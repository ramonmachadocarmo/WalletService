package com.finaya.pixwallet.infrastructure.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Aspect
@Component
public class LoggingAspect {

    private static final Logger logger = LoggerFactory.getLogger(LoggingAspect.class);
    private final StructuredLogger structuredLogger;

    public LoggingAspect(StructuredLogger structuredLogger) {
        this.structuredLogger = structuredLogger;
    }

    @Around("execution(* com.finaya.pixwallet.application.usecase.*.*(..))")
    public Object logUseCaseExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        Object[] args = joinPoint.getArgs();

        String correlationId = structuredLogger.generateCorrelationId();

        long startTime = System.currentTimeMillis();

        try {
            logger.debug("Executing use case: {}.{} with args: {}", className, methodName, Arrays.toString(args));

            Object result = joinPoint.proceed();

            long duration = System.currentTimeMillis() - startTime;
            structuredLogger.logPerformanceMetric(className + "." + methodName, duration, correlationId);

            logger.debug("Use case executed successfully: {}.{} in {}ms", className, methodName, duration);

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;

            Map<String, String> errorContext = new HashMap<>();
            errorContext.put("className", className);
            errorContext.put("methodName", methodName);
            errorContext.put("duration", String.valueOf(duration));
            errorContext.put("args", Arrays.toString(args));

            structuredLogger.logError(
                className + "." + methodName,
                e.getClass().getSimpleName(),
                e.getMessage(),
                errorContext
            );

            logger.error("Use case failed: {}.{} after {}ms", className, methodName, duration, e);

            throw e;

        } finally {
            structuredLogger.clearContext();
        }
    }

    @Around("execution(* com.finaya.pixwallet.domain.service.*.*(..))")
    public Object logDomainServiceExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();

        long startTime = System.currentTimeMillis();

        try {
            logger.debug("Executing domain service: {}.{}", className, methodName);

            Object result = joinPoint.proceed();

            long duration = System.currentTimeMillis() - startTime;
            logger.debug("Domain service executed successfully: {}.{} in {}ms", className, methodName, duration);

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;

            Map<String, String> errorContext = new HashMap<>();
            errorContext.put("className", className);
            errorContext.put("methodName", methodName);
            errorContext.put("duration", String.valueOf(duration));

            structuredLogger.logError(
                className + "." + methodName,
                e.getClass().getSimpleName(),
                e.getMessage(),
                errorContext
            );

            throw e;
        }
    }

    @Around("execution(* com.finaya.pixwallet.application.controller.*.*(..))")
    public Object logControllerExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();

        String correlationId = structuredLogger.generateCorrelationId();

        long startTime = System.currentTimeMillis();

        try {
            logger.info("Processing HTTP request: {}.{}", className, methodName);

            Object result = joinPoint.proceed();

            long duration = System.currentTimeMillis() - startTime;
            structuredLogger.logPerformanceMetric("HTTP." + className + "." + methodName, duration, correlationId);

            logger.info("HTTP request processed successfully: {}.{} in {}ms", className, methodName, duration);

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;

            Map<String, String> errorContext = new HashMap<>();
            errorContext.put("className", className);
            errorContext.put("methodName", methodName);
            errorContext.put("duration", String.valueOf(duration));
            errorContext.put("requestType", "HTTP");

            structuredLogger.logError(
                "HTTP." + className + "." + methodName,
                e.getClass().getSimpleName(),
                e.getMessage(),
                errorContext
            );

            throw e;

        } finally {
            structuredLogger.clearContext();
        }
    }

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object logTransactionalMethods(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();

        String transactionId = "TX-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        structuredLogger.setTransactionId(transactionId);

        long startTime = System.currentTimeMillis();

        try {
            logger.debug("Starting transaction: {} for {}.{}", transactionId, className, methodName);

            Object result = joinPoint.proceed();

            long duration = System.currentTimeMillis() - startTime;
            logger.debug("Transaction completed: {} for {}.{} in {}ms", transactionId, className, methodName, duration);

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Transaction failed: {} for {}.{} after {}ms - {}",
                        transactionId, className, methodName, duration, e.getMessage());
            throw e;
        }
    }
}