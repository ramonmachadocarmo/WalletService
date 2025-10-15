package com.finaya.pixwallet.unit;

import com.finaya.pixwallet.domain.entity.Wallet;
import com.finaya.pixwallet.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class WalletTest {

    @Test
    void shouldCreateWalletWithZeroBalance() {
        String userId = "user123";

        Wallet wallet = new Wallet(userId);

        assertEquals(userId, wallet.getUserId());
        assertEquals(Money.ZERO, wallet.getBalance());
        assertNotNull(wallet.getCreatedAt());
        assertTrue(wallet.getLedgerEntries().isEmpty());
        assertTrue(wallet.getPixKeys().isEmpty());
    }

    @Test
    void shouldNotCreateWalletWithNullUserId() {
        assertThrows(NullPointerException.class, () -> new Wallet(null));
    }

    @Test
    void shouldCreditAmount() {
        Wallet wallet = new Wallet("user123");
        Money amount = Money.fromReais("100.50");

        wallet.credit(amount, "Test deposit", "TX123");

        assertEquals(amount, wallet.getBalance());
        assertEquals(1, wallet.getLedgerEntries().size());
    }

    @Test
    void shouldDebitAmount() {
        Wallet wallet = new Wallet("user123");
        Money creditAmount = Money.fromReais("100.00");
        Money debitAmount = Money.fromReais("30.50");

        wallet.credit(creditAmount, "Deposit", "TX1");
        wallet.debit(debitAmount, "Withdrawal", "TX2");

        assertEquals(Money.fromReais("69.50"), wallet.getBalance());
        assertEquals(2, wallet.getLedgerEntries().size());
    }

    @Test
    void shouldNotDebitWhenInsufficientFunds() {
        Wallet wallet = new Wallet("user123");
        Money amount = Money.fromReais("100.00");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> wallet.debit(amount, "Test withdrawal", "TX123")
        );

        assertTrue(exception.getMessage().contains("Insufficient funds"));
        assertEquals(Money.ZERO, wallet.getBalance());
    }

    @Test
    void shouldNotCreditNegativeAmount() {
        Wallet wallet = new Wallet("user123");
        Money negativeAmount = Money.fromReais("-50.00");

        assertThrows(IllegalArgumentException.class,
            () -> wallet.credit(negativeAmount, "Invalid credit", "TX123"));
    }

    @Test
    void shouldNotDebitNegativeAmount() {
        Wallet wallet = new Wallet("user123");
        Money negativeAmount = Money.fromReais("-50.00");

        assertThrows(IllegalArgumentException.class,
            () -> wallet.debit(negativeAmount, "Invalid debit", "TX123"));
    }

    @Test
    void shouldCalculateHistoricalBalance() {
        Wallet wallet = new Wallet("user123");
        LocalDateTime timestamp1 = LocalDateTime.now().minusHours(2);
        LocalDateTime timestamp2 = LocalDateTime.now().minusHours(1);

        wallet.credit(Money.fromReais("100.00"), "Credit 1", "TX1");
        wallet.credit(Money.fromReais("50.00"), "Credit 2", "TX2");
        wallet.debit(Money.fromReais("30.00"), "Debit 1", "TX3");

        LocalDateTime timestamp3 = LocalDateTime.now();
        Money balanceAtTimestamp = wallet.calculateBalanceAt(timestamp3);

        assertEquals(Money.fromReais("120.00"), balanceAtTimestamp);
    }
}