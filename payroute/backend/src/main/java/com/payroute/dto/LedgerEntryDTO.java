package com.payroute.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class LedgerEntryDTO {
    private UUID id;
    private UUID accountId;
    private String accountName;
    private String currency;
    private String entryType;
    private BigDecimal amount;
    private String description;
    private OffsetDateTime createdAt;
}
