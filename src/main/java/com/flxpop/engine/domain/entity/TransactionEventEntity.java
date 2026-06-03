package com.flxpop.engine.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * APPEND-ONLY. There are no setters that should be called after persist —
 * @Immutable enforces this at the Hibernate level. If you find yourself wanting
 * to mutate one of these rows, you're doing reconciliation wrong; emit a new event.
 */
@Entity
@Immutable
@Table(name = "transaction_event")
public class TransactionEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, length = 36, unique = true, updatable = false)
    private String publicId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private Long transactionId;

    @Column(nullable = false, length = 32, updatable = false)
    private String type;

    @Column(nullable = false, length = 16, updatable = false)
    private String source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "json", updatable = false)
    private Map<String, Object> payload;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }
    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public Instant getOccurredAt() { return occurredAt; }
}
