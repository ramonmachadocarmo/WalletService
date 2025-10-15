package com.finaya.pixwallet.infrastructure.persistence;

import com.finaya.pixwallet.domain.entity.IdempotencyRecord;
import com.finaya.pixwallet.domain.repository.IdempotencyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IdempotencyRepositoryImpl extends SimpleJpaRepository<IdempotencyRecord, UUID> implements IdempotencyRepository {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyRepositoryImpl.class);

    @PersistenceContext
    private final EntityManager entityManager;

    public IdempotencyRepositoryImpl(EntityManager entityManager) {
        super(IdempotencyRecord.class, entityManager);
        this.entityManager = entityManager;
    }

    @Override
    public Optional<IdempotencyRecord> findByScopeAndIdempotencyKey(String scope, String idempotencyKey) {
        logger.debug("Finding idempotency record by scope: {} and key: {}", scope, idempotencyKey);

        try {
            IdempotencyRecord record = entityManager.createQuery(
                "SELECT ir FROM IdempotencyRecord ir WHERE ir.scope = :scope AND ir.idempotencyKey = :idempotencyKey",
                IdempotencyRecord.class)
                .setParameter("scope", scope)
                .setParameter("idempotencyKey", idempotencyKey)
                .getSingleResult();

            logger.debug("Found idempotency record: scope={}, key={}, expired={}",
                        scope, idempotencyKey, record.isExpired());
            return Optional.of(record);

        } catch (NoResultException e) {
            logger.debug("No idempotency record found for scope: {} and key: {}", scope, idempotencyKey);
            return Optional.empty();
        }
    }

    @Override
    public int deleteExpiredRecords(LocalDateTime now) {
        logger.info("Deleting expired idempotency records older than: {}", now);

        int deletedCount = entityManager.createQuery(
            "DELETE FROM IdempotencyRecord ir WHERE ir.expiresAt < :now")
            .setParameter("now", now)
            .executeUpdate();

        logger.info("Deleted {} expired idempotency records", deletedCount);
        return deletedCount;
    }

    @Override
    public <S extends IdempotencyRecord> S save(S entity) {
        logger.debug("Saving idempotency record: scope={}, key={}, status={}",
                    entity.getScope(), entity.getIdempotencyKey(), entity.getResponseStatus());

        S savedEntity = super.save(entity);

        logger.debug("Idempotency record saved successfully: scope={}, key={}",
                    savedEntity.getScope(), savedEntity.getIdempotencyKey());

        return savedEntity;
    }

    public List<IdempotencyRecord> findByScope(String scope) {
        logger.debug("Finding idempotency records by scope: {}", scope);

        List<IdempotencyRecord> records = entityManager.createQuery(
            "SELECT ir FROM IdempotencyRecord ir WHERE ir.scope = :scope ORDER BY ir.createdAt DESC",
            IdempotencyRecord.class)
            .setParameter("scope", scope)
            .getResultList();

        logger.debug("Found {} idempotency records for scope: {}", records.size(), scope);
        return records;
    }

    public List<IdempotencyRecord> findRecordsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        logger.debug("Finding idempotency records between {} and {}", startDate, endDate);

        List<IdempotencyRecord> records = entityManager.createQuery(
            "SELECT ir FROM IdempotencyRecord ir WHERE ir.createdAt BETWEEN :startDate AND :endDate ORDER BY ir.createdAt DESC",
            IdempotencyRecord.class)
            .setParameter("startDate", startDate)
            .setParameter("endDate", endDate)
            .getResultList();

        logger.debug("Found {} idempotency records between {} and {}", records.size(), startDate, endDate);
        return records;
    }

    public List<IdempotencyRecord> findByResponseStatus(Integer responseStatus) {
        logger.debug("Finding idempotency records by response status: {}", responseStatus);

        List<IdempotencyRecord> records = entityManager.createQuery(
            "SELECT ir FROM IdempotencyRecord ir WHERE ir.responseStatus = :responseStatus ORDER BY ir.createdAt DESC",
            IdempotencyRecord.class)
            .setParameter("responseStatus", responseStatus)
            .getResultList();

        logger.debug("Found {} idempotency records with response status: {}", records.size(), responseStatus);
        return records;
    }

    public IdempotencyStatistics getIdempotencyStatistics() {
        logger.debug("Calculating idempotency statistics");

        Long totalRecords = entityManager.createQuery(
            "SELECT COUNT(ir) FROM IdempotencyRecord ir", Long.class)
            .getSingleResult();

        Long activeRecords = entityManager.createQuery(
            "SELECT COUNT(ir) FROM IdempotencyRecord ir WHERE ir.expiresAt > :now", Long.class)
            .setParameter("now", LocalDateTime.now())
            .getSingleResult();

        List<Object[]> recordsByScope = entityManager.createQuery(
            "SELECT ir.scope, COUNT(ir) FROM IdempotencyRecord ir GROUP BY ir.scope")
            .getResultList();

        List<Object[]> recordsByStatus = entityManager.createQuery(
            "SELECT ir.responseStatus, COUNT(ir) FROM IdempotencyRecord ir GROUP BY ir.responseStatus")
            .getResultList();

        IdempotencyStatistics stats = new IdempotencyStatistics(
            totalRecords, activeRecords, recordsByScope, recordsByStatus);

        logger.debug("Idempotency statistics calculated: {}", stats);
        return stats;
    }

    public CleanupResult performDetailedCleanup() {
        logger.info("Starting detailed idempotency cleanup");

        LocalDateTime startTime = LocalDateTime.now();
        LocalDateTime cutoffTime = LocalDateTime.now();

        int deletedCount = deleteExpiredRecords(cutoffTime);

        long remainingCount = count() - deletedCount;

        LocalDateTime endTime = LocalDateTime.now();
        long durationMs = java.time.Duration.between(startTime, endTime).toMillis();

        CleanupResult result = new CleanupResult(
            startTime, endTime, durationMs, deletedCount, deletedCount, remainingCount);

        logger.info("Detailed cleanup completed: {}", result);
        return result;
    }

    public static class IdempotencyStatistics {
        private final long totalRecords;
        private final long activeRecords;
        private final java.util.Map<String, Long> recordsByScope;
        private final java.util.Map<Integer, Long> recordsByStatus;

        public IdempotencyStatistics(long totalRecords, long activeRecords,
                                   List<Object[]> scopeCounts, List<Object[]> statusCounts) {
            this.totalRecords = totalRecords;
            this.activeRecords = activeRecords;
            this.recordsByScope = new java.util.HashMap<>();
            this.recordsByStatus = new java.util.HashMap<>();

            for (Object[] row : scopeCounts) {
                String scope = (String) row[0];
                Long count = (Long) row[1];
                recordsByScope.put(scope, count);
            }

            for (Object[] row : statusCounts) {
                Integer status = (Integer) row[0];
                Long count = (Long) row[1];
                recordsByStatus.put(status, count);
            }
        }

        public long getTotalRecords() { return totalRecords; }
        public long getActiveRecords() { return activeRecords; }
        public long getExpiredRecords() { return totalRecords - activeRecords; }
        public java.util.Map<String, Long> getRecordsByScope() { return recordsByScope; }
        public java.util.Map<Integer, Long> getRecordsByStatus() { return recordsByStatus; }

        @Override
        public String toString() {
            return String.format("IdempotencyStatistics{totalRecords=%d, activeRecords=%d, expiredRecords=%d}",
                               totalRecords, activeRecords, getExpiredRecords());
        }
    }

    public static class CleanupResult {
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;
        private final long durationMs;
        private final long expiredCountBeforeCleanup;
        private final long deletedCount;
        private final long remainingCount;

        public CleanupResult(LocalDateTime startTime, LocalDateTime endTime, long durationMs,
                           long expiredCountBeforeCleanup, long deletedCount, long remainingCount) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.durationMs = durationMs;
            this.expiredCountBeforeCleanup = expiredCountBeforeCleanup;
            this.deletedCount = deletedCount;
            this.remainingCount = remainingCount;
        }

        public LocalDateTime getStartTime() { return startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public long getDurationMs() { return durationMs; }
        public long getExpiredCountBeforeCleanup() { return expiredCountBeforeCleanup; }
        public long getDeletedCount() { return deletedCount; }
        public long getRemainingCount() { return remainingCount; }

        @Override
        public String toString() {
            return String.format("CleanupResult{duration=%dms, expired=%d, deleted=%d, remaining=%d}",
                               durationMs, expiredCountBeforeCleanup, deletedCount, remainingCount);
        }
    }
}