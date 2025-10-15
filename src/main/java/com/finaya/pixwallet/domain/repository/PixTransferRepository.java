package com.finaya.pixwallet.domain.repository;

import com.finaya.pixwallet.domain.entity.PixTransfer;
import com.finaya.pixwallet.domain.entity.PixTransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PixTransferRepository extends JpaRepository<PixTransfer, UUID> {

    Optional<PixTransfer> findByEndToEndId(String endToEndId);

    Optional<PixTransfer> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pt FROM PixTransfer pt WHERE pt.endToEndId = :endToEndId")
    Optional<PixTransfer> findByEndToEndIdWithLock(@Param("endToEndId") String endToEndId);

    @Query("SELECT pt FROM PixTransfer pt WHERE pt.fromWalletId = :walletId ORDER BY pt.createdAt DESC")
    List<PixTransfer> findByFromWalletIdOrderByCreatedAtDesc(@Param("walletId") UUID walletId);

    @Query("SELECT pt FROM PixTransfer pt WHERE pt.status = :status ORDER BY pt.createdAt ASC")
    List<PixTransfer> findByStatusOrderByCreatedAtAsc(@Param("status") PixTransferStatus status);
}