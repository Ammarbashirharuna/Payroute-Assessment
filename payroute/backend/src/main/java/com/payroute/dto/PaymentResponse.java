package com.payroute.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class PaymentResponse {
    private UUID id;
    private String reference;
    private String status;
    private String senderName;
    private String recipientName;
    private String recipientCountry;
    private String sourceCurrency;
    private String destCurrency;
    private BigDecimal sourceAmount;
    private BigDecimal destAmount;
    private BigDecimal feeAmount;
    private BigDecimal fxRate;
    private String providerReference;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<LedgerEntryDTO> ledgerEntries;
    private List<StatusHistoryDTO> statusHistory;
}
