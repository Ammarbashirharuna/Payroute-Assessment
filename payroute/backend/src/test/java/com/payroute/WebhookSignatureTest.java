package com.payroute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payroute.repository.WebhookEventRepository;
import com.payroute.service.PaymentService;
import com.payroute.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureTest {

    private WebhookService webhookService;
    private static final String SECRET = "test-secret-key";

    @BeforeEach
    void setUp() {
        webhookService = new WebhookService(
            Mockito.mock(WebhookEventRepository.class),
            Mockito.mock(PaymentService.class),
            new ObjectMapper()
        );
        ReflectionTestUtils.setField(webhookService, "webhookSecret", SECRET);
    }

    @Test
    void valid_signature_is_accepted() throws Exception {
        String body = "{\"status\":\"completed\",\"reference\":\"PRV-123\"}";
        String sig = computeHmac(body, SECRET);
        assertTrue(webhookService.verifySignature(body, sig));
    }

    @Test
    void tampered_body_fails_verification() throws Exception {
        String originalBody = "{\"status\":\"completed\",\"reference\":\"PRV-123\"}";
        String tamperedBody = "{\"status\":\"completed\",\"reference\":\"PRV-999\"}";
        String sig = computeHmac(originalBody, SECRET);
        assertFalse(webhookService.verifySignature(tamperedBody, sig));
    }

    @Test
    void wrong_secret_fails_verification() throws Exception {
        String body = "{\"status\":\"completed\"}";
        String sig = computeHmac(body, "wrong-secret");
        assertFalse(webhookService.verifySignature(body, sig));
    }

    @Test
    void null_signature_fails_gracefully() {
        assertFalse(webhookService.verifySignature("body", null));
    }

    private String computeHmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
