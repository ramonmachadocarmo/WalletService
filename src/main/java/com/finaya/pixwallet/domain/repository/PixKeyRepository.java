package com.finaya.pixwallet.domain.repository;

import com.finaya.pixwallet.domain.entity.PixKey;
import com.finaya.pixwallet.domain.entity.PixKeyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PixKeyRepository extends JpaRepository<PixKey, UUID> {

    @Query("SELECT pk FROM PixKey pk WHERE pk.keyValue = :keyValue AND pk.isActive = true")
    Optional<PixKey> findActiveByKeyValue(@Param("keyValue") String keyValue);

    boolean existsByKeyValueAndKeyTypeAndIsActive(String keyValue, PixKeyType keyType, Boolean isActive);
}