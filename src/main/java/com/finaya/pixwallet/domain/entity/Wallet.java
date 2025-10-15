package com.finaya.pixwallet.domain.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.finaya.pixwallet.domain.valueobject.Money;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "balance_centavos", nullable = false)
    private BigInteger balanceCents;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "wallet", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonManagedReference
    private List<PixKey> pixKeys = new ArrayList<>();

    @OneToMany(mappedBy = "wallet", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<LedgerEntry> ledgerEntries = new ArrayList<>();

    protected Wallet() {}

    public Wallet(String userId) {
        this.userId = Objects.requireNonNull(userId, "User ID cannot be null");
        this.balanceCents = BigInteger.ZERO;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void credit(Money amount, String description, String transactionId) {
        validateAmount(amount);
        this.balanceCents = this.balanceCents.add(amount.cents());
        this.updatedAt = LocalDateTime.now();

        LedgerEntry entry = new LedgerEntry(this, amount, LedgerEntryType.CREDIT, description, transactionId);
        this.ledgerEntries.add(entry);
    }

    public void debit(Money amount, String description, String transactionId) {
        validateAmount(amount);
        Money currentBalance = getBalance();
        if (currentBalance.isLessThan(amount)) {
            throw new IllegalArgumentException("Insufficient funds. Current balance: " + currentBalance + ", requested: " + amount);
        }

        this.balanceCents = this.balanceCents.subtract(amount.cents());
        this.updatedAt = LocalDateTime.now();

        LedgerEntry entry = new LedgerEntry(this, amount.negate(), LedgerEntryType.DEBIT, description, transactionId);
        this.ledgerEntries.add(entry);
    }

    public void addPixKey(PixKey pixKey) {
        pixKey.setWallet(this);
        this.pixKeys.add(pixKey);
    }

    private void validateAmount(Money amount) {
        if (amount == null || amount.isZero() || amount.isNegative()) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    public Money calculateBalanceAt(LocalDateTime timestamp) {
        BigInteger totalCents = ledgerEntries.stream()
                .filter(entry -> entry.getCreatedAt().isBefore(timestamp) || entry.getCreatedAt().isEqual(timestamp))
                .map(entry -> entry.getAmount().cents())
                .reduce(BigInteger.ZERO, BigInteger::add);
        return Money.fromCents(totalCents);
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public Money getBalance() {
        return Money.fromCents(balanceCents);
    }

    /**
     * Método auxiliar para compatibilidade com código legado
     */
    public BigDecimal getBalanceAsDecimal() {
        return getBalance().toReais();
    }

    public Long getVersion() {
        return version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public List<PixKey> getPixKeys() {
        return new ArrayList<>(pixKeys);
    }

    public List<LedgerEntry> getLedgerEntries() {
        return new ArrayList<>(ledgerEntries);
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Wallet wallet = (Wallet) o;
        return Objects.equals(id, wallet.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Wallet{" +
                "id=" + id +
                ", userId='" + userId + '\'' +
                ", balance=" + getBalance() +
                ", version=" + version +
                '}';
    }
}