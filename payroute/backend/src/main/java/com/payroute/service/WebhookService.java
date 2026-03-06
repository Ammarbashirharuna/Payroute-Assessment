package com.payroute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payroute.dto.WebhookPayload;
import com.payroute.model.WebhookEvent;
import com.payroute.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookEventRepository webhookEventRepo;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @Value("${payroute.webhook.secret}")
    private String webhookSecret;

    /**
     * Verify HMAC-SHA256 signature.
     * Signature = HMAC-SHA256(webhookSecret, rawBody)
     * Computed in hex and compared in constant time to prevent timing attacks.
     */
    public boolean verifySignature(String rawBody, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(key);
            byte[] expectedBytes = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(expectedBytes);
            // Constant-time comparison prevents timing side-channel
            return constantTimeEquals(expected, signature);
        } catch (Exception e) {
            log.error("Signature verification error: {}", e.getMessage());
            return false;
        }
    }

    @Transactional
    public void handleWebhook(String rawBody, String signature, Map<String, String> headers) {
        // Step 1: Persist raw event BEFORE any processing
        // This ensures we never lose an inbound webhook even if processing fails
        WebhookEvent event = new WebhookEvent();
        event.setRawBody(rawBody);
        event.setSignature(signature);

        boolean sigValid = verifySignature(rawBody, signature);
        event.setSignatureValid(sigValid);

        WebhookPayload payload = null;
        try {
            payload = objectMapper.readValue(rawBody, WebhookPayload.class);
            event.setEventId(payload.getEventId());
        } catch (Exception e) {
            log.error("Failed to parse webhook body: {}", e.getMessage());
            event.setProcessingStatus(WebhookEvent.ProcessingStatus.failed);
            event.setProcessingError("Parse error: " + e.getMessage());
            webhookEventRepo.save(event);
            return;
        }

        // Check for duplicate event before saving (unique index handles race conditions)
        if (event.getEventId() != null && webhookEventRepo.existsByProviderAndEventId("default", event.getEventId())) {
            log.info("Duplicate webhook event_id {}, ignoring", event.getEventId());
            return; // silently drop — already processed
        }

        // Save the raw event (this is the audit log — always persisted)
        try {
            event = webhookEventRepo.save(event);
        } catch (Exception e) {
            // Unique index violation = duplicate event, safe to ignore
            log.info("Duplicate webhook detected via DB constraint: {}", e.getMessage());
            return;
        }

        if (!sigValid) {
            log.warn("Invalid webhook signature for event {}", event.getId());
            event.setProcessingStatus(WebhookEvent.ProcessingStatus.failed);
            event.setProcessingError("Invalid signature");
            webhookEventRepo.save(event);
            // Still return 200 — we've logged it; rejecting with 4xx would cause provider retries
            // for legitimately signed events that we may have misconfigured
            return;
        }

        // Step 2: Process the event
        try {
            paymentService.processWebhookEvent(event, payload);
            webhookEventRepo.save(event);
        } catch (Exception e) {
            log.error("Error processing webhook {}: {}", event.getId(), e.getMessage(), e);
            event.setProcessingStatus(WebhookEvent.ProcessingStatus.failed);
            event.setProcessingError(e.getMessage());
            webhookEventRepo.save(event);
            // Still return 200 — the event is logged; retries will hit idempotency check
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     * Standard String.equals() can leak information about how many
     * characters match via timing differences.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) return false;
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= (aBytes[i] ^ bBytes[i]);
        }
        return result == 0;
    }
}
