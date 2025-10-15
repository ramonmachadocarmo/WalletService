package com.finaya.pixwallet.domain.service;

import com.finaya.pixwallet.domain.entity.IdempotencyRecord;
import com.finaya.pixwallet.domain.repository.IdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRepository idempotencyRepository;


    private final ConcurrentHashMap<String, LockEntry> keyLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry> processingCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 5000;
    private static final int MAX_LOCKS = 1000;
    private final AtomicBoolean cleanupInProgress = new AtomicBoolean(false);

    public IdempotencyService(IdempotencyRepository idempotencyRepository) {
        this.idempotencyRepository = idempotencyRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Optional<IdempotencyRecord> findExistingRecord(String scope, String idempotencyKey) {
        String cacheKey = buildCacheKey(scope, idempotencyKey);

        logger.debug("Looking for existing idempotency record - scope: [REDACTED], key: [REDACTED]");

        CacheEntry cachedEntry = processingCache.get(cacheKey);
        if (cachedEntry != null && !cachedEntry.isExpired()) {
            IdempotencyRecord record = cachedEntry.getRecord();
            if (!record.isExpired()) {
                logger.debug("Found valid cached idempotency record");
                return Optional.of(record);
            } else {
                processingCache.remove(cacheKey, cachedEntry);
            }
        }
        
        // Check size limits
        if (processingCache.size() > MAX_CACHE_SIZE) {
            performCacheCleanup();
        }

        Optional<IdempotencyRecord> record = idempotencyRepository.findByScopeAndIdempotencyKey(scope, idempotencyKey);

        if (record.isPresent()) {
            if (record.get().isExpired()) {
                logger.debug("Found expired idempotency record - scope: {}, key: {}", scope, idempotencyKey);
                return Optional.empty();
            }

            processingCache.put(cacheKey, new CacheEntry(record.get()));
            logger.debug("Found valid idempotency record - scope: {}, key: {}", scope, idempotencyKey);
        }

        return record;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    public IdempotencyRecord saveRecordAtomically(String scope, String idempotencyKey, String requestBody, String responseBody, Integer responseStatus) {
        String cacheKey = buildCacheKey(scope, idempotencyKey);
        LockEntry lockEntry = keyLocks.computeIfAbsent(cacheKey, k -> new LockEntry());
        ReentrantLock lock = lockEntry.getLock();
        
        if (keyLocks.size() > MAX_LOCKS) {
            performLockCleanup();
        }

        try {
            if (!lock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new RuntimeException("Failed to acquire idempotency lock within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for idempotency lock", e);
        }
        try {
            logger.debug("Saving idempotency record atomically - scope: {}, key: {}, status: {}", scope, idempotencyKey, responseStatus);

            Optional<IdempotencyRecord> existing = findExistingRecord(scope, idempotencyKey);
            if (existing.isPresent()) {
                logger.debug("Record already exists during atomic save - returning existing");
                return existing.get();
            }

            String requestHash = calculateHash(requestBody);
            IdempotencyRecord record = new IdempotencyRecord(scope, idempotencyKey, requestHash, responseBody, responseStatus);

            try {
                IdempotencyRecord savedRecord = idempotencyRepository.save(record);

                processingCache.put(cacheKey, new CacheEntry(savedRecord));

                logger.debug("Idempotency record saved successfully - scope: {}, key: {}", scope, idempotencyKey);
                return savedRecord;

            } catch (DataIntegrityViolationException e) {
                logger.debug("Race condition detected during save - fetching existing record");
                return idempotencyRepository.findByScopeAndIdempotencyKey(scope, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Record should exist after constraint violation"));
            }

        } finally {
            lock.unlock();
            lockEntry.updateLastAccess();
            if (!lock.hasQueuedThreads() && lockEntry.isExpired()) {
                keyLocks.remove(cacheKey, lockEntry);
            }
        }
    }

    public IdempotencyRecord saveRecord(String scope, String idempotencyKey, String requestBody, String responseBody, Integer responseStatus) {
        return saveRecordAtomically(scope, idempotencyKey, requestBody, responseBody, responseStatus);
    }

    public boolean isValidRequest(IdempotencyRecord existingRecord, String requestBody) {
        String requestHash = calculateHash(requestBody);
        boolean isValid = existingRecord.getRequestHash().equals(requestHash);

        if (!isValid) {
            logger.warn("Request hash mismatch for idempotency key - validation failed");
        }

        return isValid;
    }

    @Transactional
    public void cleanupExpiredRecords() {
        if (!cleanupInProgress.compareAndSet(false, true)) {
            logger.debug("Cleanup already in progress, skipping");
            return;
        }

        try {
            logger.info("Starting atomic cleanup of expired idempotency records");

            int deletedRecords = idempotencyRepository.deleteExpiredRecords(java.time.LocalDateTime.now());

            processingCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            keyLocks.entrySet().removeIf(entry -> {
                LockEntry lockEntry = entry.getValue();
                return lockEntry.isExpired() && !lockEntry.getLock().hasQueuedThreads();
            });

            logger.info("Cleanup completed - deleted {} expired records", deletedRecords);

        } finally {
            cleanupInProgress.set(false);
        }
    }

    public ProcessingStats getProcessingStats() {
        int cacheSize = processingCache.size();
        int lockCount = keyLocks.size();
        boolean cleanupActive = cleanupInProgress.get();

        return new ProcessingStats(cacheSize, lockCount, cleanupActive);
    }

    private String buildCacheKey(String scope, String idempotencyKey) {
        return scope + ":" + idempotencyKey;
    }

    private String calculateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Statistics for monitoring atomic operations
     */
    public static class ProcessingStats {
        private final int cacheSize;
        private final int lockCount;
        private final boolean cleanupInProgress;

        public ProcessingStats(int cacheSize, int lockCount, boolean cleanupInProgress) {
            this.cacheSize = cacheSize;
            this.lockCount = lockCount;
            this.cleanupInProgress = cleanupInProgress;
        }

        public int getCacheSize() { return cacheSize; }
        public int getLockCount() { return lockCount; }
        public boolean isCleanupInProgress() { return cleanupInProgress; }
    }
    
    private void performCacheCleanup() {
        processingCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        logger.debug("Cache cleanup completed - size: {}", processingCache.size());
    }
    
    private void performLockCleanup() {
        keyLocks.entrySet().removeIf(entry -> {
            LockEntry lockEntry = entry.getValue();
            return lockEntry.isExpired() && !lockEntry.getLock().hasQueuedThreads();
        });
        logger.debug("Lock cleanup completed - size: {}", keyLocks.size());
    }
    
    private static class CacheEntry {
        private final IdempotencyRecord record;
        private final LocalDateTime createdAt;
        
        public CacheEntry(IdempotencyRecord record) {
            this.record = record;
            this.createdAt = LocalDateTime.now();
        }
        
        public IdempotencyRecord getRecord() {
            return record;
        }
        
        public boolean isExpired() {
            return LocalDateTime.now().isAfter(createdAt.plusMinutes(30)); // 30 min TTL
        }
    }
    
    private static class LockEntry {
        private final ReentrantLock lock;
        private final LocalDateTime createdAt;
        private volatile LocalDateTime lastAccess;
        
        public LockEntry() {
            this.lock = new ReentrantLock();
            this.createdAt = LocalDateTime.now();
            this.lastAccess = LocalDateTime.now();
        }
        
        public ReentrantLock getLock() {
            updateLastAccess();
            return lock;
        }
        
        public void updateLastAccess() {
            this.lastAccess = LocalDateTime.now();
        }
        
        public boolean isExpired() {
            return LocalDateTime.now().isAfter(lastAccess.plusMinutes(10)); // 10 min TTL
        }
    }
}