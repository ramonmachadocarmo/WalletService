package com.finaya.pixwallet.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"scope", "idempotency_key"})
})
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "scope", nullable = false, length = 100)
    private String scope;

    @Column(name = "idempotency_key", nullable = false, length = 500)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "response_status", nullable = false)
    private Integer responseStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected IdempotencyRecord() {}

    public IdempotencyRecord(String scope, String idempotencyKey, String requestHash, String responseBody, Integer responseStatus) {
        this.scope = Objects.requireNonNull(scope, "Scope cannot be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "Idempotency key cannot be null");
        this.requestHash = Objects.requireNonNull(requestHash, "Request hash cannot be null");
        this.responseBody = responseBody;
        this.responseStatus = Objects.requireNonNull(responseStatus, "Response status cannot be null");
        this.createdAt = LocalDateTime.now();
        this.expiresAt = LocalDateTime.now().plusHours(24);
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public String getScope() {
        return scope;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdempotencyRecord that = (IdempotencyRecord) o;
        return Objects.equals(scope, that.scope) && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, idempotencyKey);
    }

    @Override
    public String toString() {
        return "IdempotencyRecord{" +
                "id=" + id +
                ", scope='" + scope + '\'' +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                ", responseStatus=" + responseStatus +
                ", createdAt=" + createdAt +
                '}';
    }
}