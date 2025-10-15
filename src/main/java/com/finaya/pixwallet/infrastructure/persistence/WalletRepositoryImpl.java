package com.finaya.pixwallet.infrastructure.persistence;

import com.finaya.pixwallet.domain.entity.Wallet;
import com.finaya.pixwallet.domain.repository.WalletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepositoryImpl extends SimpleJpaRepository<Wallet, UUID> implements WalletRepository {

    private static final Logger logger = LoggerFactory.getLogger(WalletRepositoryImpl.class);

    @PersistenceContext
    private EntityManager entityManager;

    public WalletRepositoryImpl(EntityManager entityManager) {
        super(Wallet.class, entityManager);
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Wallet> findByUserId(String userId) {
        logger.debug("Finding wallet by userId: {}", userId);

        try {
            Wallet wallet = entityManager.createQuery(
                "SELECT w FROM Wallet w WHERE w.userId = :userId", Wallet.class)
                .setParameter("userId", userId)
                .getSingleResult();

            logger.debug("Found wallet for userId: {} - ID: {}", userId, wallet.getId());
            return Optional.of(wallet);

        } catch (NoResultException e) {
            logger.debug("No wallet found for userId: {}", userId);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Wallet> findByIdWithLock(UUID id) {
        logger.debug("Finding wallet by ID with pessimistic lock: {}", id);

        try {
            Wallet wallet = entityManager.createQuery(
                "SELECT w FROM Wallet w WHERE w.id = :id", Wallet.class)
                .setParameter("id", id)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();

            logger.debug("Found and locked wallet: {} - Balance: {}, Version: {}",
                        id, wallet.getBalance(), wallet.getVersion());
            return Optional.of(wallet);

        } catch (NoResultException e) {
            logger.debug("No wallet found for ID: {}", id);
            return Optional.empty();
        }
    }


    @Override
    public Optional<Wallet> findByIdWithLedgerEntries(UUID id) {
        logger.debug("Finding wallet by ID with ledger entries: {}", id);

        try {
            Wallet wallet = entityManager.createQuery(
                "SELECT w FROM Wallet w LEFT JOIN FETCH w.ledgerEntries WHERE w.id = :id", Wallet.class)
                .setParameter("id", id)
                .getSingleResult();

            logger.debug("Found wallet with ledger entries: {} - Entries: {}", id, wallet.getLedgerEntries().size());
            return Optional.of(wallet);

        } catch (NoResultException e) {
            logger.debug("No wallet found for ID: {}", id);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Wallet> findByUserIdOptimized(String userId) {
        return findByUserId(userId);
    }

    @Override
    public boolean existsByUserId(String userId) {
        logger.debug("Checking if wallet exists for userId: {}", userId);

        Long count = entityManager.createQuery(
            "SELECT COUNT(w) FROM Wallet w WHERE w.userId = :userId", Long.class)
            .setParameter("userId", userId)
            .getSingleResult();

        boolean exists = count > 0;
        logger.debug("Wallet exists for userId: {} - {}", userId, exists);

        return exists;
    }

    @Override
    public <S extends Wallet> S save(S entity) {
        logger.debug("Saving wallet: {} - Balance: {}, Version: {}",
                    entity.getId(), entity.getBalance(), entity.getVersion());

        S savedEntity = super.save(entity);

        logger.debug("Wallet saved successfully: {} - New Version: {}",
                    savedEntity.getId(), savedEntity.getVersion());

        return savedEntity;
    }

    @Override
    public Optional<Wallet> findById(UUID id) {
        logger.debug("Finding wallet by ID: {}", id);

        Optional<Wallet> wallet = super.findById(id);

        if (wallet.isPresent()) {
            logger.debug("Found wallet: {} - Balance: {}", id, wallet.get().getBalance());
        } else {
            logger.debug("Wallet not found: {}", id);
        }

        return wallet;
    }

    @Override
    public void deleteById(UUID id) {
        logger.info("Deleting wallet by ID: {}", id);
        super.deleteById(id);
        logger.info("Wallet deleted: {}", id);
    }

    @Override
    public void delete(Wallet entity) {
        logger.info("Deleting wallet: {}", entity.getId());
        super.delete(entity);
        logger.info("Wallet deleted: {}", entity.getId());
    }

    @Override
    public void deleteAll() {
        logger.warn("Deleting all wallets - this operation should only be used in tests");
        super.deleteAll();
        logger.warn("All wallets deleted");
    }

}