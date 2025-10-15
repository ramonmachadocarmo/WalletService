package com.finaya.pixwallet.application.controller;

import com.finaya.pixwallet.application.usecase.PixTransferUseCase;
import com.finaya.pixwallet.application.usecase.WalletUseCase;
import com.finaya.pixwallet.domain.service.AtomicTransferService;
import com.finaya.pixwallet.domain.service.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private static final Logger logger = LoggerFactory.getLogger(MonitoringController.class);

    private final WalletUseCase walletUseCase;
    private final PixTransferUseCase pixTransferUseCase;
    private final AtomicTransferService atomicTransferService;
    private final IdempotencyService idempotencyService;

    private final AtomicLong monitoringRequests = new AtomicLong(0);

    public MonitoringController(WalletUseCase walletUseCase,
                               PixTransferUseCase pixTransferUseCase,
                               AtomicTransferService atomicTransferService,
                               IdempotencyService idempotencyService) {
        this.walletUseCase = walletUseCase;
        this.pixTransferUseCase = pixTransferUseCase;
        this.atomicTransferService = atomicTransferService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/atomic-stats")
    public AtomicSystemStats getAtomicStats() {
        long requestNumber = monitoringRequests.incrementAndGet();
        logger.debug("Retrieving atomic stats - Request #{}", requestNumber);

        WalletUseCase.WalletStats walletStats = walletUseCase.getAtomicStats();
        PixTransferUseCase.WebhookStats webhookStats = pixTransferUseCase.getWebhookStats();
        AtomicTransferService.TransferStats transferStats = atomicTransferService.getAtomicStats();
        IdempotencyService.ProcessingStats idempotencyStats = idempotencyService.getProcessingStats();

        return new AtomicSystemStats(
            LocalDateTime.now(),
            requestNumber,
            walletStats,
            webhookStats,
            transferStats,
            idempotencyStats
        );
    }

    @GetMapping("/system-health")
    public SystemHealthStats getSystemHealth() {
        logger.debug("Retrieving system health stats");

        AtomicSystemStats atomicStats = getAtomicStats();

        return new SystemHealthStats(
            LocalDateTime.now(),
            calculateSystemLoad(atomicStats),
            calculateConcurrencyLevel(atomicStats),
            calculateErrorRate(atomicStats),
            atomicStats.getTransferStats().getSuccessRate()
        );
    }

    @PostMapping("/cleanup")
    public CleanupResult performCleanup() {
        logger.info("Performing atomic cleanup operations");

        LocalDateTime startTime = LocalDateTime.now();

        idempotencyService.cleanupExpiredRecords();

        atomicTransferService.cleanupCompletedTransfers();

        walletUseCase.cleanupOperationLocks();

        LocalDateTime endTime = LocalDateTime.now();

        logger.info("Atomic cleanup operations completed");

        return new CleanupResult(
            startTime,
            endTime,
            "Atomic cleanup completed successfully"
        );
    }

    private double calculateSystemLoad(AtomicSystemStats stats) {
        int totalActiveLocks = stats.getWalletStats().getActiveLocks() +
                              stats.getTransferStats().getWalletLocks() +
                              stats.getIdempotencyStats().getLockCount();

        return Math.min(100.0, (double) totalActiveLocks / 10 * 100); // Normalized to 0-100%
    }

    private int calculateConcurrencyLevel(AtomicSystemStats stats) {
        return stats.getTransferStats().getActiveTransfers() +
               stats.getWalletStats().getActiveLocks();
    }

    private double calculateErrorRate(AtomicSystemStats stats) {
        long totalTransfers = stats.getTransferStats().getTotalTransfers();
        long failedTransfers = stats.getTransferStats().getFailedTransfers();

        return totalTransfers > 0 ? (double) failedTransfers / totalTransfers * 100 : 0;
    }

    public static class AtomicSystemStats {
        private final LocalDateTime timestamp;
        private final long monitoringRequestNumber;
        private final WalletUseCase.WalletStats walletStats;
        private final PixTransferUseCase.WebhookStats webhookStats;
        private final AtomicTransferService.TransferStats transferStats;
        private final IdempotencyService.ProcessingStats idempotencyStats;

        public AtomicSystemStats(LocalDateTime timestamp, long monitoringRequestNumber,
                                WalletUseCase.WalletStats walletStats,
                                PixTransferUseCase.WebhookStats webhookStats,
                                AtomicTransferService.TransferStats transferStats,
                                IdempotencyService.ProcessingStats idempotencyStats) {
            this.timestamp = timestamp;
            this.monitoringRequestNumber = monitoringRequestNumber;
            this.walletStats = walletStats;
            this.webhookStats = webhookStats;
            this.transferStats = transferStats;
            this.idempotencyStats = idempotencyStats;
        }

        public LocalDateTime getTimestamp() { return timestamp; }
        public long getMonitoringRequestNumber() { return monitoringRequestNumber; }
        public WalletUseCase.WalletStats getWalletStats() { return walletStats; }
        public PixTransferUseCase.WebhookStats getWebhookStats() { return webhookStats; }
        public AtomicTransferService.TransferStats getTransferStats() { return transferStats; }
        public IdempotencyService.ProcessingStats getIdempotencyStats() { return idempotencyStats; }
    }

    public static class SystemHealthStats {
        private final LocalDateTime timestamp;
        private final double systemLoad;
        private final int concurrencyLevel;
        private final double errorRate;
        private final double successRate;

        public SystemHealthStats(LocalDateTime timestamp, double systemLoad, int concurrencyLevel,
                                double errorRate, double successRate) {
            this.timestamp = timestamp;
            this.systemLoad = systemLoad;
            this.concurrencyLevel = concurrencyLevel;
            this.errorRate = errorRate;
            this.successRate = successRate;
        }

        public LocalDateTime getTimestamp() { return timestamp; }
        public double getSystemLoad() { return systemLoad; }
        public int getConcurrencyLevel() { return concurrencyLevel; }
        public double getErrorRate() { return errorRate; }
        public double getSuccessRate() { return successRate; }
        public String getHealthStatus() {
            if (errorRate > 10) return "CRITICAL";
            if (errorRate > 5 || systemLoad > 80) return "WARNING";
            if (successRate > 95 && systemLoad < 50) return "EXCELLENT";
            return "GOOD";
        }
    }

    public static class CleanupResult {
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;
        private final String message;

        public CleanupResult(LocalDateTime startTime, LocalDateTime endTime, String message) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.message = message;
        }

        public LocalDateTime getStartTime() { return startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public String getMessage() { return message; }
        public long getDurationMs() {
            return java.time.Duration.between(startTime, endTime).toMillis();
        }
    }
}