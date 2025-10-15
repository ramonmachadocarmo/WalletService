package com.finaya.pixwallet.domain.service;

import com.finaya.pixwallet.domain.entity.PixTransfer;
import com.finaya.pixwallet.domain.entity.PixTransferStatus;
import com.finaya.pixwallet.domain.entity.Wallet;
import com.finaya.pixwallet.domain.repository.PixTransferRepository;
import com.finaya.pixwallet.domain.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.finaya.pixwallet.domain.valueobject.Money;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;

@Service
public class AtomicTransferService {

    private static final Logger logger = LoggerFactory.getLogger(AtomicTransferService.class);

    private final PixTransferRepository pixTransferRepository;
    private final WalletRepository walletRepository;

    private final AtomicLong transferCounter = new AtomicLong(0);
    private final AtomicLong successfulTransfers = new AtomicLong(0);
    private final AtomicLong failedTransfers = new AtomicLong(0);
    private final AtomicInteger activeTransfers = new AtomicInteger(0);

    private final ConcurrentHashMap<String, TransferStateEntry> transferStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, LockEntry> walletLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AtomicTransferService-Cleanup");
        t.setDaemon(true);
        return t;
    });

    private static final long TRANSFER_STATE_TTL_MINUTES = 60;
    private static final long WALLET_LOCK_TTL_MINUTES = 5;
    private static final int MAX_TRANSFER_STATES = 10000;
    private static final int MAX_WALLET_LOCKS = 1000;

    public AtomicTransferService(PixTransferRepository pixTransferRepository, WalletRepository walletRepository) {
        this.pixTransferRepository = pixTransferRepository;
        this.walletRepository = walletRepository;
    }

    @PostConstruct
    public void startCleanupScheduler() {
        // Schedule cleanup every 15 minutes
        cleanupExecutor.scheduleAtFixedRate(this::performAutomaticCleanup, 15, 15, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    public PixTransfer createTransferAtomically(String endToEndId, String idempotencyKey, UUID fromWalletId,
            String toPixKey, Money amount) {
        long transferId = transferCounter.incrementAndGet();
        activeTransfers.incrementAndGet();

        try {
            logger.info("Starting atomic transfer creation - ID: {}, EndToEnd: {}, From: {}, Amount: {}",
                    transferId, endToEndId, fromWalletId, amount);

            TransferStateEntry existingState = transferStates.get(endToEndId);
            if (existingState != null && !existingState.isExpired()) {
                logger.info("Transfer already exists - EndToEnd: {}, Status: {}", endToEndId,
                        existingState.getStatus());
                return pixTransferRepository.findByEndToEndId(endToEndId)
                        .orElseThrow(() -> new IllegalStateException("Transfer state exists but record not found"));
            }

            TransferStateEntry newState = new TransferStateEntry(PixTransferStatus.PENDING);
            TransferStateEntry existing = transferStates.putIfAbsent(endToEndId, newState);

            // Check size limit to prevent memory exhaustion
            if (transferStates.size() > MAX_TRANSFER_STATES) {
                performEmergencyCleanup();
            }

            if (existing != null) {
                logger.info("Concurrent transfer creation detected - EndToEnd: {}", endToEndId);
                return pixTransferRepository.findByEndToEndId(endToEndId)
                        .orElseThrow(() -> new IllegalStateException("Transfer state exists but record not found"));
            }

            try {
                boolean debitSuccess = debitWalletAtomically(fromWalletId, amount, endToEndId);

                if (!debitSuccess) {
                    transferStates.remove(endToEndId);
                    failedTransfers.incrementAndGet();
                    throw new IllegalArgumentException("Insufficient funds or wallet not found");
                }

                PixTransfer transfer = new PixTransfer(endToEndId, idempotencyKey, fromWalletId, toPixKey, amount);

                try {
                    PixTransfer savedTransfer = pixTransferRepository.save(transfer);
                    successfulTransfers.incrementAndGet();

                    logger.info("Atomic transfer created successfully - ID: {}, EndToEnd: {}",
                            transferId, endToEndId);

                    return savedTransfer;

                } catch (DataIntegrityViolationException e) {
                    logger.warn("Data integrity violation during transfer creation - refunding debit");
                    refundWalletAtomically(fromWalletId, amount, endToEndId + "-REFUND");
                    transferStates.remove(endToEndId);

                    return pixTransferRepository.findByEndToEndId(endToEndId)
                            .orElseGet(() -> pixTransferRepository.findByIdempotencyKey(idempotencyKey)
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Transfer should exist after constraint violation")));
                }

            } catch (Exception e) {
                transferStates.remove(endToEndId);
                throw e;
            }

        } finally {
            activeTransfers.decrementAndGet();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    public boolean updateTransferStateAtomically(String endToEndId, PixTransferStatus newStatus, String reason) {
        logger.info("Updating transfer state atomically - EndToEnd: {}, NewStatus: {}", endToEndId, newStatus);

        TransferStateEntry stateEntry = transferStates.get(endToEndId);
        if (stateEntry == null || stateEntry.isExpired()) {
            logger.warn("Transfer state not found or expired in memory - EndToEnd: {}", endToEndId);
            return false;
        }

        PixTransferStatus currentStatus = stateEntry.getStatus();

        if (!isValidTransition(currentStatus, newStatus)) {
            logger.warn("Invalid state transition - EndToEnd: {}, From: {}, To: {}",
                    endToEndId, currentStatus, newStatus);
            return false;
        }

        boolean updated = stateEntry.compareAndSetStatus(currentStatus, newStatus);

        if (updated) {
            PixTransfer transfer = pixTransferRepository.findByEndToEndIdWithLock(endToEndId)
                    .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + endToEndId));

            switch (newStatus) {
                case CONFIRMED:
                    transfer.confirm();
                    break;
                case REJECTED:
                    transfer.reject(reason != null ? reason : "Transfer rejected");
                    break;
                default:
                    logger.warn("Unsupported status transition to: {}", newStatus);
                    return false;
            }

            pixTransferRepository.save(transfer);

            logger.info("Transfer state updated successfully - EndToEnd: {}, Status: {}",
                    endToEndId, newStatus);
        }

        return updated;
    }

    private boolean debitWalletAtomically(UUID walletId, Money amount, String transactionId) {
        LockEntry lockEntry = walletLocks.computeIfAbsent(walletId, k -> new LockEntry());
        ReentrantReadWriteLock walletLock = lockEntry.getLock();

        // Check size limit to prevent memory exhaustion
        if (walletLocks.size() > MAX_WALLET_LOCKS) {
            performEmergencyCleanup();
        }

        try {
            if (!walletLock.writeLock().tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warn("Failed to acquire wallet lock within timeout - ID: {}", walletId);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        try {
            // Use database-level locking only, remove application-level nested lock
            Wallet wallet = walletRepository.findByIdWithLock(walletId).orElse(null);
            if (wallet == null) {
                logger.warn("Wallet not found for debit - ID: {}", walletId);
                return false;
            }

            if (wallet.getBalance().isLessThan(amount)) {
                logger.warn("Insufficient funds - Wallet: {}, Balance: {}, Required: {}",
                        walletId, wallet.getBalance(), amount);
                return false;
            }

            wallet.debit(amount, "Pix transfer - " + transactionId, transactionId);
            walletRepository.save(wallet);

            logger.debug("Wallet debited atomically - ID: {}, Amount: {}, NewBalance: {}",
                    walletId, amount, wallet.getBalance());

            return true;

        } finally {
            walletLock.writeLock().unlock();
            lockEntry.updateLastAccess();
            // Cleanup lock if no threads waiting and expired
            if (!walletLock.hasQueuedThreads() && !walletLock.isWriteLocked() &&
                    walletLock.getReadLockCount() == 0 && lockEntry.isExpired()) {
                walletLocks.remove(walletId, lockEntry);
            }
        }
    }

    private void creditWalletAtomically(UUID walletId, Money amount, String transactionId) {
        LockEntry lockEntry = walletLocks.computeIfAbsent(walletId, k -> new LockEntry());
        ReentrantReadWriteLock walletLock = lockEntry.getLock();

        try {
            if (!walletLock.writeLock().tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new RuntimeException("Failed to acquire wallet lock within timeout - ID: " + walletId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for wallet lock", e);
        }

        try {
            Wallet wallet = walletRepository.findByIdWithLock(walletId)
                    .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

            wallet.credit(amount, "Pix credit - " + transactionId, transactionId);
            walletRepository.save(wallet);

            logger.debug("Wallet credited atomically - ID: {}, Amount: {}, NewBalance: {}",
                    walletId, amount, wallet.getBalance());

        } finally {
            walletLock.writeLock().unlock();
            lockEntry.updateLastAccess();
            if (!walletLock.hasQueuedThreads() && !walletLock.isWriteLocked() &&
                    walletLock.getReadLockCount() == 0 && lockEntry.isExpired()) {
                walletLocks.remove(walletId, lockEntry);
            }
        }
    }

    private void refundWalletAtomically(UUID walletId, Money amount, String transactionId) {
        creditWalletAtomically(walletId, amount, transactionId);
        logger.info("Refund processed atomically - Wallet: {}, Amount: {}", walletId, amount);
    }

    public void creditDestinationWalletAtomically(UUID destinationWalletId, Money amount, String transactionId) {
        creditWalletAtomically(destinationWalletId, amount, transactionId);
        logger.info("Destination wallet credited atomically - Wallet: {}, Amount: {}", destinationWalletId, amount);
    }

    public void refundSourceWalletAtomically(UUID sourceWalletId, Money amount, String transactionId) {
        refundWalletAtomically(sourceWalletId, amount, transactionId);
        logger.info("Source wallet refunded atomically - Wallet: {}, Amount: {}", sourceWalletId, amount);
    }

    private boolean isValidTransition(PixTransferStatus from, PixTransferStatus to) {
        if (from == null || to == null) {
            return false;
        }

        return switch (from) {
            case PENDING -> to == PixTransferStatus.CONFIRMED || to == PixTransferStatus.REJECTED;
            case CONFIRMED, REJECTED -> false; // Terminal states
        };
    }

    public TransferStats getAtomicStats() {
        return new TransferStats(
                transferCounter.get(),
                successfulTransfers.get(),
                failedTransfers.get(),
                activeTransfers.get(),
                transferStates.size(),
                walletLocks.size());
    }

    public void cleanupCompletedTransfers() {
        performAutomaticCleanup();
    }

    private void performAutomaticCleanup() {
        int initialStatesSize = transferStates.size();
        int initialLocksSize = walletLocks.size();

        // Cleanup expired transfer states
        transferStates.entrySet().removeIf(entry -> {
            TransferStateEntry stateEntry = entry.getValue();
            return stateEntry.isExpired() || stateEntry.isTerminal();
        });

        // Cleanup expired wallet locks
        walletLocks.entrySet().removeIf(entry -> {
            LockEntry lockEntry = entry.getValue();
            ReentrantReadWriteLock lock = lockEntry.getLock();
            return lockEntry.isExpired() && !lock.hasQueuedThreads() &&
                    !lock.isWriteLocked() && lock.getReadLockCount() == 0;
        });

        int removedStates = initialStatesSize - transferStates.size();
        int removedLocks = initialLocksSize - walletLocks.size();

        logger.info("Automatic cleanup completed - removed {} transfer states, {} wallet locks",
                removedStates, removedLocks);
    }

    private void performEmergencyCleanup() {
        logger.warn("Performing emergency cleanup due to memory limits");

        // Force cleanup of oldest entries
        if (transferStates.size() > MAX_TRANSFER_STATES) {
            transferStates.entrySet().removeIf(entry -> entry.getValue().isOlderThan(30)); // 30 minutes
        }

        if (walletLocks.size() > MAX_WALLET_LOCKS) {
            walletLocks.entrySet().removeIf(entry -> entry.getValue().isOlderThan(5)); // 5 minutes
        }
    }

    public static class TransferStats {
        private final long totalTransfers;
        private final long successfulTransfers;
        private final long failedTransfers;
        private final int activeTransfers;
        private final int statesInMemory;
        private final int walletLocks;

        public TransferStats(long totalTransfers, long successfulTransfers, long failedTransfers,
                int activeTransfers, int statesInMemory, int walletLocks) {
            this.totalTransfers = totalTransfers;
            this.successfulTransfers = successfulTransfers;
            this.failedTransfers = failedTransfers;
            this.activeTransfers = activeTransfers;
            this.statesInMemory = statesInMemory;
            this.walletLocks = walletLocks;
        }

        public long getTotalTransfers() {
            return totalTransfers;
        }

        public long getSuccessfulTransfers() {
            return successfulTransfers;
        }

        public long getFailedTransfers() {
            return failedTransfers;
        }

        public int getActiveTransfers() {
            return activeTransfers;
        }

        public int getStatesInMemory() {
            return statesInMemory;
        }

        public int getWalletLocks() {
            return walletLocks;
        }

        public double getSuccessRate() {
            return totalTransfers > 0 ? (double) successfulTransfers / totalTransfers * 100 : 0;
        }
    }

    // TTL-based wrapper classes to prevent memory leaks
    private static class TransferStateEntry {
        private final AtomicReference<PixTransferStatus> status;
        private final LocalDateTime createdAt;
        private volatile LocalDateTime lastAccess;

        public TransferStateEntry(PixTransferStatus initialStatus) {
            this.status = new AtomicReference<>(initialStatus);
            this.createdAt = LocalDateTime.now();
            this.lastAccess = LocalDateTime.now();
        }

        public PixTransferStatus getStatus() {
            updateLastAccess();
            return status.get();
        }

        public boolean compareAndSetStatus(PixTransferStatus expected, PixTransferStatus update) {
            updateLastAccess();
            return status.compareAndSet(expected, update);
        }

        public void updateLastAccess() {
            this.lastAccess = LocalDateTime.now();
        }

        public boolean isExpired() {
            return LocalDateTime.now().isAfter(createdAt.plusMinutes(TRANSFER_STATE_TTL_MINUTES));
        }

        public boolean isOlderThan(long minutes) {
            return LocalDateTime.now().isAfter(createdAt.plusMinutes(minutes));
        }

        public boolean isTerminal() {
            PixTransferStatus currentStatus = status.get();
            return currentStatus == PixTransferStatus.CONFIRMED || currentStatus == PixTransferStatus.REJECTED;
        }
    }

    private static class LockEntry {
        private final ReentrantReadWriteLock lock;
        private final LocalDateTime createdAt;
        private volatile LocalDateTime lastAccess;

        public LockEntry() {
            this.lock = new ReentrantReadWriteLock();
            this.createdAt = LocalDateTime.now();
            this.lastAccess = LocalDateTime.now();
        }

        public ReentrantReadWriteLock getLock() {
            updateLastAccess();
            return lock;
        }

        public void updateLastAccess() {
            this.lastAccess = LocalDateTime.now();
        }

        public boolean isExpired() {
            return LocalDateTime.now().isAfter(lastAccess.plusMinutes(WALLET_LOCK_TTL_MINUTES));
        }

        public boolean isOlderThan(long minutes) {
            return LocalDateTime.now().isAfter(createdAt.plusMinutes(minutes));
        }
    }
}