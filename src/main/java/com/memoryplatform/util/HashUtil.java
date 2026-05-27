package com.memoryplatform.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256哈希工具类
 * <p>
 * 用于记忆内容去重和导入比较。基于内容生成唯一哈希值，
 * 支持单字符串和多字段组合哈希。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 计算单个字符串的SHA-256
 * String hash = HashUtil.sha256("记忆内容");
 *
 * // 计算记忆内容哈希（组合字段）
 * String contentHash = HashUtil.memoryContentHash("用户偏好：咖啡", "user123", "agent456");
 *
 * // 验证哈希是否匹配
 * boolean isDuplicate = HashUtil.matches(contentHash, existingHash);
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 */
public final class HashUtil {

    /** SHA-256算法名称 */
    private static final String ALGORITHM = "SHA-256";

    /** 十六进制字符集 */
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    // 私有构造，防止实例化
    private HashUtil() {
    }

    /**
     * 计算字符串的SHA-256哈希值
     *
     * @param input 输入字符串
     * @return 64位十六进制哈希字符串，输入为null时返回null
     */
    public static String sha256(String input) {
        if (input == null) {
            return null;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256是JDK标准算法，理论上不会发生
            throw new RuntimeException("SHA-256算法不可用", e);
        }
    }

    /**
     * 计算字节数组的SHA-256哈希值
     *
     * @param input 输入字节数组
     * @return 64位十六进制哈希字符串，输入为null时返回null
     */
    public static String sha256Bytes(byte[] input) {
        if (input == null) {
            return null;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hashBytes = digest.digest(input);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256算法不可用", e);
        }
    }

    /**
     * 计算记忆内容的哈希值
     * <p>
     * 组合text、userId、agentId三个字段生成唯一哈希，
     * 用于导入时的去重判断。相同内容+相同用户+相同Agent = 相同哈希。
     * </p>
     *
     * @param text    记忆文本内容
     * @param userId  用户ID
     * @param agentId Agent ID（可为null）
     * @return 64位十六进制哈希字符串
     */
    public static String memoryContentHash(String text, String userId, String agentId) {
        StringBuilder sb = new StringBuilder();
        sb.append(text != null ? text : "");
        sb.append("||");
        sb.append(userId != null ? userId : "");
        sb.append("||");
        sb.append(agentId != null ? agentId : "");
        return sha256(sb.toString());
    }

    /**
     * 验证两个哈希值是否匹配
     * <p>
     * 使用恒定时间比较防止时序攻击。
     * </p>
     *
     * @param hash1 哈希值1
     * @param hash2 哈希值2
     * @return 是否匹配
     */
    public static boolean matches(String hash1, String hash2) {
        if (hash1 == null || hash2 == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash1.getBytes(StandardCharsets.UTF_8),
                hash2.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 字节数组转十六进制字符串
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }
}
