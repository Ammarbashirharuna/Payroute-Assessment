package com.payroute.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
public class PaymentRequest {
    @NotNull
    private UUID senderAccountId;

    @NotBlank
    private String recipientName;

    @NotBlank @Size(min = 2, max = 2)
    private String recipientCountry;

    @NotBlank @Size(min = 3, max = 3)
    private String destCurrency;

    @NotNull @DecimalMin("0.01")
    private BigDecimal sourceAmount;

    @NotBlank @Size(min = 3, max = 3)
    private String sourceCurrency;
}
