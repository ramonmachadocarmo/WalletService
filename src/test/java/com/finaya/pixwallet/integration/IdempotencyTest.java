package com.finaya.pixwallet.integration;

import com.finaya.pixwallet.application.usecase.PixTransferUseCase;
import com.finaya.pixwallet.application.usecase.WalletUseCase;
import com.finaya.pixwallet.domain.entity.PixKey;
import com.finaya.pixwallet.domain.entity.PixKeyType;
import com.finaya.pixwallet.domain.entity.PixTransfer;
import com.finaya.pixwallet.domain.entity.Wallet;
import com.finaya.pixwallet.domain.repository.PixTransferRepository;
import com.finaya.pixwallet.domain.repository.WalletRepository;
import com.finaya.pixwallet.domain.service.IdempotencyService;
import com.finaya.pixwallet.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class IdempotencyTest {

    @Autowired
    private PixTransferUseCase pixTransferUseCase;

    @Autowired
    private WalletUseCase walletUseCase;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PixTransferRepository pixTransferRepository;

    private Wallet sourceWallet;
    private Wallet destinationWallet;
    private PixKey destinationPixKey;

    @BeforeEach
    @Transactional
    void setUp() {
        pixTransferRepository.deleteAll();
        walletRepository.deleteAll();

        sourceWallet = walletUseCase.createWallet("source-user");
        destinationWallet = walletUseCase.createWallet("dest-user");

        walletUseCase.deposit(sourceWallet.getId(), Money.fromReais("1000.00"), "Initial deposit");

        destinationPixKey = walletUseCase.registerPixKey(
            destinationWallet.getId(),
            "dest@example.com",
            PixKeyType.EMAIL
        );
    }

    @Test
    void shouldReturnSameTransferForSameIdempotencyKey() {
        String idempotencyKey = "IDEMPOTENCY-TEST-" + UUID.randomUUID();
        Money transferAmount = Money.fromReais("100.00");

        PixTransfer firstTransfer = pixTransferUseCase.initiateTransfer(
            idempotencyKey,
            sourceWallet.getId(),
            destinationPixKey.getKeyValue(),
            transferAmount
        );

        PixTransfer secondTransfer = pixTransferUseCase.initiateTransfer(
            idempotencyKey,
            sourceWallet.getId(),
            destinationPixKey.getKeyValue(),
            transferAmount
        );

        assertEquals(firstTransfer.getId(), secondTransfer.getId());
        assertEquals(firstTransfer.getEndToEndId(), secondTransfer.getEndToEndId());

        List<PixTransfer> allTransfers = pixTransferRepository.findByFromWalletIdOrderByCreatedAtDesc(sourceWallet.getId());
        assertEquals(1, allTransfers.size(), "Only one transfer should exist");

        Wallet updatedSourceWallet = walletRepository.findById(sourceWallet.getId()).orElseThrow();
        assertEquals(Money.fromReais("900.00"), updatedSourceWallet.getBalance(), "Balance should be debited only once");
    }

    @Test
    void shouldProcessWebhookEventsIdempotently() {
        PixTransfer transfer = pixTransferUseCase.initiateTransfer(
            "WEBHOOK-IDEMPOTENCY-" + UUID.randomUUID(),
            sourceWallet.getId(),
            destinationPixKey.getKeyValue(),
            Money.fromReais("50.00")
        );

        String endToEndId = transfer.getEndToEndId();
        String eventId = "EVENT-" + UUID.randomUUID();

        pixTransferUseCase.processWebhookEvent(endToEndId, eventId, "CONFIRMED");

        Wallet destinationWalletAfterFirst = walletRepository.findById(destinationWallet.getId()).orElseThrow();
        Money balanceAfterFirst = destinationWalletAfterFirst.getBalance();

        pixTransferUseCase.processWebhookEvent(endToEndId, eventId, "CONFIRMED");

        Wallet destinationWalletAfterSecond = walletRepository.findById(destinationWallet.getId()).orElseThrow();
        Money balanceAfterSecond = destinationWalletAfterSecond.getBalance();

        assertEquals(balanceAfterFirst, balanceAfterSecond, "Balance should not change on duplicate webhook");
        assertEquals(Money.fromReais("50.00"), balanceAfterSecond, "Destination should receive amount only once");

        PixTransfer updatedTransfer = pixTransferRepository.findByEndToEndId(endToEndId).orElseThrow();
        assertTrue(updatedTransfer.isConfirmed(), "Transfer should be confirmed");
    }

    @Test
    void shouldHandleMultipleWebhookEventsForSameTransfer() {
        PixTransfer transfer = pixTransferUseCase.initiateTransfer(
            "MULTI-WEBHOOK-" + UUID.randomUUID(),
            sourceWallet.getId(),
            destinationPixKey.getKeyValue(),
            Money.fromReais("75.00")
        );

        String endToEndId = transfer.getEndToEndId();

        pixTransferUseCase.processWebhookEvent(endToEndId, "EVENT-1", "CONFIRMED");
        pixTransferUseCase.processWebhookEvent(endToEndId, "EVENT-2", "CONFIRMED");
        pixTransferUseCase.processWebhookEvent(endToEndId, "EVENT-3", "CONFIRMED");

        Wallet destinationWalletAfter = walletRepository.findById(destinationWallet.getId()).orElseThrow();
        assertEquals(Money.fromReais("75.00"), destinationWalletAfter.getBalance(),
                    "Destination should receive amount only once despite multiple events");

        PixTransfer updatedTransfer = pixTransferRepository.findByEndToEndId(endToEndId).orElseThrow();
        assertTrue(updatedTransfer.isConfirmed(), "Transfer should be confirmed");
    }

    @Test
    void shouldHandleOutOfOrderWebhookEvents() {
        PixTransfer transfer = pixTransferUseCase.initiateTransfer(
            "OUT-OF-ORDER-" + UUID.randomUUID(),
            sourceWallet.getId(),
            destinationPixKey.getKeyValue(),
            Money.fromReais("25.00")
        );

        String endToEndId = transfer.getEndToEndId();

        pixTransferUseCase.processWebhookEvent(endToEndId, "REJECTED-EVENT", "REJECTED");

        PixTransfer transferAfterRejection = pixTransferRepository.findByEndToEndId(endToEndId).orElseThrow();
        assertTrue(transferAfterRejection.isRejected(), "Transfer should be rejected");

        Wallet sourceWalletAfterRejection = walletRepository.findById(sourceWallet.getId()).orElseThrow();
        assertEquals(Money.fromReais("1000.00"), sourceWalletAfterRejection.getBalance(),
                    "Source wallet should be refunded");

        pixTransferUseCase.processWebhookEvent(endToEndId, "CONFIRMED-EVENT", "CONFIRMED");

        PixTransfer transferAfterConfirmed = pixTransferRepository.findByEndToEndId(endToEndId).orElseThrow();
        assertTrue(transferAfterConfirmed.isRejected(), "Transfer should remain rejected");

        Wallet destinationWalletFinal = walletRepository.findById(destinationWallet.getId()).orElseThrow();
        assertEquals(Money.ZERO, destinationWalletFinal.getBalance(),
                    "Destination should not receive funds for rejected transfer");
    }
}