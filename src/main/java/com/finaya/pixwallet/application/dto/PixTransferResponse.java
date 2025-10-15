package com.finaya.pixwallet.application.dto;

import com.finaya.pixwallet.domain.entity.PixTransferStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PixTransferResponse(
        UUID id,
        String endToEndId,
        UUID fromWalletId,
        String toPixKey,
        BigDecimal amount,
        PixTransferStatus status,
        LocalDateTime createdAt
) {}