package com.smartpark;

import com.smartpark.common.PageSupport;
import com.smartpark.security.crypto.SensitiveCrypto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感字段加密组件冒烟测试（裁决 17）：加解密可逆、hash 确定性、掩码正确、分页收敛。
 */
class SensitiveCryptoTest {

    private SensitiveCrypto crypto() {
        SensitiveCrypto crypto = new SensitiveCrypto();
        crypto.setKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        crypto.setHmacKey("unit-test-hmac-key");
        crypto.init();
        return crypto;
    }

    @Test
    void encryptThenDecryptShouldReturnPlainText() {
        SensitiveCrypto crypto = crypto();
        String plain = "110101199003077733";
        String cipher = crypto.encrypt(plain);
        assertNotEquals(plain, cipher, "密文不应等于明文");
        assertEquals(plain, crypto.decrypt(cipher));
    }

    @Test
    void samePlainTextProducesDeterministicHashButRandomCipher() {
        SensitiveCrypto crypto = crypto();
        String plain = "13800138001";
        assertEquals(crypto.hash(plain), crypto.hash(plain), "hash 必须确定性（用于等值查询）");
        assertNotEquals(crypto.encrypt(plain), crypto.encrypt(plain), "GCM 随机 IV，密文不应重复");
        assertEquals(64, crypto.hash(plain).length());
    }

    @Test
    void maskShouldHideMiddlePart() {
        SensitiveCrypto crypto = crypto();
        assertEquals("110101********7733", crypto.maskIdCard("110101199003077733"));
        assertEquals("138****8001", crypto.maskPhone("13800138001"));
        assertTrue(SensitiveCrypto.looksMasked("110101********7733"), "掩码值必须能被识别（B05 防护依据）");
    }

    @Test
    void pageSizeShouldBeClamped() {
        assertEquals(200, PageSupport.clampPageSize(100000));
        assertEquals(10, PageSupport.clampPageSize(null));
        assertEquals(1, PageSupport.clampPageNum(0));
    }
}
