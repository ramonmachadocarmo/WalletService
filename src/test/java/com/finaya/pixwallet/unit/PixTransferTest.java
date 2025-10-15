package com.finaya.pixwallet.unit;

import com.finaya.pixwallet.domain.entity.PixTransfer;
import com.finaya.pixwallet.domain.entity.PixTransferStatus;
import com.finaya.pixwallet.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PixTransferTest {

    @Test
    void shouldCreatePixTransferWithPendingStatus() {
        String endToEndId = "E123456789";
        String idempotencyKey = "idmp-123";
        UUID fromWalletId = UUID.randomUUID();
        String toPixKey = "user@example.com";
        Money amount = Money.fromReais("100.00");

        PixTransfer transfer = new PixTransfer(endToEndId, idempotencyKey, fromWalletId, toPixKey, amount);

        assertEquals(endToEndId, transfer.getEndToEndId());
        assertEquals(idempotencyKey, transfer.getIdempotencyKey());
        assertEquals(fromWalletId, transfer.getFromWalletId());
        assertEquals(toPixKey, transfer.getToPixKey());
        assertEquals(amount, transfer.getAmount());
        assertEquals(PixTransferStatus.PENDING, transfer.getStatus());
        assertTrue(transfer.isPending());
        assertFalse(transfer.isConfirmed());
        assertFalse(transfer.isRejected());
        assertNotNull(transfer.getCreatedAt());
        assertNull(transfer.getConfirmedAt());
        assertNull(transfer.getRejectedAt());
    }

    @Test
    void shouldNotCreateTransferWithNullEndToEndId() {
        assertThrows(NullPointerException.class, () ->
            new PixTransfer(null, "idmp-123", UUID.randomUUID(), "user@example.com", Money.fromReais("100.00"))
        );
    }

    @Test
    void shouldNotCreateTransferWithNullIdempotencyKey() {
        assertThrows(NullPointerException.class, () ->
            new PixTransfer("E123", null, UUID.randomUUID(), "user@example.com", Money.fromReais("100.00"))
        );
    }

    @Test
    void shouldNotCreateTransferWithZeroAmount() {
        assertThrows(IllegalArgumentException.class, () ->
            new PixTransfer("E123", "idmp-123", UUID.randomUUID(), "user@example.com", Money.ZERO)
        );
    }

    @Test
    void shouldNotCreateTransferWithNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () ->
            new PixTransfer("E123", "idmp-123", UUID.randomUUID(), "user@example.com", Money.fromReais("-10.00"))
        );
    }

    @Test
    void shouldConfirmPendingTransfer() {
        PixTransfer transfer = new PixTransfer("E123", "idmp-123", UUID.randomUUID(), "user@example.com", Money.fromReais("100.00"));

        transfer.confirm();

        assertEquals(PixTransferStatus.CONFIRMED, transfer.getStatus());
        assertTrue(transfer.isConfirmed());
        assertFalse(transfer.isPending());
        assertFalse(transfer.isRejected());
        assertNotNull(transfer.getConfirmedAt());
        assertNull(transfer.getRejectedAt());
    }

    @Test
    void shouldRejectPendingTransfer() {
        PixTransfer transfer = new PixTransfer("E123", "idmp-123", UUID.randomUUID(), "user@example.com", Money.fromReais("100.00"));
        String rejectionReason = "Insufficient funds";

        transfer.reject(rejectionReason);

        assertEquals(PixTransferStatus.REJECTED, transfer.getStatus());
        assertTrue(transfer.isRejected());
        assertFalse(transfer.isPending());
        assertFalse(transfer.isConfirmed());
        assertNotNull(transfer.getRejectedAt());
        assertEquals(rejectionReason, transfer.getRejectionReason());
        assertNull(transfer.getConfirmedAt());
    }

    @Test
    void shouldNotConfirmNonPendingTransfer() {
        PixTransfer transfer = new PixTransfer("E123", "idmp-123", UUID.randomUUID(), "user@example.com", Money.fromReais("100.00"));
        transfer.reject("Test rejection");

        assertThrows(IllegalStateException.class, transfer::confirm);
    }

    @Test
    void shouldNotRejectNonPendingTransfer() {
        PixTransfer transfer = new PixTransfer("E123", "idmp-123", UUID.randomUUID(), "user@example.com", Money.fromReais("100.00"));
        transfer.confirm();

        assertThrows(IllegalStateException.class, () -> transfer.reject("Test rejection"));
    }
}