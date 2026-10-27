package com.smartpark.security.crypto;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段（身份证 / 手机号）加密与检索支撑：
 * <ul>
 *   <li>AES-256-GCM 加密存储（随机 12 字节 IV，密文含认证标签），禁用 MySQL AES_ENCRYPT()</li>
 *   <li>HMAC-SHA256 生成确定性 hash 列，用于精确查询（等值检索走 hash 列，不解密全表）</li>
 *   <li>掩码列用于列表展示，接口不再返回明文</li>
 * </ul>
 * 密钥来自环境变量 SMARTPARK_CRYPTO_KEY / SMARTPARK_CRYPTO_HMAC_KEY，缺失或非法时启动 fail-fast。
 */
@Slf4j
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "smartpark.crypto")
public class SensitiveCrypto {

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** AES-256 密钥（Base64 编码，解码后 32 字节） */
    private String key;

    /** HMAC-SHA256 密钥（任意长度字符串） */
    private String hmacKey;

    private SecretKeySpec aesKey;
    private SecretKeySpec macKey;

    /** 校验并初始化密钥（由 @PostConstruct 调用） */
    @jakarta.annotation.PostConstruct
    public void init() {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("缺少配置项 smartpark.crypto.key（环境变量 SMARTPARK_CRYPTO_KEY），拒绝启动");
        }
        if (hmacKey == null || hmacKey.isBlank()) {
            throw new IllegalStateException("缺少配置项 smartpark.crypto.hmac-key（环境变量 SMARTPARK_CRYPTO_HMAC_KEY），拒绝启动");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("smartpark.crypto.key 必须是 Base64 编码的 32 字节密钥", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException("smartpark.crypto.key 解码后长度必须为 32 字节（AES-256），当前：" + keyBytes.length);
        }
        this.aesKey = new SecretKeySpec(keyBytes, "AES");
        this.macKey = new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        log.info("敏感字段加密组件已就绪（AES-256-GCM + HMAC-SHA256）");
    }

    /** 加密：返回 Base64(IV + 密文+TAG)；空值原样返回 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, out, IV_LENGTH, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /** 解密：输入必须是 encrypt() 的输出；解密失败抛异常（不允许静默返回空） */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            if (all.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度非法");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段解密失败（密钥变更或数据损坏）", e);
        }
    }

    /** 确定性 hash（十六进制 64 位），用于等值查询 */
    public String hash(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            return toHex(mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段 hash 计算失败", e);
        }
    }

    /** 身份证掩码：前 6 后 4，中间 8 位星号 */
    public String maskIdCard(String idCard) {
        if (idCard == null || idCard.isEmpty()) {
            return idCard;
        }
        if (idCard.length() < 10) {
            return "****";
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(idCard.length() - 4);
    }

    /** 手机号掩码：前 3 后 4 */
    public String maskPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        if (phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 判断字符串是否已是掩码形态（含星号）——B05 三层防护后端侧依据 */
    public static boolean looksMasked(String value) {
        return value != null && value.contains("*");
    }
}
