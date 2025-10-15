package com.finaya.pixwallet.domain.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "pix_keys", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"key_value", "key_type"})
})
public class PixKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "key_value", nullable = false, length = 500)
    private String keyValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_type", nullable = false)
    private PixKeyType keyType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    @JsonBackReference
    private Wallet wallet;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    protected PixKey() {}

    public PixKey(String keyValue, PixKeyType keyType) {
        this.keyValue = Objects.requireNonNull(keyValue, "Key value cannot be null");
        this.keyType = Objects.requireNonNull(keyType, "Key type cannot be null");
        validateKey(keyValue, keyType);
        this.createdAt = LocalDateTime.now();
        this.isActive = true;
    }

    private void validateKey(String keyValue, PixKeyType keyType) {
        if (keyValue == null || keyValue.trim().isEmpty()) {
            throw new IllegalArgumentException("Key value cannot be empty");
        }

        switch (keyType) {
            case EMAIL:
                if (!isValidEmail(keyValue)) {
                    throw new IllegalArgumentException("Invalid email format");
                }
                break;
            case PHONE:
                if (!isValidPhone(keyValue)) {
                    throw new IllegalArgumentException("Invalid phone format");
                }
                break;
            case CPF:
                if (!isValidCPF(keyValue)) {
                    throw new IllegalArgumentException("Invalid CPF format");
                }
                break;
            case CNPJ:
                if (!isValidCNPJ(keyValue)) {
                    throw new IllegalArgumentException("Invalid CNPJ format");
                }
                break;
            case EVP:
                if (!isValidEVP(keyValue)) {
                    throw new IllegalArgumentException("Invalid EVP format");
                }
                break;
        }
    }

    private boolean isValidEmail(String email) {
        String emailRegex = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";
        return Pattern.compile(emailRegex).matcher(email).matches();
    }

    private boolean isValidPhone(String phone) {
        String phoneRegex = "^\\+55[1-9][0-9]{10}$";
        return Pattern.compile(phoneRegex).matcher(phone).matches();
    }

    private boolean isValidCPF(String cpf) {
        String cleanCpf = cpf.replaceAll("\\D", "");
        return cleanCpf.length() == 11 && !cleanCpf.matches("(\\d)\\1{10}");
    }

    private boolean isValidCNPJ(String cnpj) {
        String cleanCnpj = cnpj.replaceAll("\\D", "");
        return cleanCnpj.length() == 14 && !cleanCnpj.matches("(\\d)\\1{13}");
    }

    private boolean isValidEVP(String evp) {
        String uuidRegex = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
        return Pattern.compile(uuidRegex).matcher(evp).matches();
    }

    public void deactivate() {
        this.isActive = false;
    }

    public void activate() {
        this.isActive = true;
    }

    public UUID getId() {
        return id;
    }

    public String getKeyValue() {
        return keyValue;
    }

    public PixKeyType getKeyType() {
        return keyType;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public void setWallet(Wallet wallet) {
        this.wallet = wallet;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PixKey pixKey = (PixKey) o;
        return Objects.equals(keyValue, pixKey.keyValue) && keyType == pixKey.keyType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyValue, keyType);
    }

    @Override
    public String toString() {
        return "PixKey{" +
                "id=" + id +
                ", keyValue='" + keyValue + '\'' +
                ", keyType=" + keyType +
                ", isActive=" + isActive +
                '}';
    }
}