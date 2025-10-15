package com.finaya.pixwallet.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @NotBlank(message = "User ID is required")
        @Size(min = 1, max = 100, message = "User ID must be between 1 and 100 characters")
        String userId
) {}