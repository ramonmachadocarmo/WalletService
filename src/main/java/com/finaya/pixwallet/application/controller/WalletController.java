package com.finaya.pixwallet.application.controller;

import com.finaya.pixwallet.application.dto.*;
import com.finaya.pixwallet.application.usecase.WalletUseCase;
import com.finaya.pixwallet.domain.entity.PixKey;
import com.finaya.pixwallet.domain.entity.Wallet;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.finaya.pixwallet.domain.valueobject.Money;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private static final Logger logger = LoggerFactory.getLogger(WalletController.class);

    private final WalletUseCase walletUseCase;
    private final Counter walletCreationCounter;
    private final Counter depositCounter;
    private final Counter withdrawalCounter;

    public WalletController(WalletUseCase walletUseCase, MeterRegistry meterRegistry) {
        this.walletUseCase = walletUseCase;
        this.walletCreationCounter = Counter.builder("wallet.created")
                .description("Number of wallets created")
                .register(meterRegistry);
        this.depositCounter = Counter.builder("wallet.deposit")
                .description("Number of deposits processed")
                .register(meterRegistry);
        this.withdrawalCounter = Counter.builder("wallet.withdrawal")
                .description("Number of withdrawals processed")
                .register(meterRegistry);
    }

    @PostMapping
    @Timed(value = "wallet.creation.time", description = "Time taken to create a wallet")
    public ResponseEntity<Wallet> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        logger.info("Creating wallet for user: {}", request.userId());

        try {
            Wallet wallet = walletUseCase.createWallet(request.userId());
            walletCreationCounter.increment();

            logger.info("Wallet created successfully - ID: {}, User: {}", wallet.getId(), request.userId());
            return ResponseEntity.status(HttpStatus.CREATED).body(wallet);
        } catch (IllegalArgumentException e) {
            logger.error("Failed to create wallet - validation error occurred");
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Unexpected error creating wallet", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/{id}/pix-keys")
    @Timed(value = "pixkey.registration.time", description = "Time taken to register a Pix key")
    public ResponseEntity<PixKey> registerPixKey(@PathVariable UUID id,
            @Valid @RequestBody CreatePixKeyRequest request) {
        logger.info("Registering Pix key for wallet: {} - Key: {}, Type: {}", id, request.keyValue(),
                request.keyType());

        try {
            PixKey pixKey = walletUseCase.registerPixKey(id, request.keyValue(), request.keyType());

            logger.info("Pix key registered successfully - ID: {}, Key: {}", pixKey.getId(), request.keyValue());
            return ResponseEntity.status(HttpStatus.CREATED).body(pixKey);
        } catch (IllegalArgumentException e) {
            logger.error("Failed to register Pix key for wallet {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{id}/balance")
    @Timed(value = "balance.query.time", description = "Time taken to query balance")
    public ResponseEntity<BalanceResponse> getBalance(
            @PathVariable UUID id,
            @RequestParam(name = "at", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timestamp) {

        logger.debug("Getting balance for wallet: {}, timestamp: {}", id, timestamp);

        try {
            Money balance;
            if (timestamp != null) {
                balance = walletUseCase.getHistoricalBalance(id, timestamp);
            } else {
                balance = walletUseCase.getBalance(id);
                timestamp = LocalDateTime.now();
            }

            BalanceResponse response = new BalanceResponse(id, balance.toReais(), timestamp);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.error("Failed to get balance for wallet {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/deposit")
    @Timed(value = "deposit.processing.time", description = "Time taken to process a deposit")
    public ResponseEntity<Void> deposit(@PathVariable UUID id, @Valid @RequestBody DepositRequest request) {
        logger.info("Processing deposit for wallet: {} - Amount: {}", id, request.amount());

        try {
            String description = request.description() != null ? request.description() : "Deposit";
            Money amount = Money.fromReais(request.amount());
            walletUseCase.deposit(id, amount, description);
            depositCounter.increment();

            logger.info("Deposit processed successfully for wallet: {} - Amount: {}", id, request.amount());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            logger.error("Failed to process deposit for wallet {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/withdraw")
    @Timed(value = "withdrawal.processing.time", description = "Time taken to process a withdrawal")
    public ResponseEntity<Void> withdraw(@PathVariable UUID id, @Valid @RequestBody WithdrawRequest request) {
        logger.info("Processing withdrawal for wallet: {} - Amount: {}", id, request.amount());

        try {
            String description = request.description() != null ? request.description() : "Withdrawal";
            Money amount = Money.fromReais(request.amount());
            walletUseCase.withdraw(id, amount, description);
            withdrawalCounter.increment();

            logger.info("Withdrawal processed successfully for wallet: {} - Amount: {}", id, request.amount());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            logger.error("Failed to process withdrawal for wallet {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}