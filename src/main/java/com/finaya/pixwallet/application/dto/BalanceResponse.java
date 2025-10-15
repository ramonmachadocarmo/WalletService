package com.finaya.pixwallet.application.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record BalanceResponse(
        UUID walletId,
        BigDecimal balance,
        LocalDateTime timestamp
) {}