package com.payroute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payroute.dto.*;
import com.payroute.exception.InsufficientFundsException;
import com.payroute.exception.InvalidStateTransitionException;
import com.payroute.exception.ResourceNotFoundException;
import com.payroute.model.*;
import com.payroute.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final TransactionRepository transactionRepo;
    private final AccountBalanceRepository balanceRepo;
    private final AccountRepository accountRepo;
    private final FxQuoteRepository fxQuoteRepo;
    private final LedgerEntryRepository ledgerRepo;
    private final TransactionStatusHistoryRepository historyRepo;
    private final IdempotencyKeyRepository idempotencyRepo;
    private final ObjectMapper objectMapper;

    @Value("${payroute.fx.quote-ttl-minutes:5}")
    private int fxQuoteTtlMinutes;

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PaymentResponse initiatePayment(PaymentRequest request, String idempotencyKey) {
        // 1. Check idempotency — return cached response if key was seen before
        Optional<IdempotencyKey> existingKey = idempotencyRepo.findByKeyAndEndpoint(idempotencyKey, "/payments");
        if (existingKey.isPresent() && existingKey.get().getResponseBody() != null) {
            log.info("Idempotency hit for key: {}", idempotencyKey);
            return deserializeResponse(existingKey.get().getResponseBody());
        }

        // 2. Validate sender account
        Account sender = accountRepo.findById(request.getSenderAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Sender account not found"));

        // 3. Lock the sender's balance row
        AccountBalance senderBalance = balanceRepo
                .findByAccountIdAndCurrencyForUpdate(sender.getId(), request.getSourceCurrency())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Sender has no " + request.getSourceCurrency() + " account"));

        // 4. Obtain FX quote
        FxQuote quote = obtainFxQuote(request.getSourceCurrency(), request.getDestCurrency());

        // 5. Calculate amounts
        BigDecimal fxRate = quote.getRate();
        BigDecimal feeAmount = request.getSourceAmount()
                .multiply(quote.getFeeRate())
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal totalDebit = request.getSourceAmount().add(feeAmount);
        BigDecimal destAmount = request.getSourceAmount()
                .multiply(fxRate)
                .setScale(6, RoundingMode.HALF_UP);

        // 6. Insufficient funds check
        if (senderBalance.getBalance().compareTo(totalDebit) < 0) {
            throw new InsufficientFundsException(
                    String.format("Insufficient funds: have %s %s, need %s %s",
                            senderBalance.getBalance(), request.getSourceCurrency(),
                            totalDebit, request.getSourceCurrency()));
        }

        // 7. Find or create recipient/transit account
        Account recipient = findOrCreateSystemTransitAccount();

        // 8. Save idempotency key row FIRST — transaction table has a FK to it
        IdempotencyKey iKey = new IdempotencyKey();
        iKey.setKey(idempotencyKey);
        iKey.setEndpoint("/payments");
        iKey.setExpiresAt(OffsetDateTime.now().plusHours(24));
        idempotencyRepo.save(iKey);

        // 9. Create transaction record
        Transaction tx = new Transaction();
        tx.setReference(generateReference());
        tx.setIdempotencyKey(idempotencyKey);
        tx.setSenderAccount(sender);
        tx.setRecipientAccount(recipient);
        tx.setRecipientName(request.getRecipientName());
        tx.setRecipientCountry(request.getRecipientCountry());
        tx.setSourceCurrency(request.getSourceCurrency());
        tx.setDestCurrency(request.getDestCurrency());
        tx.setSourceAmount(request.getSourceAmount());
        tx.setDestAmount(destAmount);
        tx.setFeeAmount(feeAmount);
        tx.setFxQuote(quote);
        tx.setFxRate(fxRate);
        tx.setStatus(Transaction.Status.initiated);
        tx = transactionRepo.save(tx);

        // 10. Record initial status history
        recordStatusTransition(tx, null, Transaction.Status.initiated, "Payment initiated");

        // 11. DEBIT sender — double-entry bookkeeping
        debitAccount(senderBalance, totalDebit);
        createLedgerEntries(tx, sender, recipient, request.getSourceCurrency(), totalDebit, feeAmount);

        // 12. Mark quote as used
        quote.setUsed(true);
        fxQuoteRepo.save(quote);

        // 13. Submit to downstream provider
        String providerRef = submitToProvider(tx);
        tx.setProviderReference(providerRef);
        tx.setProviderSubmittedAt(OffsetDateTime.now());
        tx.setStatus(Transaction.Status.processing);
        tx = transactionRepo.save(tx);

        recordStatusTransition(tx, Transaction.Status.initiated, Transaction.Status.processing,
                "Submitted to provider: " + providerRef);

        // 14. Update idempotency key with the full response body
        PaymentResponse response = mapToResponse(tx);
        try {
            iKey.setTransactionId(tx.getId());
            iKey.setResponseStatus(200);
            iKey.setResponseBody(objectMapper.writeValueAsString(response));
            idempotencyRepo.save(iKey);
        } catch (Exception e) {
            log.error("Failed to update idempotency key: {}", e.getMessage());
        }

        return response;
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID id) {
        Transaction tx = transactionRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + id));
        return mapToResponse(tx);
    }

    @Transactional(readOnly = true)
    public PagedPaymentResponse listPayments(String status, OffsetDateTime from,
                                             OffsetDateTime to, int page, int size) {
        Transaction.Status statusEnum = null;
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("all")) {
            statusEnum = Transaction.Status.valueOf(status.toLowerCase());
        }

        Page<Transaction> txPage;
        PageRequest pageable = PageRequest.of(page, size);

        if (statusEnum != null && from != null && to != null) {
            txPage = transactionRepo.findByStatusAndCreatedAtBetweenOrderByCreatedAtDesc(statusEnum, from, to, pageable);
        } else if (statusEnum != null) {
            txPage = transactionRepo.findByStatusOrderByCreatedAtDesc(statusEnum, pageable);
        } else if (from != null && to != null) {
            txPage = transactionRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to, pageable);
        } else {
            txPage = transactionRepo.findAllByOrderByCreatedAtDesc(pageable);
        }

        PagedPaymentResponse response = new PagedPaymentResponse();
        response.setData(txPage.getContent().stream().map(this::mapToResponse).toList());
        response.setPage(page);
        response.setSize(size);
        response.setTotalElements(txPage.getTotalElements());
        response.setTotalPages(txPage.getTotalPages());
        return response;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void processWebhookEvent(WebhookEvent webhookEvent, WebhookPayload payload) {
        if (webhookEvent.getEventId() != null && webhookEventAlreadyProcessed(webhookEvent)) {
            log.info("Webhook event {} already processed, skipping", webhookEvent.getEventId());
            webhookEvent.setProcessingStatus(WebhookEvent.ProcessingStatus.ignored);
            return;
        }

        Optional<Transaction> txOpt = transactionRepo.findByProviderReference(payload.getReference());

        if (txOpt.isEmpty()) {
            log.warn("Webhook received for unknown provider reference: {}", payload.getReference());
            webhookEvent.setProcessingStatus(WebhookEvent.ProcessingStatus.ignored);
            return;
        }

        Transaction tx = txOpt.get();
        webhookEvent.setTransaction(tx);

        String statusStr = payload.getStatus();
        Transaction.Status newStatus;
        try {
            newStatus = Transaction.Status.valueOf(statusStr.toLowerCase());
        } catch (IllegalArgumentException e) {
            log.error("Unknown status in webhook: {}", statusStr);
            webhookEvent.setProcessingStatus(WebhookEvent.ProcessingStatus.failed);
            webhookEvent.setProcessingError("Unknown status: " + statusStr);
            return;
        }

        if (!Transaction.isValidTransition(tx.getStatus(), newStatus)) {
            log.warn("Invalid transition {} -> {} for tx {}", tx.getStatus(), newStatus, tx.getId());
            webhookEvent.setProcessingStatus(WebhookEvent.ProcessingStatus.ignored);
            return;
        }

        switch (newStatus) {
            case completed -> handleCompletion(tx, payload);
            case failed    -> handleFailure(tx, payload);
            default        -> log.warn("Unhandled webhook status: {}", newStatus);
        }

        webhookEvent.setProcessingStatus(WebhookEvent.ProcessingStatus.processed);
        webhookEvent.setProcessedAt(OffsetDateTime.now());
    }

    private void handleCompletion(Transaction tx, WebhookPayload payload) {
        AccountBalance recipientBalance = balanceRepo
                .findByAccountIdAndCurrencyForUpdate(tx.getRecipientAccount().getId(), tx.getDestCurrency())
                .orElseGet(() -> createBalance(tx.getRecipientAccount(), tx.getDestCurrency()));

        recipientBalance.setBalance(recipientBalance.getBalance().add(tx.getDestAmount()));
        balanceRepo.save(recipientBalance);

        LedgerEntry debitTransit = new LedgerEntry();
        debitTransit.setTransaction(tx);
        debitTransit.setAccount(findOrCreateSystemTransitAccount());
        debitTransit.setCurrency(tx.getDestCurrency());
        debitTransit.setEntryType(LedgerEntry.EntryType.DEBIT);
        debitTransit.setAmount(tx.getDestAmount());
        debitTransit.setDescription("Settlement: debit transit account");
        ledgerRepo.save(debitTransit);

        LedgerEntry creditRecipient = new LedgerEntry();
        creditRecipient.setTransaction(tx);
        creditRecipient.setAccount(tx.getRecipientAccount());
        creditRecipient.setCurrency(tx.getDestCurrency());
        creditRecipient.setEntryType(LedgerEntry.EntryType.CREDIT);
        creditRecipient.setAmount(tx.getDestAmount());
        creditRecipient.setDescription("Settlement: credit recipient");
        ledgerRepo.save(creditRecipient);

        tx.setStatus(Transaction.Status.completed);
        tx.setCompletedAt(OffsetDateTime.now());
        transactionRepo.save(tx);
        recordStatusTransition(tx, Transaction.Status.processing, Transaction.Status.completed, "Provider confirmed");
        log.info("Transaction {} completed", tx.getReference());
    }

    private void handleFailure(Transaction tx, WebhookPayload payload) {
        AccountBalance senderBalance = balanceRepo
                .findByAccountIdAndCurrencyForUpdate(tx.getSenderAccount().getId(), tx.getSourceCurrency())
                .orElseThrow(() -> new ResourceNotFoundException("Sender balance missing during reversal"));

        BigDecimal totalToReturn = tx.getSourceAmount().add(tx.getFeeAmount());
        senderBalance.setBalance(senderBalance.getBalance().add(totalToReturn));
        balanceRepo.save(senderBalance);

        LedgerEntry creditSender = new LedgerEntry();
        creditSender.setTransaction(tx);
        creditSender.setAccount(tx.getSenderAccount());
        creditSender.setCurrency(tx.getSourceCurrency());
        creditSender.setEntryType(LedgerEntry.EntryType.CREDIT);
        creditSender.setAmount(totalToReturn);
        creditSender.setDescription("Reversal: credit sender after failed payment");
        ledgerRepo.save(creditSender);

        LedgerEntry debitTransit = new LedgerEntry();
        debitTransit.setTransaction(tx);
        debitTransit.setAccount(findOrCreateSystemTransitAccount());
        debitTransit.setCurrency(tx.getSourceCurrency());
        debitTransit.setEntryType(LedgerEntry.EntryType.DEBIT);
        debitTransit.setAmount(totalToReturn);
        debitTransit.setDescription("Reversal: debit transit account");
        ledgerRepo.save(debitTransit);

        tx.setStatus(Transaction.Status.failed);
        tx.setFailedAt(OffsetDateTime.now());
        tx.setFailureReason(payload.getFailureReason());
        transactionRepo.save(tx);
        recordStatusTransition(tx, Transaction.Status.processing, Transaction.Status.failed,
                payload.getFailureReason() != null ? payload.getFailureReason() : "Provider reported failure");
        log.info("Transaction {} failed, funds reversed", tx.getReference());
    }

    private FxQuote obtainFxQuote(String from, String to) {
        BigDecimal simulatedRate = getSimulatedRate(from, to);
        FxQuote quote = new FxQuote();
        quote.setSourceCurrency(from);
        quote.setDestCurrency(to);
        quote.setRate(simulatedRate);
        quote.setFeeRate(new BigDecimal("0.015"));
        quote.setExpiresAt(OffsetDateTime.now().plusMinutes(fxQuoteTtlMinutes));
        return fxQuoteRepo.save(quote);
    }

    private BigDecimal getSimulatedRate(String from, String to) {
        if ("NGN".equals(from) && "USD".equals(to)) return new BigDecimal("0.000647");
        if ("NGN".equals(from) && "GBP".equals(to)) return new BigDecimal("0.000511");
        if ("NGN".equals(from) && "EUR".equals(to)) return new BigDecimal("0.000598");
        if ("NGN".equals(from) && "KES".equals(to)) return new BigDecimal("0.0836");
        if ("NGN".equals(from) && "GHS".equals(to)) return new BigDecimal("0.00983");
        return new BigDecimal("1.0");
    }

    private void debitAccount(AccountBalance balance, BigDecimal amount) {
        balance.setBalance(balance.getBalance().subtract(amount));
        balanceRepo.save(balance);
    }

    private void createLedgerEntries(Transaction tx, Account sender, Account transit,
                                     String currency, BigDecimal totalDebit, BigDecimal feeAmount) {
        LedgerEntry debitSender = new LedgerEntry();
        debitSender.setTransaction(tx);
        debitSender.setAccount(sender);
        debitSender.setCurrency(currency);
        debitSender.setEntryType(LedgerEntry.EntryType.DEBIT);
        debitSender.setAmount(totalDebit);
        debitSender.setDescription("Payment initiated: " + tx.getReference());
        ledgerRepo.save(debitSender);

        LedgerEntry creditTransit = new LedgerEntry();
        creditTransit.setTransaction(tx);
        creditTransit.setAccount(transit);
        creditTransit.setCurrency(currency);
        creditTransit.setEntryType(LedgerEntry.EntryType.CREDIT);
        creditTransit.setAmount(totalDebit);
        creditTransit.setDescription("Payment in transit: " + tx.getReference());
        ledgerRepo.save(creditTransit);
    }

    private String submitToProvider(Transaction tx) {
        return "PRV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private void recordStatusTransition(Transaction tx, Transaction.Status from,
                                        Transaction.Status to, String reason) {
        TransactionStatusHistory history = new TransactionStatusHistory();
        history.setTransaction(tx);
        history.setFromStatus(from != null ? from.name() : null);
        history.setToStatus(to.name());
        history.setReason(reason);
        historyRepo.save(history);
    }

    private PaymentResponse deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, PaymentResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cached response", e);
        }
    }

    private Account findOrCreateSystemTransitAccount() {
        return accountRepo.findById(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .orElseThrow(() -> new ResourceNotFoundException("System transit account missing"));
    }

    private AccountBalance createBalance(Account account, String currency) {
        AccountBalance balance = new AccountBalance();
        balance.setAccount(account);
        balance.setCurrency(currency);
        balance.setBalance(BigDecimal.ZERO);
        return balanceRepo.save(balance);
    }

    private String generateReference() {
        return "PAY-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private boolean webhookEventAlreadyProcessed(WebhookEvent webhookEvent) {
        return false;
    }

    public PaymentResponse mapToResponse(Transaction tx) {
        PaymentResponse resp = new PaymentResponse();
        resp.setId(tx.getId());
        resp.setReference(tx.getReference());
        resp.setStatus(tx.getStatus().name());
        resp.setSenderName(tx.getSenderAccount().getOwnerName());
        resp.setRecipientName(tx.getRecipientName());
        resp.setRecipientCountry(tx.getRecipientCountry());
        resp.setSourceCurrency(tx.getSourceCurrency());
        resp.setDestCurrency(tx.getDestCurrency());
        resp.setSourceAmount(tx.getSourceAmount());
        resp.setDestAmount(tx.getDestAmount());
        resp.setFeeAmount(tx.getFeeAmount());
        resp.setFxRate(tx.getFxRate());
        resp.setProviderReference(tx.getProviderReference());
        resp.setCreatedAt(tx.getCreatedAt());
        resp.setUpdatedAt(tx.getUpdatedAt());

        List<LedgerEntry> entries = ledgerRepo.findByTransactionIdOrderByCreatedAtAsc(tx.getId());
        resp.setLedgerEntries(entries.stream().map(e -> {
            LedgerEntryDTO dto = new LedgerEntryDTO();
            dto.setId(e.getId());
            dto.setAccountId(e.getAccount().getId());
            dto.setAccountName(e.getAccount().getOwnerName());
            dto.setCurrency(e.getCurrency());
            dto.setEntryType(e.getEntryType().name());
            dto.setAmount(e.getAmount());
            dto.setDescription(e.getDescription());
            dto.setCreatedAt(e.getCreatedAt());
            return dto;
        }).toList());

        List<TransactionStatusHistory> history = historyRepo
                .findByTransactionIdOrderByCreatedAtAsc(tx.getId());
        resp.setStatusHistory(history.stream().map(h -> {
            StatusHistoryDTO dto = new StatusHistoryDTO();
            dto.setFromStatus(h.getFromStatus());
            dto.setToStatus(h.getToStatus());
            dto.setReason(h.getReason());
            dto.setCreatedBy(h.getCreatedBy());
            dto.setCreatedAt(h.getCreatedAt());
            return dto;
        }).toList());

        return resp;
    }
}