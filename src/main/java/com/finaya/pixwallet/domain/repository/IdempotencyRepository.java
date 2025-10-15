package com.finaya.pixwallet.domain.repository;

import com.finaya.pixwallet.domain.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByScopeAndIdempotencyKey(String scope, String idempotencyKey);

    @Modifying
    @Query("DELETE FROM IdempotencyRecord ir WHERE ir.expiresAt < :now")
    int deleteExpiredRecords(@Param("now") LocalDateTime now);
}