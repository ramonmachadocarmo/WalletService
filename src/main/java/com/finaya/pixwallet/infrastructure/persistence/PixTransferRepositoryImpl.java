package com.finaya.pixwallet.infrastructure.persistence;

import com.finaya.pixwallet.domain.entity.PixTransfer;
import com.finaya.pixwallet.domain.entity.PixTransferStatus;
import com.finaya.pixwallet.domain.repository.PixTransferRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
public class PixTransferRepositoryImpl extends SimpleJpaRepository<PixTransfer, UUID> implements PixTransferRepository {

    private static final Logger logger = LoggerFactory.getLogger(PixTransferRepositoryImpl.class);

    @PersistenceContext
    private final EntityManager entityManager;

    public PixTransferRepositoryImpl(EntityManager entityManager) {
        super(PixTransfer.class, entityManager);
        this.entityManager = entityManager;
    }

    @Override
    public Optional<PixTransfer> findByEndToEndId(String endToEndId) {
        logger.debug("Finding transfer by endToEndId: {}", endToEndId);

        try {
            PixTransfer transfer = entityManager.createQuery(
                "SELECT pt FROM PixTransfer pt WHERE pt.endToEndId = :endToEndId", PixTransfer.class)
                .setParameter("endToEndId", endToEndId)
                .getSingleResult();

            logger.debug("Found transfer for endToEndId: {} - Status: {}", endToEndId, transfer.getStatus());
            return Optional.of(transfer);

        } catch (NoResultException e) {
            logger.debug("No transfer found for endToEndId: {}", endToEndId);
            return Optional.empty();
        }
    }

    @Override
    public Optional<PixTransfer> findByIdempotencyKey(String idempotencyKey) {
        logger.debug("Finding transfer by idempotencyKey: {}", idempotencyKey);

        try {
            PixTransfer transfer = entityManager.createQuery(
                "SELECT pt FROM PixTransfer pt WHERE pt.idempotencyKey = :idempotencyKey", PixTransfer.class)
                .setParameter("idempotencyKey", idempotencyKey)
                .getSingleResult();

            logger.debug("Found transfer for idempotencyKey: {} - EndToEnd: {}, Status: {}",
                        idempotencyKey, transfer.getEndToEndId(), transfer.getStatus());
            return Optional.of(transfer);

        } catch (NoResultException e) {
            logger.debug("No transfer found for idempotencyKey: {}", idempotencyKey);
            return Optional.empty();
        }
    }

    @Override
    public Optional<PixTransfer> findByEndToEndIdWithLock(String endToEndId) {
        logger.debug("Finding transfer by endToEndId with pessimistic lock: {}", endToEndId);

        try {
            PixTransfer transfer = entityManager.createQuery(
                "SELECT pt FROM PixTransfer pt WHERE pt.endToEndId = :endToEndId", PixTransfer.class)
                .setParameter("endToEndId", endToEndId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();

            logger.debug("Found and locked transfer: {} - Status: {}, Version: {}",
                        endToEndId, transfer.getStatus(), transfer.getVersion());
            return Optional.of(transfer);

        } catch (NoResultException e) {
            logger.debug("No transfer found for endToEndId: {}", endToEndId);
            return Optional.empty();
        }
    }

    @Override
    public List<PixTransfer> findByFromWalletIdOrderByCreatedAtDesc(UUID walletId) {
        logger.debug("Finding transfers by fromWalletId: {}", walletId);

        List<PixTransfer> transfers = entityManager.createQuery(
            "SELECT pt FROM PixTransfer pt WHERE pt.fromWalletId = :walletId ORDER BY pt.createdAt DESC", PixTransfer.class)
            .setParameter("walletId", walletId)
            .getResultList();

        logger.debug("Found {} transfers for wallet: {}", transfers.size(), walletId);
        return transfers;
    }

    @Override
    public List<PixTransfer> findByStatusOrderByCreatedAtAsc(PixTransferStatus status) {
        logger.debug("Finding transfers by status: {}", status);

        List<PixTransfer> transfers = entityManager.createQuery(
            "SELECT pt FROM PixTransfer pt WHERE pt.status = :status ORDER BY pt.createdAt ASC", PixTransfer.class)
            .setParameter("status", status)
            .getResultList();

        logger.debug("Found {} transfers with status: {}", transfers.size(), status);
        return transfers;
    }

    @Override
    public <S extends PixTransfer> S save(S entity) {
        logger.debug("Saving transfer: {} - Status: {}, Version: {}",
                    entity.getEndToEndId(), entity.getStatus(), entity.getVersion());

        S savedEntity = super.save(entity);

        logger.debug("Transfer saved successfully: {} - New Version: {}",
                    savedEntity.getEndToEndId(), savedEntity.getVersion());

        return savedEntity;
    }

}