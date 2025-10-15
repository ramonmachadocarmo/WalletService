package com.finaya.pixwallet.application.usecase;

import com.finaya.pixwallet.domain.entity.*;
import com.finaya.pixwallet.domain.repository.PixKeyRepository;
import com.finaya.pixwallet.domain.repository.PixTransferRepository;
import com.finaya.pixwallet.domain.repository.WalletRepository;
import com.finaya.pixwallet.domain.service.AtomicTransferService;
import com.finaya.pixwallet.domain.service.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.finaya.pixwallet.domain.valueobject.Money;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PixTransferUseCase {

    private static final Logger logger = LoggerFactory.getLogger(PixTransferUseCase.class);

    private final PixTransferRepository pixTransferRepository;
    private final WalletRepository walletRepository;
    private final PixKeyRepository pixKeyRepository;
    private final IdempotencyService idempotencyService;
    private final AtomicTransferService atomicTransferService;

    private final AtomicInteger webhookEventsProcessed = new AtomicInteger(0);
    private final AtomicInteger duplicateWebhooks = new AtomicInteger(0);

    public PixTransferUseCase(PixTransferRepository pixTransferRepository,
                              WalletRepository walletRepository,
                              PixKeyRepository pixKeyRepository,
                              IdempotencyService idempotencyService,
                              AtomicTransferService atomicTransferService) {
        this.pixTransferRepository = pixTransferRepository;
        this.walletRepository = walletRepository;
        this.pixKeyRepository = pixKeyRepository;
        this.idempotencyService = idempotencyService;
        this.atomicTransferService = atomicTransferService;
    }

    @Transactional
    @Retryable(value = {DataIntegrityViolationException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public PixTransfer initiateTransfer(String idempotencyKey, UUID fromWalletId, String toPixKey, Money amount) {
        logger.info("Initiating Pix transfer with atomic operations - Idempotency Key: {}, From: {}, To: {}, Amount: {}",
                   idempotencyKey, fromWalletId, toPixKey, amount);

        Optional<PixTransfer> existingTransfer = pixTransferRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTransfer.isPresent()) {
            logger.info("Transfer already exists for idempotency key: {}", idempotencyKey);
            return existingTransfer.get();
        }

        PixKey destinationPixKey = pixKeyRepository.findActiveByKeyValue(toPixKey)
                .orElseThrow(() -> new IllegalArgumentException("Destination Pix key not found or inactive: " + toPixKey));

        String endToEndId = generateEndToEndId();

        PixTransfer transfer = atomicTransferService.createTransferAtomically(
            endToEndId,
            idempotencyKey,
            fromWalletId,
            toPixKey,
            amount
        );

        logger.info("Pix transfer initiated atomically - End-to-End ID: {}, Transfer ID: {}",
                   endToEndId, transfer.getId());

        return transfer;
    }

    @Transactional
    @Retryable(value = {Exception.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public void processWebhookEvent(String endToEndId, String eventId, String eventType) {
        logger.info("Processing webhook event atomically - End-to-End ID: {}, Event ID: {}, Event Type: {}",
                   endToEndId, eventId, eventType);

        int eventNumber = webhookEventsProcessed.incrementAndGet();

        Optional<IdempotencyRecord> existingEvent = idempotencyService.findExistingRecord("webhook", eventId);
        if (existingEvent.isPresent()) {
            logger.info("Webhook event already processed atomically - Event ID: {}", eventId);
            duplicateWebhooks.incrementAndGet();
            return;
        }

        PixTransferStatus targetStatus = parseEventType(eventType);
        if (targetStatus == null) {
            logger.warn("Unknown event type: {} for End-to-End ID: {}", eventType, endToEndId);
            return;
        }

        boolean stateUpdated = atomicTransferService.updateTransferStateAtomically(
            endToEndId,
            targetStatus,
            "Processed via webhook event: " + eventId
        );

        if (!stateUpdated) {
            logger.warn("Failed to update transfer state atomically - End-to-End ID: {}, Target Status: {}",
                       endToEndId, targetStatus);
            return;
        }

        PixTransfer transfer = pixTransferRepository.findByEndToEndId(endToEndId)
                .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + endToEndId));

        switch (targetStatus) {
            case CONFIRMED:
                handleConfirmedEventAtomically(transfer);
                break;
            case REJECTED:
                handleRejectedEventAtomically(transfer);
                break;
        }

        idempotencyService.saveRecordAtomically("webhook", eventId, endToEndId, "processed", 200);

        logger.info("Webhook event processed atomically - Event #{}, End-to-End ID: {}, Event ID: {}, Status: {}",
                   eventNumber, endToEndId, eventId, targetStatus);
    }

    private void handleConfirmedEventAtomically(PixTransfer transfer) {
        logger.info("Handling confirmed event atomically - End-to-End ID: {}", transfer.getEndToEndId());

        PixKey destinationPixKey = pixKeyRepository.findActiveByKeyValue(transfer.getToPixKey())
                .orElseThrow(() -> new IllegalArgumentException("Destination Pix key not found: " + transfer.getToPixKey()));

        UUID destinationWalletId = destinationPixKey.getWallet().getId();

        atomicTransferService.creditDestinationWalletAtomically(
            destinationWalletId,
            transfer.getAmount(),
            transfer.getEndToEndId()
        );

        logger.info("Transfer confirmed and credited atomically - End-to-End ID: {}, Destination Wallet: {}",
                   transfer.getEndToEndId(), destinationWalletId);
    }

    private void handleRejectedEventAtomically(PixTransfer transfer) {
        logger.info("Handling rejected event atomically - End-to-End ID: {}", transfer.getEndToEndId());

        atomicTransferService.refundSourceWalletAtomically(
            transfer.getFromWalletId(),
            transfer.getAmount(),
            transfer.getEndToEndId() + "-REFUND"
        );

        logger.info("Transfer rejected and refunded atomically - End-to-End ID: {}, Source Wallet: {}",
                   transfer.getEndToEndId(), transfer.getFromWalletId());
    }

    private PixTransferStatus parseEventType(String eventType) {
        return switch (eventType.toUpperCase()) {
            case "CONFIRMED" -> PixTransferStatus.CONFIRMED;
            case "REJECTED" -> PixTransferStatus.REJECTED;
            default -> null;
        };
    }

    public WebhookStats getWebhookStats() {
        return new WebhookStats(
            webhookEventsProcessed.get(),
            duplicateWebhooks.get()
        );
    }

    @Transactional(readOnly = true)
    public Optional<PixTransfer> findByEndToEndId(String endToEndId) {
        return pixTransferRepository.findByEndToEndId(endToEndId);
    }

    @Transactional(readOnly = true)
    public Optional<PixTransfer> findByIdempotencyKey(String idempotencyKey) {
        return pixTransferRepository.findByIdempotencyKey(idempotencyKey);
    }

    private String generateEndToEndId() {
        return "E" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
    }

    public static class WebhookStats {
        private final int totalEvents;
        private final int duplicateEvents;

        public WebhookStats(int totalEvents, int duplicateEvents) {
            this.totalEvents = totalEvents;
            this.duplicateEvents = duplicateEvents;
        }

        public int getTotalEvents() { return totalEvents; }
        public int getDuplicateEvents() { return duplicateEvents; }
        public int getUniqueEvents() { return totalEvents - duplicateEvents; }
        public double getDuplicateRate() {
            return totalEvents > 0 ? (double) duplicateEvents / totalEvents * 100 : 0;
        }
    }
}