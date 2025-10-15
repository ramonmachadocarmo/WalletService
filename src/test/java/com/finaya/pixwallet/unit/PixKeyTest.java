package com.finaya.pixwallet.unit;

import com.finaya.pixwallet.domain.entity.PixKey;
import com.finaya.pixwallet.domain.entity.PixKeyType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PixKeyTest {

    @Test
    void shouldCreateValidEmailPixKey() {
        String email = "user@example.com";

        PixKey pixKey = new PixKey(email, PixKeyType.EMAIL);

        assertEquals(email, pixKey.getKeyValue());
        assertEquals(PixKeyType.EMAIL, pixKey.getKeyType());
        assertTrue(pixKey.getIsActive());
        assertNotNull(pixKey.getCreatedAt());
    }

    @Test
    void shouldCreateValidPhonePixKey() {
        String phone = "+5511999999999";

        PixKey pixKey = new PixKey(phone, PixKeyType.PHONE);

        assertEquals(phone, pixKey.getKeyValue());
        assertEquals(PixKeyType.PHONE, pixKey.getKeyType());
        assertTrue(pixKey.getIsActive());
    }

    @Test
    void shouldCreateValidCPFPixKey() {
        String cpf = "12345678901";

        PixKey pixKey = new PixKey(cpf, PixKeyType.CPF);

        assertEquals(cpf, pixKey.getKeyValue());
        assertEquals(PixKeyType.CPF, pixKey.getKeyType());
        assertTrue(pixKey.getIsActive());
    }

    @Test
    void shouldCreateValidEVPPixKey() {
        String evp = "550e8400-e29b-41d4-a716-446655440000";

        PixKey pixKey = new PixKey(evp, PixKeyType.EVP);

        assertEquals(evp, pixKey.getKeyValue());
        assertEquals(PixKeyType.EVP, pixKey.getKeyType());
        assertTrue(pixKey.getIsActive());
    }

    @Test
    void shouldNotCreatePixKeyWithInvalidEmail() {
        String invalidEmail = "invalid-email";

        assertThrows(IllegalArgumentException.class,
            () -> new PixKey(invalidEmail, PixKeyType.EMAIL));
    }

    @Test
    void shouldNotCreatePixKeyWithInvalidPhone() {
        String invalidPhone = "123456";

        assertThrows(IllegalArgumentException.class,
            () -> new PixKey(invalidPhone, PixKeyType.PHONE));
    }

    @Test
    void shouldNotCreatePixKeyWithInvalidEVP() {
        String invalidEvp = "invalid-uuid";

        assertThrows(IllegalArgumentException.class,
            () -> new PixKey(invalidEvp, PixKeyType.EVP));
    }

    @Test
    void shouldNotCreatePixKeyWithNullValue() {
        assertThrows(NullPointerException.class,
            () -> new PixKey(null, PixKeyType.EMAIL));
    }

    @Test
    void shouldNotCreatePixKeyWithNullType() {
        assertThrows(NullPointerException.class,
            () -> new PixKey("user@example.com", null));
    }

    @Test
    void shouldNotCreatePixKeyWithEmptyValue() {
        assertThrows(IllegalArgumentException.class,
            () -> new PixKey("", PixKeyType.EMAIL));
    }

    @Test
    void shouldDeactivatePixKey() {
        PixKey pixKey = new PixKey("user@example.com", PixKeyType.EMAIL);

        pixKey.deactivate();

        assertFalse(pixKey.getIsActive());
    }

    @Test
    void shouldActivatePixKey() {
        PixKey pixKey = new PixKey("user@example.com", PixKeyType.EMAIL);
        pixKey.deactivate();

        pixKey.activate();

        assertTrue(pixKey.getIsActive());
    }
}