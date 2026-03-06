package com.payroute.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    public enum Status { initiated, processing, completed, failed, reversed }

    public static boolean isValidTransition(Status from, Status to) {
        return switch (from) {
            case initiated  -> to == Status.processing || to == Status.failed;
            case processing -> to == Status.completed || to == Status.failed;
            case failed     -> to == Status.reversed;
            case completed  -> false;
            case reversed   -> false;
        };
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "reference", nullable = false, unique = true)
    private String reference;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_account_id", nullable = false)
    private Account senderAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_account_id", nullable = false)
    private Account recipientAccount;

    @Column(name = "recipient_name", nullable = false)
    private String recipientName;

    @Column(name = "recipient_country", nullable = false, length = 2)
    private String recipientCountry;

    @Column(name = "source_currency", nullable = false, length = 3)
    private String sourceCurrency;

    @Column(name = "dest_currency", nullable = false, length = 3)
    private String destCurrency;

    @Column(name = "source_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal sourceAmount;

    @Column(name = "dest_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal destAmount;

    @Column(name = "fee_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fx_quote_id")
    private FxQuote fxQuote;

    @Column(name = "fx_rate", nullable = false, precision = 20, scale = 8)
    private BigDecimal fxRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.initiated;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "provider_submitted_at")
    private OffsetDateTime providerSubmittedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "failed_at")
    private OffsetDateTime failedAt;

    @Column(name = "reversed_at")
    private OffsetDateTime reversedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Account getSenderAccount() { return senderAccount; }
    public void setSenderAccount(Account senderAccount) { this.senderAccount = senderAccount; }
    public Account getRecipientAccount() { return recipientAccount; }
    public void setRecipientAccount(Account recipientAccount) { this.recipientAccount = recipientAccount; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public String getRecipientCountry() { return recipientCountry; }
    public void setRecipientCountry(String recipientCountry) { this.recipientCountry = recipientCountry; }
    public String getSourceCurrency() { return sourceCurrency; }
    public void setSourceCurrency(String sourceCurrency) { this.sourceCurrency = sourceCurrency; }
    public String getDestCurrency() { return destCurrency; }
    public void setDestCurrency(String destCurrency) { this.destCurrency = destCurrency; }
    public BigDecimal getSourceAmount() { return sourceAmount; }
    public void setSourceAmount(BigDecimal sourceAmount) { this.sourceAmount = sourceAmount; }
    public BigDecimal getDestAmount() { return destAmount; }
    public void setDestAmount(BigDecimal destAmount) { this.destAmount = destAmount; }
    public BigDecimal getFeeAmount() { return feeAmount; }
    public void setFeeAmount(BigDecimal feeAmount) { this.feeAmount = feeAmount; }
    public FxQuote getFxQuote() { return fxQuote; }
    public void setFxQuote(FxQuote fxQuote) { this.fxQuote = fxQuote; }
    public BigDecimal getFxRate() { return fxRate; }
    public void setFxRate(BigDecimal fxRate) { this.fxRate = fxRate; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
    public OffsetDateTime getProviderSubmittedAt() { return providerSubmittedAt; }
    public void setProviderSubmittedAt(OffsetDateTime providerSubmittedAt) { this.providerSubmittedAt = providerSubmittedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public OffsetDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(OffsetDateTime failedAt) { this.failedAt = failedAt; }
    public OffsetDateTime getReversedAt() { return reversedAt; }
    public void setReversedAt(OffsetDateTime reversedAt) { this.reversedAt = reversedAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
