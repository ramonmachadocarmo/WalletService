package com.finaya.pixwallet.domain.repository;

import com.finaya.pixwallet.domain.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByUserId(String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdWithLock(@Param("id") UUID id);
    
    @Query("SELECT w FROM Wallet w LEFT JOIN FETCH w.ledgerEntries WHERE w.id = :id")
    Optional<Wallet> findByIdWithLedgerEntries(@Param("id") UUID id);
    
    @Query("SELECT w FROM Wallet w WHERE w.userId = :userId")
    Optional<Wallet> findByUserIdOptimized(@Param("userId") String userId);

    boolean existsByUserId(String userId);
}