package com.finaya.pixwallet.application.controller;

import com.finaya.pixwallet.application.dto.PixTransferRequest;
import com.finaya.pixwallet.application.dto.PixTransferResponse;
import com.finaya.pixwallet.application.dto.PixWebhookRequest;
import com.finaya.pixwallet.application.usecase.PixTransferUseCase;
import com.finaya.pixwallet.domain.entity.PixTransfer;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.finaya.pixwallet.domain.valueobject.Money;

@RestController
@RequestMapping("/api/v1/pix")
public class PixController {

    private static final Logger logger = LoggerFactory.getLogger(PixController.class);

    private final PixTransferUseCase pixTransferUseCase;
    private final Counter transferCounter;
    private final Counter webhookCounter;

    public PixController(PixTransferUseCase pixTransferUseCase, MeterRegistry meterRegistry) {
        this.pixTransferUseCase = pixTransferUseCase;
        this.transferCounter = Counter.builder("pix.transfer")
                .description("Number of Pix transfers initiated")
                .register(meterRegistry);
        this.webhookCounter = Counter.builder("pix.webhook")
                .description("Number of webhook events processed")
                .register(meterRegistry);
    }

    @PostMapping("/transfers")
    @Timed(value = "pix.transfer.time", description = "Time taken to process a Pix transfer")
    public ResponseEntity<PixTransferResponse> initiateTransfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PixTransferRequest request) {

        logger.info("Initiating Pix transfer - Idempotency Key: {}, From: {}, To: {}, Amount: {}",
                idempotencyKey, request.fromWalletId(), request.toPixKey(), request.amount());

        try {
            Money amount = Money.fromReais(request.amount());
            PixTransfer transfer = pixTransferUseCase.initiateTransfer(
                    idempotencyKey,
                    request.fromWalletId(),
                    request.toPixKey(),
                    amount);

            PixTransferResponse response = new PixTransferResponse(
                    transfer.getId(),
                    transfer.getEndToEndId(),
                    transfer.getFromWalletId(),
                    transfer.getToPixKey(),
                    transfer.getAmount().toReais(),
                    transfer.getStatus(),
                    transfer.getCreatedAt());

            transferCounter.increment();

            logger.info("Pix transfer initiated successfully - End-to-End ID: {}, Transfer ID: {}",
                    transfer.getEndToEndId(), transfer.getId());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            logger.error("Failed to initiate Pix transfer: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/webhook")
    @Timed(value = "pix.webhook.time", description = "Time taken to process a webhook event")
    public ResponseEntity<Void> processWebhook(@Valid @RequestBody PixWebhookRequest request) {
        logger.info("Processing Pix webhook - End-to-End ID: {}, Event ID: {}, Event Type: {}",
                request.endToEndId(), request.eventId(), request.eventType());

        try {
            pixTransferUseCase.processWebhookEvent(
                    request.endToEndId(),
                    request.eventId(),
                    request.eventType());

            webhookCounter.increment();

            logger.info("Webhook processed successfully - End-to-End ID: {}, Event ID: {}",
                    request.endToEndId(), request.eventId());

            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            logger.error("Failed to process webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Unexpected error processing webhook: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}