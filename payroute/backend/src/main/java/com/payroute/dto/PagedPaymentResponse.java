package com.payroute.dto;

import lombok.Data;
import java.util.List;

@Data
public class PagedPaymentResponse {
    private List<PaymentResponse> data;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
