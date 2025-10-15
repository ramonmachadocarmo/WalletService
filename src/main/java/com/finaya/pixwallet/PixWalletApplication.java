package com.finaya.pixwallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableCaching
@EnableRetry
@EnableTransactionManagement
public class PixWalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(PixWalletApplication.class, args);
    }
}