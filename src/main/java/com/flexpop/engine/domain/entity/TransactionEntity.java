package com.flexpop.engine.domain.entity;

import com.flexpop.engine.domain.Country;
import com.flexpop.engine.domain.Currency;
import com.flexpop.engine.domain.Device;
import com.flexpop.engine.domain.Gateway;
import com.flexpop.engine.domain.TxnStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "transaction")
public class TransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, length = 32, unique = true, updatable = false)
    private String publicId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Country country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Device device;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Gateway gateway;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TxnStatus status = TxnStatus.CREATED;

    @Column(name = "gateway_ref", length = 120)
    private String gatewayRef;

    @Column(name = "app_intent_url", length = 2048)
    private String appIntentUrl;

    @Column(name = "qr_payload", columnDefinition = "TEXT")
    private String qrPayload;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    public long getRefundedAmountMinor() { return refundedAmountMinor; }
    public void setRefundedAmountMinor(long refundedAmountMinor) { this.refundedAmountMinor = refundedAmountMinor; }
    public long getRefundableAmountMinor() { return amountMinor - refundedAmountMinor; }
    public Currency getCurrency() { return currency; }
    public void setCurrency(Currency currency) { this.currency = currency; }
    public Country getCountry() { return country; }
    public void setCountry(Country country) { this.country = country; }
    public Device getDevice() { return device; }
    public void setDevice(Device device) { this.device = device; }
    public Gateway getGateway() { return gateway; }
    public void setGateway(Gateway gateway) { this.gateway = gateway; }
    public TxnStatus getStatus() { return status; }
    public void setStatus(TxnStatus status) { this.status = status; }
    public String getGatewayRef() { return gatewayRef; }
    public void setGatewayRef(String gatewayRef) { this.gatewayRef = gatewayRef; }
    public String getAppIntentUrl() { return appIntentUrl; }
    public void setAppIntentUrl(String appIntentUrl) { this.appIntentUrl = appIntentUrl; }
    public String getQrPayload() { return qrPayload; }
    public void setQrPayload(String qrPayload) { this.qrPayload = qrPayload; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
