package com.finaya.pixwallet.infrastructure.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class StructuredLogger {

    private final ObjectMapper objectMapper;
    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String TRANSACTION_ID_KEY = "transactionId";
    private static final String USER_ID_KEY = "userId";

    public StructuredLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void logTransferInitiated(String endToEndId, String idempotencyKey, UUID fromWalletId, String toPixKey, String amount) {
        Logger logger = LoggerFactory.getLogger("com.finaya.pixwallet.transfer");

        Map<String, Object> logData = new HashMap<>();
        logData.put("event", "transfer_initiated");
        logData.put("endToEndId", endToEndId);
        logData.put("idempotencyKey", idempotencyKey);
        logData.put("fromWalletId", fromWalletId.toString());
        logData.put("toPixKey", toPixKey);
        logData.put("amount", amount);
        logData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        logStructured(logger, "INFO", "Pix transfer initiated", logData);
    }

    public void logTransferCompleted(String endToEndId, String status, String walletId, String amount) {
        Logger logger = LoggerFactory.getLogger("com.finaya.pixwallet.transfer");

        Map<String, Object> logData = new HashMap<>();
        logData.put("event", "transfer_completed");
        logData.put("endToEndId", endToEndId);
        logData.put("status", status);
        logData.put("walletId", walletId);
        logData.put("amount", amount);
        logData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        logStructured(logger, "INFO", "Pix transfer completed", logData);
    }

    public void logWalletOperation(String operationType, UUID walletId, String amount, String transactionId) {
        Logger logger = LoggerFactory.getLogger("com.finaya.pixwallet.wallet");

        Map<String, Object> logData = new HashMap<>();
        logData.put("event", "wallet_operation");
        logData.put("operationType", operationType);
        logData.put("walletId", walletId.toString());
        logData.put("amount", amount);
        logData.put("transactionId", transactionId);
        logData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        logStructured(logger, "INFO", "Wallet operation performed", logData);
    }

    public void logWebhookEvent(String endToEndId, String eventId, String eventType, String status) {
        Logger logger = LoggerFactory.getLogger("com.finaya.pixwallet.webhook");

        Map<String, Object> logData = new HashMap<>();
        logData.put("event", "webhook_processed");
        logData.put("endToEndId", endToEndId);
        logData.put("eventId", eventId);
        logData.put("eventType", eventType);
        logData.put("status", status);
        logData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        logStructured(logger, "INFO", "Webhook event processed", logData);
    }

    public void logIdempotencyCheck(String scope, String idempotencyKey, boolean found, boolean expired) {
        Logger logger = LoggerFactory.getLogger("com.finaya.pixwallet.idempotency");

        Map<String, Object> logData = new HashMap<>();
        logData.put("event", "idempotency_check");
        logData.put("scope", scope);
        logData.put("idempotencyKey", idempotencyKey);
        logData.put("found", found);
        logData.put("expired", expired);
        logData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        logStructured(logger, "DEBUG", "Idempotency check performed", logData);
    }

    public void logConcurrencyEvent(String eventType, String resourceId, int activeThreads, String lockType) {
        Logger logger = LoggerFactory.getLogger("com.finaya.pixwallet.concurrency");

        Map<String, Object> logData = new HashMap<>();
        logData.put("event", "concurrency_event");
        logData.put("eventType", eventType);
        logData.put("resourceId", resourceId);
        logData.put("activeThreads", activeThreads);
        logData.put("lockType", lockType);
        logData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        logStructured(logger, "DEBUG", "Concurrency event", logData);
    }

    public void logError(String operation, String errorType, String errorMessage, Map<String, String> context) {
        Logger logger = LoggerFactory.getLogger("com.finaya.pixwallet.error");

        Map<String, Object> logData = new HashMap<>();
        logData.put("event", "error");
        logData.put("operation", operation);
        logData.put("errorType", errorType);
        logData.put("errorMessage", errorMessage);
        logData.put("context", context);
        logData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        logStructured(logger, "ERROR", "Operation failed", logData);
    }

    public void logPerformanceMetric(String operation, long durationMs, String resourceId) {
        Logger logger = LoggerFactory.getLogger("com.finaya.pixwallet.performance");

        Map<String, Object> logData = new HashMap<>();
        logData.put("event", "performance_metric");
        logData.put("operation", operation);
        logData.put("durationMs", durationMs);
        logData.put("resourceId", resourceId);
        logData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        logStructured(logger, "INFO", "Performance metric", logData);
    }

    public void setCorrelationId(String correlationId) {
        MDC.put(CORRELATION_ID_KEY, correlationId);
    }

    public void setTransactionId(String transactionId) {
        MDC.put(TRANSACTION_ID_KEY, transactionId);
    }

    public void setUserId(String userId) {
        MDC.put(USER_ID_KEY, userId);
    }

    public String generateCorrelationId() {
        String correlationId = UUID.randomUUID().toString();
        setCorrelationId(correlationId);
        return correlationId;
    }

    public void clearContext() {
        MDC.clear();
    }

    private void logStructured(Logger logger, String level, String message, Map<String, Object> data) {
        // Add MDC context to the log data
        String correlationId = MDC.get(CORRELATION_ID_KEY);
        String transactionId = MDC.get(TRANSACTION_ID_KEY);
        String userId = MDC.get(USER_ID_KEY);

        if (correlationId != null) {
            data.put("correlationId", correlationId);
        }
        if (transactionId != null) {
            data.put("transactionId", transactionId);
        }
        if (userId != null) {
            data.put("userId", userId);
        }

        try {
            String jsonLog = objectMapper.writeValueAsString(data);

            switch (level.toUpperCase()) {
                case "ERROR":
                    logger.error("{} - {}", message, jsonLog);
                    break;
                case "WARN":
                    logger.warn("{} - {}", message, jsonLog);
                    break;
                case "INFO":
                    logger.info("{} - {}", message, jsonLog);
                    break;
                case "DEBUG":
                    logger.debug("{} - {}", message, jsonLog);
                    break;
                default:
                    logger.info("{} - {}", message, jsonLog);
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize log data to JSON - serialization error occurred");
            logger.info(message); // Fallback to simple message
        }
    }
}