package com.finaya.pixwallet.domain.entity;

import com.finaya.pixwallet.domain.valueobject.Money;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "pix_transfers", indexes = {
    @Index(name = "idx_transfer_end_to_end_id", columnList = "end_to_end_id", unique = true),
    @Index(name = "idx_transfer_idempotency_key", columnList = "idempotency_key", unique = true)
})
public class PixTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "end_to_end_id", nullable = false, unique = true, length = 32)
    private String endToEndId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "from_wallet_id", nullable = false)
    private UUID fromWalletId;

    @Column(name = "to_pix_key", nullable = false, length = 500)
    private String toPixKey;

    @Column(name = "amount_cents", nullable = false)
    private BigInteger amountCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PixTransferStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Version
    @Column(name = "version")
    private Long version;

    protected PixTransfer() {}

    public PixTransfer(String endToEndId, String idempotencyKey, UUID fromWalletId, String toPixKey, Money amount) {
        this.endToEndId = Objects.requireNonNull(endToEndId, "End-to-end ID cannot be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "Idempotency key cannot be null");
        this.fromWalletId = Objects.requireNonNull(fromWalletId, "From wallet ID cannot be null");
        this.toPixKey = Objects.requireNonNull(toPixKey, "To Pix key cannot be null");

        Objects.requireNonNull(amount, "Amount cannot be null");
        amount.validateForPix(); // Valida limites PIX
        this.amountCents = amount.cents();

        this.status = PixTransferStatus.PENDING;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void confirm() {
        if (this.status != PixTransferStatus.PENDING) {
            throw new IllegalStateException("Transfer can only be confirmed from PENDING status. Current status: " + this.status);
        }
        this.status = PixTransferStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void reject(String reason) {
        if (this.status != PixTransferStatus.PENDING) {
            throw new IllegalStateException("Transfer can only be rejected from PENDING status. Current status: " + this.status);
        }
        this.status = PixTransferStatus.REJECTED;
        this.rejectionReason = reason;
        this.rejectedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isPending() {
        return this.status == PixTransferStatus.PENDING;
    }

    public boolean isConfirmed() {
        return this.status == PixTransferStatus.CONFIRMED;
    }

    public boolean isRejected() {
        return this.status == PixTransferStatus.REJECTED;
    }

    public UUID getId() {
        return id;
    }

    public String getEndToEndId() {
        return endToEndId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getFromWalletId() {
        return fromWalletId;
    }

    public String getToPixKey() {
        return toPixKey;
    }

    public Money getAmount() {
        return Money.fromCents(amountCents);
    }

    /**
     * Método auxiliar para compatibilidade com código legado
     */
    public BigDecimal getAmountAsDecimal() {
        return getAmount().toReais();
    }

    public PixTransferStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public LocalDateTime getRejectedAt() {
        return rejectedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Long getVersion() {
        return version;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PixTransfer that = (PixTransfer) o;
        return Objects.equals(endToEndId, that.endToEndId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(endToEndId);
    }

    @Override
    public String toString() {
        return "PixTransfer{" +
                "id=" + id +
                ", endToEndId='" + endToEndId + '\'' +
                ", fromWalletId=" + fromWalletId +
                ", toPixKey='" + toPixKey + '\'' +
                ", amount=" + getAmount() +
                ", status=" + status +
                '}';
    }
}