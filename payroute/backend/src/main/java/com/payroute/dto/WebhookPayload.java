package com.payroute.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class WebhookPayload {
    private String eventId;
    private String reference;
    private String status;
    private BigDecimal amount;
    private String currency;
    private String failureReason;
}
