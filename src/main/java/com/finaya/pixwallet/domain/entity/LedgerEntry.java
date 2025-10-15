package com.finaya.pixwallet.domain.entity;

import com.finaya.pixwallet.domain.valueobject.Money;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries", indexes = {
    @Index(name = "idx_ledger_wallet_created", columnList = "wallet_id, created_at"),
    @Index(name = "idx_ledger_transaction_id", columnList = "transaction_id")
})
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(name = "amount_centavos", nullable = false)
    private BigInteger amountCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private LedgerEntryType entryType;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "transaction_id", nullable = false, length = 100)
    private String transactionId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "balance_after_centavos")
    private BigInteger balanceAfterCents;

    protected LedgerEntry() {}

    public LedgerEntry(Wallet wallet, Money amount, LedgerEntryType entryType, String description, String transactionId) {
        this.wallet = Objects.requireNonNull(wallet, "Wallet cannot be null");
        this.amountCents = Objects.requireNonNull(amount, "Amount cannot be null").cents();
        this.entryType = Objects.requireNonNull(entryType, "Entry type cannot be null");
        this.description = Objects.requireNonNull(description, "Description cannot be null");
        this.transactionId = Objects.requireNonNull(transactionId, "Transaction ID cannot be null");
        this.createdAt = LocalDateTime.now();
        this.balanceAfterCents = wallet.getBalance().cents();
    }

    public UUID getId() {
        return id;
    }

    public Wallet getWallet() {
        return wallet;
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

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public String getDescription() {
        return description;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Money getBalanceAfter() {
        return balanceAfterCents != null ? Money.fromCents(balanceAfterCents) : Money.ZERO;
    }

    /**
     * Método auxiliar para compatibilidade com código legado
     */
    public BigDecimal getBalanceAfterAsDecimal() {
        return getBalanceAfter().toReais();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerEntry that = (LedgerEntry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "LedgerEntry{" +
                "id=" + id +
                ", amount=" + getAmount() +
                ", entryType=" + entryType +
                ", description='" + description + '\'' +
                ", transactionId='" + transactionId + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}