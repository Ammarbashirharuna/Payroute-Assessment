package com.payroute.controller;

import com.payroute.dto.*;
import com.payroute.service.PaymentService;
import com.payroute.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PaymentController {

    private final PaymentService paymentService;
    private final WebhookService webhookService;

    // ================================================================
    // POST /payments — Initiate a cross-border payment
    // ================================================================
    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> initiatePayment(
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        PaymentResponse response = paymentService.initiatePayment(request, idempotencyKey);
        return ResponseEntity.ok(response);
    }

    // ================================================================
    // GET /payments/:id — Get transaction details
    // ================================================================
    @GetMapping("/payments/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.getPayment(id));
    }

    // ================================================================
    // GET /payments — List payments with filters
    // ================================================================
    @GetMapping("/payments")
    public ResponseEntity<PagedPaymentResponse> listPayments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Cap page size to prevent abuse
        size = Math.min(size, 100);
        return ResponseEntity.ok(paymentService.listPayments(status, from, to, page, size));
    }

    // ================================================================
    // POST /webhooks/provider — Receive provider status updates
    // We read the raw body as a string so we can:
    //   1. Log it verbatim before any parsing (never lose an event)
    //   2. Verify HMAC over the exact bytes received
    // ================================================================
    @PostMapping("/webhooks/provider")
    public ResponseEntity<String> receiveWebhook(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) throws IOException {

        String rawBody = request.getReader().lines().collect(Collectors.joining("\n"));

        log.info("Webhook received: sig={}, body_length={}", signature, rawBody.length());

        // Always return 200 — even for invalid signatures or unknown references.
        // Reasoning: if we return non-200, the provider will retry indefinitely.
        // All error handling happens inside handleWebhook (it logs and ignores gracefully).
        webhookService.handleWebhook(rawBody, signature, headers);

        return ResponseEntity.ok("OK");
    }
}
