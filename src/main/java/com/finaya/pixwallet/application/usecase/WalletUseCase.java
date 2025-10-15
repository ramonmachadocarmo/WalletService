package com.finaya.pixwallet.application.usecase;

import com.finaya.pixwallet.domain.entity.PixKey;
import com.finaya.pixwallet.domain.entity.PixKeyType;
import com.finaya.pixwallet.domain.entity.Wallet;
import com.finaya.pixwallet.domain.repository.PixKeyRepository;
import com.finaya.pixwallet.domain.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.finaya.pixwallet.domain.valueobject.Money;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class WalletUseCase {

    private static final Logger logger = LoggerFactory.getLogger(WalletUseCase.class);

    private final WalletRepository walletRepository;
    private final PixKeyRepository pixKeyRepository;

    private final AtomicLong walletsCreated = new AtomicLong(0);
    private final AtomicLong depositsProcessed = new AtomicLong(0);
    private final AtomicLong withdrawalsProcessed = new AtomicLong(0);
    private final AtomicLong pixKeysRegistered = new AtomicLong(0);

    private final ConcurrentHashMap<UUID, ReentrantReadWriteLock> walletOperationLocks = new ConcurrentHashMap<>();

    public WalletUseCase(WalletRepository walletRepository, PixKeyRepository pixKeyRepository) {
        this.walletRepository = walletRepository;
        this.pixKeyRepository = pixKeyRepository;
    }

    @Transactional
    public Wallet createWallet(String userId) {
        long walletNumber = walletsCreated.incrementAndGet();
        logger.info("Creating wallet #{} for user: {}", walletNumber, userId);

        if (walletRepository.existsByUserId(userId)) {
            throw new IllegalArgumentException("Wallet already exists for user: " + userId);
        }

        Wallet wallet = new Wallet(userId);
        Wallet savedWallet = walletRepository.save(wallet);

        logger.info("Wallet #{} created successfully - ID: {}, User: {}", walletNumber, savedWallet.getId(), userId);
        return savedWallet;
    }

    @Transactional
    public PixKey registerPixKey(UUID walletId, String keyValue, PixKeyType keyType) {
        long pixKeyNumber = pixKeysRegistered.incrementAndGet();
        logger.info("Registering Pix key #{} for wallet: {} - Key: {}, Type: {}", pixKeyNumber, walletId, keyValue, keyType);

        if (pixKeyRepository.existsByKeyValueAndKeyTypeAndIsActive(keyValue, keyType, true)) {
            throw new IllegalArgumentException("Pix key already exists and is active: " + keyValue);
        }

        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        PixKey pixKey = new PixKey(keyValue, keyType);
        wallet.addPixKey(pixKey);

        PixKey savedPixKey = pixKeyRepository.save(pixKey);
        logger.info("Pix key #{} registered successfully - ID: {}, Key: {}", pixKeyNumber, savedPixKey.getId(), keyValue);

        return savedPixKey;
    }

    @Transactional(readOnly = true)
    public Money getBalance(UUID walletId) {
        logger.debug("Getting balance for wallet: {}", walletId);

        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        return wallet.getBalance();
    }

    @Transactional(readOnly = true)
    public Money getHistoricalBalance(UUID walletId, LocalDateTime timestamp) {
        logger.debug("Getting historical balance for wallet: {} at timestamp: {}", walletId, timestamp);

        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        return wallet.calculateBalanceAt(timestamp);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    @Retryable(value = {Exception.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public void deposit(UUID walletId, Money amount, String description) {
        long depositNumber = depositsProcessed.incrementAndGet();
        logger.info("Processing atomic deposit #{} - Wallet: {}, Amount: {}, Description: {}",
                   depositNumber, walletId, amount, description);

        ReentrantReadWriteLock operationLock = walletOperationLocks.computeIfAbsent(walletId, k -> new ReentrantReadWriteLock());

        try {
            if (!operationLock.writeLock().tryLock(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new RuntimeException("Failed to acquire wallet operation lock within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for wallet lock", e);
        }
        
        try {
            Wallet wallet = walletRepository.findByIdWithLock(walletId)
                    .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

            Money previousBalance = wallet.getBalance();
            String transactionId = "DEP-" + depositNumber + "-" + UUID.randomUUID().toString().substring(0, 8);

            wallet.credit(amount, description, transactionId);
            walletRepository.save(wallet);

            logger.info("Atomic deposit processed successfully - Deposit #{}, Wallet: {}, Amount: {}, Balance: {} -> {}",
                       depositNumber, walletId, amount, previousBalance, wallet.getBalance());

        } finally {
            operationLock.writeLock().unlock();

            if (!operationLock.hasQueuedThreads()) {
                walletOperationLocks.remove(walletId, operationLock);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    @Retryable(value = {Exception.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public void withdraw(UUID walletId, Money amount, String description) {
        long withdrawalNumber = withdrawalsProcessed.incrementAndGet();
        logger.info("Processing atomic withdrawal #{} - Wallet: {}, Amount: {}, Description: {}",
                   withdrawalNumber, walletId, amount, description);

        ReentrantReadWriteLock operationLock = walletOperationLocks.computeIfAbsent(walletId, k -> new ReentrantReadWriteLock());

        try {
            if (!operationLock.writeLock().tryLock(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new RuntimeException("Failed to acquire wallet operation lock within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for wallet lock", e);
        }
        
        try {
            Wallet wallet = walletRepository.findByIdWithLock(walletId)
                    .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

            Money previousBalance = wallet.getBalance();

            if (wallet.getBalance().isLessThan(amount)) {
                throw new IllegalArgumentException(
                    String.format("Insufficient funds - Available: %s, Required: %s", wallet.getBalance(), amount)
                );
            }

            String transactionId = "WDR-" + withdrawalNumber + "-" + UUID.randomUUID().toString().substring(0, 8);

            wallet.debit(amount, description, transactionId);
            walletRepository.save(wallet);

            logger.info("Atomic withdrawal processed successfully - Withdrawal #{}, Wallet: {}, Amount: {}, Balance: {} -> {}",
                       withdrawalNumber, walletId, amount, previousBalance, wallet.getBalance());

        } finally {
            operationLock.writeLock().unlock();

            if (!operationLock.hasQueuedThreads()) {
                walletOperationLocks.remove(walletId, operationLock);
            }
        }
    }

    @Transactional(readOnly = true)
    public Optional<Wallet> findByUserId(String userId) {
        return walletRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Optional<Wallet> findById(UUID walletId) {
        return walletRepository.findById(walletId);
    }

    @Transactional(readOnly = true)
    public Optional<PixKey> findPixKeyByValue(String keyValue) {
        return pixKeyRepository.findActiveByKeyValue(keyValue);
    }

    public WalletStats getAtomicStats() {
        return new WalletStats(
            walletsCreated.get(),
            depositsProcessed.get(),
            withdrawalsProcessed.get(),
            pixKeysRegistered.get(),
            walletOperationLocks.size()
        );
    }

    public void cleanupOperationLocks() {
        walletOperationLocks.entrySet().removeIf(entry -> {
            ReentrantReadWriteLock lock = entry.getValue();
            return !lock.hasQueuedThreads() && !lock.isWriteLocked() && lock.getReadLockCount() == 0;
        });

        logger.info("Cleaned up wallet operation locks - remaining: {}", walletOperationLocks.size());
    }

    public static class WalletStats {
        private final long walletsCreated;
        private final long depositsProcessed;
        private final long withdrawalsProcessed;
        private final long pixKeysRegistered;
        private final int activeLocks;

        public WalletStats(long walletsCreated, long depositsProcessed, long withdrawalsProcessed,
                          long pixKeysRegistered, int activeLocks) {
            this.walletsCreated = walletsCreated;
            this.depositsProcessed = depositsProcessed;
            this.withdrawalsProcessed = withdrawalsProcessed;
            this.pixKeysRegistered = pixKeysRegistered;
            this.activeLocks = activeLocks;
        }

        public long getWalletsCreated() { return walletsCreated; }
        public long getDepositsProcessed() { return depositsProcessed; }
        public long getWithdrawalsProcessed() { return withdrawalsProcessed; }
        public long getPixKeysRegistered() { return pixKeysRegistered; }
        public int getActiveLocks() { return activeLocks; }
        public long getTotalOperations() { return depositsProcessed + withdrawalsProcessed; }
    }
}