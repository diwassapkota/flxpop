package com.flxpop.engine.domain.entity;

import com.flxpop.engine.domain.Gateway;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "inbound_webhook")
public class InboundWebhookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private Gateway gateway;

    @Column(name = "gateway_event_id", nullable = false, length = 120, updatable = false)
    private String gatewayEventId;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(length = 32)
    private String type;

    @Column(name = "raw_body", nullable = false, columnDefinition = "mediumtext", updatable = false)
    private String rawBody;

    @Column(length = 256, updatable = false)
    private String signature;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    public Long getId() { return id; }
    public Gateway getGateway() { return gateway; }
    public void setGateway(Gateway gateway) { this.gateway = gateway; }
    public String getGatewayEventId() { return gatewayEventId; }
    public void setGatewayEventId(String gatewayEventId) { this.gatewayEventId = gatewayEventId; }
    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getRawBody() { return rawBody; }
    public void setRawBody(String rawBody) { this.rawBody = rawBody; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
