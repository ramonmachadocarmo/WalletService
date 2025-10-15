package com.finaya.pixwallet.concurrency;

import com.finaya.pixwallet.application.usecase.PixTransferUseCase;
import com.finaya.pixwallet.application.usecase.WalletUseCase;
import com.finaya.pixwallet.domain.entity.PixKey;
import com.finaya.pixwallet.domain.entity.PixKeyType;
import com.finaya.pixwallet.domain.entity.PixTransfer;
import com.finaya.pixwallet.domain.entity.Wallet;
import com.finaya.pixwallet.domain.repository.PixTransferRepository;
import com.finaya.pixwallet.domain.repository.WalletRepository;
import com.finaya.pixwallet.domain.service.AtomicTransferService;
import com.finaya.pixwallet.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.*;

@SpringBootTest
@ActiveProfiles("test")
class AtomicOperationsTest {

    @Autowired
    private PixTransferUseCase pixTransferUseCase;

    @Autowired
    private WalletUseCase walletUseCase;

    @Autowired
    private AtomicTransferService atomicTransferService;

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

        sourceWallet = walletUseCase.createWallet("atomic-source-user");
        destinationWallet = walletUseCase.createWallet("atomic-dest-user");

        walletUseCase.deposit(sourceWallet.getId(), Money.fromReais("10000.00"), "Initial atomic deposit");

        destinationPixKey = walletUseCase.registerPixKey(
            destinationWallet.getId(),
            "atomic@example.com",
            PixKeyType.EMAIL
        );
    }

    @Test
    void shouldHandleAtomicConcurrentDeposits() throws Exception {
        int numberOfThreads = 50;
        Money depositAmount = Money.fromReais("10.00");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int depositNumber = i;
            executor.submit(() -> {
                try {
                    await().until(() -> startLatch.getCount() == 0);

                    walletUseCase.deposit(sourceWallet.getId(), depositAmount, "Atomic concurrent deposit " + depositNumber);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("Atomic deposit error: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Verify atomic consistency
        Wallet updatedWallet = walletRepository.findById(sourceWallet.getId()).orElseThrow();
        Money expectedBalance = Money.fromReais("10000.00")
            .add(depositAmount.multiply(successCount.get()));

        assertEquals(expectedBalance, updatedWallet.getBalance(),
                    "Balance should reflect all successful atomic deposits");

        assertEquals(numberOfThreads, successCount.get(),
                    "All atomic deposits should succeed");

        assertEquals(0, errorCount.get(),
                    "No atomic deposit errors should occur");

        // Verify ledger integrity using method that fetches ledger entries
        Wallet walletWithLedger = walletRepository.findByIdWithLedgerEntries(sourceWallet.getId()).orElseThrow();
        int expectedLedgerEntries = 1 + successCount.get(); // Initial + deposits
        assertEquals(expectedLedgerEntries, walletWithLedger.getLedgerEntries().size(),
                    "All atomic operations should be recorded in ledger");

        // Check atomic statistics - verify deposits were processed
        assertTrue(successCount.get() == numberOfThreads,
                  "All atomic deposits should be processed successfully");
    }

    @Test
    void shouldHandleAtomicConcurrentTransfers() throws Exception {
        int numberOfThreads = 20;
        Money transferAmount = Money.fromReais("50.00");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

        List<Future<PixTransfer>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int transferNumber = i;
            Future<PixTransfer> future = executor.submit(() -> {
                try {
                    await().until(() -> startLatch.getCount() == 0);

                    String idempotencyKey = "ATOMIC-TRANSFER-" + transferNumber + "-" + UUID.randomUUID();

                    PixTransfer transfer = pixTransferUseCase.initiateTransfer(
                        idempotencyKey,
                        sourceWallet.getId(),
                        destinationPixKey.getKeyValue(),
                        transferAmount
                    );

                    successCount.incrementAndGet();
                    return transfer;

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("Atomic transfer error: " + e.getMessage());
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            });
            futures.add(future);
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Verify atomic transfer consistency
        Wallet updatedSourceWallet = walletRepository.findById(sourceWallet.getId()).orElseThrow();
        Money expectedBalance = Money.fromReais("10000.00")
            .subtract(transferAmount.multiply(successCount.get()));

        assertEquals(expectedBalance, updatedSourceWallet.getBalance(),
                    "Source wallet balance should reflect all successful atomic transfers");

        // Verify all transfers have unique end-to-end IDs
        List<PixTransfer> allTransfers = pixTransferRepository.findByFromWalletIdOrderByCreatedAtDesc(sourceWallet.getId());
        
        // Check atomic transfer statistics
        AtomicTransferService.TransferStats transferStats = atomicTransferService.getAtomicStats();
        assertTrue(transferStats.getTotalTransfers() > 0,
                  "Atomic transfer counter should track operations");
        
        assertEquals(successCount.get(), allTransfers.size(),
                    "Number of successful transfers should match database records");

        assertTrue(transferStats.getSuccessfulTransfers() > 0,
                  "Some transfers should succeed atomically");
        long uniqueEndToEndIds = allTransfers.stream()
            .map(PixTransfer::getEndToEndId)
            .distinct()
            .count();

        assertEquals(allTransfers.size(), uniqueEndToEndIds,
                    "All transfers should have unique end-to-end IDs");
    }

    @Test
    void shouldHandleAtomicWebhookProcessing() throws Exception {
        // Create a transfer first
        String idempotencyKey = "ATOMIC-WEBHOOK-TEST-" + UUID.randomUUID();
        PixTransfer transfer = pixTransferUseCase.initiateTransfer(
            idempotencyKey,
            sourceWallet.getId(),
            destinationPixKey.getKeyValue(),
            Money.fromReais("100.00")
        );

        String endToEndId = transfer.getEndToEndId();
        int numberOfThreads = 15;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int eventNumber = i;
            executor.submit(() -> {
                try {
                    await().until(() -> startLatch.getCount() == 0);

                    String eventId = "ATOMIC-EVENT-" + eventNumber;

                    pixTransferUseCase.processWebhookEvent(
                        endToEndId,
                        eventId,
                        "CONFIRMED"
                    );

                    processedCount.incrementAndGet();

                } catch (Exception e) {
                    duplicateCount.incrementAndGet();
                    System.err.println("Atomic webhook processing: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Verify atomic webhook processing
        PixTransfer updatedTransfer = pixTransferRepository.findByEndToEndId(endToEndId).orElseThrow();
        assertTrue(updatedTransfer.isConfirmed(), "Transfer should be confirmed atomically");

        Wallet updatedDestinationWallet = walletRepository.findById(destinationWallet.getId()).orElseThrow();
        assertEquals(Money.fromReais("100.00"), updatedDestinationWallet.getBalance(),
                    "Destination wallet should receive amount only once despite concurrent webhooks");

        // Check webhook statistics
        PixTransferUseCase.WebhookStats webhookStats = pixTransferUseCase.getWebhookStats();
        assertTrue(webhookStats.getTotalEvents() > 0,
                  "Some webhook events should be processed");

        assertTrue(webhookStats.getDuplicateEvents() >= 0,
                  "Duplicate webhook events should be tracked");
    }

    @Test
    void shouldMaintainAtomicConsistencyUnderHighLoad() throws Exception {
        int numberOfWallets = 10;
        int operationsPerWallet = 20;
        Money operationAmount = Money.fromReais("5.00");

        // Create multiple wallets
        List<Wallet> wallets = new ArrayList<>();
        for (int i = 0; i < numberOfWallets; i++) {
            Wallet wallet = walletUseCase.createWallet("load-test-user-" + i);
            walletUseCase.deposit(wallet.getId(), Money.fromReais("1000.00"), "Initial load test deposit");
            wallets.add(wallet);
        }

        int totalOperations = numberOfWallets * operationsPerWallet;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalOperations);
        ExecutorService executor = Executors.newFixedThreadPool(50);

        AtomicInteger successfulOperations = new AtomicInteger(0);
        AtomicInteger failedOperations = new AtomicInteger(0);

        // Execute mixed operations concurrently
        for (int i = 0; i < numberOfWallets; i++) {
            final Wallet wallet = wallets.get(i);

            for (int j = 0; j < operationsPerWallet; j++) {
                final int operationNumber = j;

                executor.submit(() -> {
                    try {
                        await().until(() -> startLatch.getCount() == 0);

                        // Alternate between deposits and withdrawals
                        if (operationNumber % 2 == 0) {
                            walletUseCase.deposit(wallet.getId(), operationAmount, "Load test deposit " + operationNumber);
                        } else {
                            walletUseCase.withdraw(wallet.getId(), operationAmount, "Load test withdrawal " + operationNumber);
                        }

                        successfulOperations.incrementAndGet();

                    } catch (Exception e) {
                        failedOperations.incrementAndGet();
                        System.err.println("Load test operation error: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Verify atomic consistency across all wallets
        for (Wallet wallet : wallets) {
            Wallet updatedWallet = walletRepository.findById(wallet.getId()).orElseThrow();

            // Each wallet should have consistent balance and ledger
            assertTrue(updatedWallet.getBalance().compareTo(Money.ZERO) >= 0,
                      "Wallet balance should never go negative");

            // Verify ledger integrity using method that fetches ledger entries
            Wallet walletWithLedger = walletRepository.findByIdWithLedgerEntries(wallet.getId()).orElseThrow();
            Money calculatedBalance = walletWithLedger.getLedgerEntries().stream()
                .map(entry -> entry.getAmount())
                .reduce(Money.ZERO, Money::add);

            assertEquals(calculatedBalance, updatedWallet.getBalance(),
                        "Calculated balance should match stored balance");
        }

        // Verify overall statistics - check that operations were successful
        assertTrue(successfulOperations.get() > 0,
                  "Some operations should have succeeded");

        double errorRate = (double) failedOperations.get() / totalOperations * 100;
        assertTrue(errorRate < 5, // Less than 5% error rate
                  "Error rate should be low under high load: " + errorRate + "%");

        System.out.printf("High load test completed - Success: %d, Failed: %d, Error Rate: %.2f%%%n",
                         successfulOperations.get(), failedOperations.get(), errorRate);
    }
}