package com.finaya.pixwallet.application.dto;

import com.finaya.pixwallet.domain.entity.PixKeyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePixKeyRequest(
        @NotBlank(message = "Key value is required")
        @Size(min = 1, max = 500, message = "Key value must be between 1 and 500 characters")
        @Pattern(regexp = "^[a-zA-Z0-9@.+_-]+$", message = "Key value contains invalid characters")
        String keyValue,

        @NotNull(message = "Key type is required")
        PixKeyType keyType
) {}