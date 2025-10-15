package com.finaya.pixwallet.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record PixTransferRequest(
        @NotNull(message = "From wallet ID is required")
        UUID fromWalletId,

        @NotBlank(message = "To Pix key is required")
        @Size(min = 1, max = 500, message = "To Pix key must be between 1 and 500 characters")
        String toPixKey,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than 0.01")
        BigDecimal amount
) {}