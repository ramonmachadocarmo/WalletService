package com.finaya.pixwallet.domain.valueobject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigInteger cents) {

    public static final Money ZERO = new Money(BigInteger.ZERO);

    public Money {
        Objects.requireNonNull(cents, "Cents cannot be null");
    }

    public static Money fromReais(String valor) {
        if (valor == null || valor.trim().isEmpty()) {
            throw new IllegalArgumentException("Value cannot be null or empty");
        }

        BigDecimal decimal = new BigDecimal(valor.trim());
        BigInteger cents = decimal.multiply(BigDecimal.valueOf(100))
                                   .setScale(0, RoundingMode.HALF_UP)
                                   .toBigInteger();
        return new Money(cents);
    }

    public static Money fromReais(BigDecimal valor) {
        Objects.requireNonNull(valor, "Value cannot be null");

        BigInteger cents = valor.multiply(BigDecimal.valueOf(100))
                                  .setScale(0, RoundingMode.HALF_UP)
                                  .toBigInteger();
        return new Money(cents);
    }

    public static Money fromCents(long cents) {
        return new Money(BigInteger.valueOf(cents));
    }

    public static Money fromCents(BigInteger cents) {
        return new Money(cents);
    }

    public BigDecimal toReais() {
        return new BigDecimal(cents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public Money add(Money outro) {
        Objects.requireNonNull(outro, "Value to be add cannot be null");
        return new Money(this.cents.add(outro.cents));
    }

    public Money subtract(Money outro) {
        Objects.requireNonNull(outro, "Value to be subtracted cannot be null.");
        return new Money(this.cents.subtract(outro.cents));
    }

    public boolean isGreaterThan(Money outro) {
        Objects.requireNonNull(outro, "Comparison value cannot be null");
        return this.cents.compareTo(outro.cents) > 0;
    }

    public boolean isGreaterThanOrEqualTo(Money outro) {
        Objects.requireNonNull(outro, "Comparison value cannot be null");
        return this.cents.compareTo(outro.cents) >= 0;
    }

    public boolean isLessThan(Money outro) {
        Objects.requireNonNull(outro, "Comparison value cannot be null");
        return this.cents.compareTo(outro.cents) < 0;
    }

    public boolean isLessThanOrEqualTo(Money outro) {
        Objects.requireNonNull(outro, "Comparison value cannot be null");
        return this.cents.compareTo(outro.cents) <= 0;
    }

    public boolean isZero() {
        return cents.equals(BigInteger.ZERO);
    }

    public boolean isPositive() {
        return cents.compareTo(BigInteger.ZERO) > 0;
    }

    public boolean isNegative() {
        return cents.compareTo(BigInteger.ZERO) < 0;
    }

    public Money abs() {
        return new Money(cents.abs());
    }

    public Money negate() {
        return new Money(cents.negate());
    }

    public Money multiply(int multiplier) {
        return new Money(cents.multiply(BigInteger.valueOf(multiplier)));
    }

    public Money multiply(long multiplier) {
        return new Money(cents.multiply(BigInteger.valueOf(multiplier)));
    }

    public int compareTo(Money outro) {
        Objects.requireNonNull(outro, "Comparison value cannot be null");
        return this.cents.compareTo(outro.cents);
    }

    public void validateForPix() {
        if (isNegative()) {
            throw new IllegalArgumentException("PIX value cannot be negative");
        }

        BigInteger limitePix = BigInteger.valueOf(2_000_000L);
        if (cents.compareTo(limitePix) > 0) {
            throw new IllegalArgumentException("PIX amount exceeds limit of R$ 20,000.00");
        }

        if (cents.equals(BigInteger.ZERO)) {
            throw new IllegalArgumentException("PIX value must be greater than R$ 0.00");
        }
    }

    public void validateForBalance() {
        if (isNegative()) {
            throw new IllegalArgumentException("Balance cannot be negative");
        }
    }

    public String toFormattedString() {
        return String.format("R$ %,.2f", toReais());
    }

    @Override
    public String toString() {
        return toFormattedString();
    }
}