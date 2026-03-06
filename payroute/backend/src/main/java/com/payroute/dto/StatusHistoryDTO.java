package com.payroute.dto;

import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class StatusHistoryDTO {
    private String fromStatus;
    private String toStatus;
    private String reason;
    private String createdBy;
    private OffsetDateTime createdAt;
}
