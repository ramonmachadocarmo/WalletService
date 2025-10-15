package com.finaya.pixwallet.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record PixWebhookRequest(
        @NotBlank(message = "End-to-end ID is required")
        @Size(min = 1, max = 32, message = "End-to-end ID must be between 1 and 32 characters")
        String endToEndId,

        @NotBlank(message = "Event ID is required")
        @Size(min = 1, max = 100, message = "Event ID must be between 1 and 100 characters")
        String eventId,

        @NotBlank(message = "Event type is required")
        @Pattern(regexp = "CONFIRMED|REJECTED", message = "Event type must be CONFIRMED or REJECTED")
        String eventType,

        LocalDateTime occurredAt
) {}