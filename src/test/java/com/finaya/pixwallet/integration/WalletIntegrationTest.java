package com.finaya.pixwallet.integration;

import com.finaya.pixwallet.application.dto.CreateWalletRequest;
import com.finaya.pixwallet.application.dto.DepositRequest;
import com.finaya.pixwallet.application.dto.WithdrawRequest;
import com.finaya.pixwallet.domain.entity.Wallet;
import com.finaya.pixwallet.domain.repository.WalletRepository;
import com.finaya.pixwallet.domain.valueobject.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebMvc
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalletIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WalletRepository walletRepository;

    @BeforeEach
    void setUp() {
        walletRepository.deleteAll();
    }

    @Test
    void shouldCreateWallet() throws Exception {
        CreateWalletRequest request = new CreateWalletRequest("user123");

        MvcResult result = mockMvc.perform(post("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("user123"))
                .andExpect(jsonPath("$.balance.cents").value(0))
                .andReturn();

        String responseContent = result.getResponse().getContentAsString();
        Wallet wallet = objectMapper.readValue(responseContent, Wallet.class);

        assertTrue(walletRepository.existsById(wallet.getId()));
    }

    @Test
    void shouldNotCreateDuplicateWallet() throws Exception {
        CreateWalletRequest request = new CreateWalletRequest("user123");

        mockMvc.perform(post("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldGetWalletBalance() throws Exception {
        Wallet wallet = new Wallet("user123");
        wallet = walletRepository.save(wallet);

        mockMvc.perform(get("/api/v1/wallets/{id}/balance", wallet.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(wallet.getId().toString()))
                .andExpect(jsonPath("$.balance").value(0.0));
    }

    @Test
    void shouldProcessDeposit() throws Exception {
        Wallet wallet = new Wallet("user123");
        wallet = walletRepository.save(wallet);

        DepositRequest request = new DepositRequest(Money.fromReais("100.50").toReais(), "Test deposit");

        mockMvc.perform(post("/api/v1/wallets/{id}/deposit", wallet.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        Wallet updatedWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertEquals(Money.fromReais("100.50"), updatedWallet.getBalance());
    }

    @Test
    void shouldProcessWithdrawal() throws Exception {
        Wallet wallet = new Wallet("user123");
        wallet.credit(Money.fromReais("200.00"), "Initial deposit", "INIT-123");
        wallet = walletRepository.save(wallet);

        WithdrawRequest request = new WithdrawRequest(Money.fromReais("50.25").toReais(), "Test withdrawal");

        mockMvc.perform(post("/api/v1/wallets/{id}/withdraw", wallet.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        Wallet updatedWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertEquals(Money.fromReais("149.75"), updatedWallet.getBalance());
    }

    @Test
    void shouldNotWithdrawWhenInsufficientFunds() throws Exception {
        Wallet wallet = new Wallet("user123");
        wallet = walletRepository.save(wallet);

        WithdrawRequest request = new WithdrawRequest(Money.fromReais("100.00").toReais(), "Test withdrawal");

        mockMvc.perform(post("/api/v1/wallets/{id}/withdraw", wallet.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnNotFoundForNonExistentWallet() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/wallets/{id}/balance", nonExistentId))
                .andExpect(status().isNotFound());
    }
}