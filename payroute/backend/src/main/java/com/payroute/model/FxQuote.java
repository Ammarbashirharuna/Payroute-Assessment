package com.payroute.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "fx_quotes")
public class FxQuote {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "source_currency", nullable = false, length = 3)
    private String sourceCurrency;

    @Column(name = "dest_currency", nullable = false, length = 3)
    private String destCurrency;

    @Column(name = "rate", nullable = false, precision = 20, scale = 8)
    private BigDecimal rate;

    @Column(name = "fee_rate", nullable = false, precision = 8, scale = 6)
    private BigDecimal feeRate = new BigDecimal("0.015");

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public boolean isExpired() { return OffsetDateTime.now().isAfter(expiresAt); }

    public UUID getId() { return id; }
    public String getSourceCurrency() { return sourceCurrency; }
    public void setSourceCurrency(String s) { this.sourceCurrency = s; }
    public String getDestCurrency() { return destCurrency; }
    public void setDestCurrency(String d) { this.destCurrency = d; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public BigDecimal getFeeRate() { return feeRate; }
    public void setFeeRate(BigDecimal feeRate) { this.feeRate = feeRate; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
