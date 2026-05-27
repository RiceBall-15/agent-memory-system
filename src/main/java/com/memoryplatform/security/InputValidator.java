package com.memoryplatform.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 输入验证器
 * <p>
 * 提供统一的输入验证功能，包括：
 * <ul>
 *   <li>userId/agentId 格式校验（仅允许字母数字下划线，最大64字符）</li>
 *   <li>消息内容长度限制（最大10000字符）</li>
 *   <li>XSS防护（HTML转义）</li>
 *   <li>通用字符串参数校验</li>
 * </ul>
 * </p>
 * 
 * @author MemoryPlatform
 * @version 1.0
 */
public class InputValidator {

    /** 用户ID/代理ID合法字符正则：仅字母、数字、下划线 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    /** 最大ID长度 */
    private static final int MAX_ID_LENGTH = 64;

    /** 最大消息内容长度 */
    private static final int MAX_MESSAGE_LENGTH = 10000;

    /** 最大通用参数长度 */
    private static final int MAX_PARAM_LENGTH = 512;

    /**
     * 验证用户ID格式
     * <p>userId只能包含字母、数字和下划线，长度1~64</p>
     *
     * @param userId 用户ID
     * @return 验证结果
     */
    public static ValidationResult validateUserId(String userId) {
        List<String> errors = new ArrayList<>();

        if (userId == null || userId.isEmpty()) {
            errors.add("userId不能为空");
            return new ValidationResult(false, errors);
        }

        if (userId.length() > MAX_ID_LENGTH) {
            errors.add("userId长度不能超过" + MAX_ID_LENGTH + "个字符");
        }

        if (!ID_PATTERN.matcher(userId).matches()) {
            errors.add("userId只能包含字母、数字和下划线");
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * 验证代理ID格式
     * <p>agentId只能包含字母、数字和下划线，长度1~64</p>
     *
     * @param agentId 代理ID
     * @return 验证结果
     */
    public static ValidationResult validateAgentId(String agentId) {
        List<String> errors = new ArrayList<>();

        if (agentId == null || agentId.isEmpty()) {
            errors.add("agentId不能为空");
            return new ValidationResult(false, errors);
        }

        if (agentId.length() > MAX_ID_LENGTH) {
            errors.add("agentId长度不能超过" + MAX_ID_LENGTH + "个字符");
        }

        if (!ID_PATTERN.matcher(agentId).matches()) {
            errors.add("agentId只能包含字母、数字和下划线");
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * 验证消息内容
     * <p>消息不能为空，且长度不超过10000字符</p>
     *
     * @param message 消息内容
     * @return 验证结果
     */
    public static ValidationResult validateMessage(String message) {
        List<String> errors = new ArrayList<>();

        if (message == null || message.isEmpty()) {
            errors.add("消息内容不能为空");
            return new ValidationResult(false, errors);
        }

        if (message.length() > MAX_MESSAGE_LENGTH) {
            errors.add("消息内容长度不能超过" + MAX_MESSAGE_LENGTH + "个字符，当前长度：" + message.length());
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * 验证消息内容（可选字段）
     * <p>如果提供了消息，检查长度限制</p>
     *
     * @param message 消息内容
     * @return 验证结果
     */
    public static ValidationResult validateMessageOptional(String message) {
        List<String> errors = new ArrayList<>();

        if (message != null && !message.isEmpty() && message.length() > MAX_MESSAGE_LENGTH) {
            errors.add("消息内容长度不能超过" + MAX_MESSAGE_LENGTH + "个字符，当前长度：" + message.length());
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * 验证通用字符串参数
     *
     * @param value 参数值
     * @param paramName 参数名称（用于错误消息）
     * @return 验证结果
     */
    public static ValidationResult validateStringParam(String value, String paramName) {
        List<String> errors = new ArrayList<>();

        if (value != null && value.length() > MAX_PARAM_LENGTH) {
            errors.add(paramName + "长度不能超过" + MAX_PARAM_LENGTH + "个字符");
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * 验证非空字符串参数
     *
     * @param value 参数值
     * @param paramName 参数名称
     * @return 验证结果
     */
    public static ValidationResult validateRequired(String value, String paramName) {
        List<String> errors = new ArrayList<>();

        if (value == null || value.trim().isEmpty()) {
            errors.add(paramName + "不能为空");
        } else if (value.length() > MAX_PARAM_LENGTH) {
            errors.add(paramName + "长度不能超过" + MAX_PARAM_LENGTH + "个字符");
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * 对文本进行HTML转义，防止XSS攻击
     * <p>
     * 转义以下字符：
     * <ul>
     *   <li>& → &amp;</li>
     *   <li>< → &lt;</li>
     *   <li>> → &gt;</li>
     *   <li>" → &quot;</li>
     *   <li>' → &#x27;</li>
     * </ul>
     * </p>
     *
     * @param text 原始文本
     * @return 转义后的安全文本
     */
    public static String escapeHtml(String text) {
        if (text == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#x27;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 验证并转义用户ID（用于日志记录时安全输出）
     *
     * @param userId 用户ID
     * @return 转义后的ID，如果无效返回null
     */
    public static String sanitizeForLog(String userId) {
        if (userId == null) {
            return "null";
        }
        return escapeHtml(userId);
    }

    // ========== 验证结果类 ==========

    /**
     * 输入验证结果
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;

        public ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = Collections.unmodifiableList(errors);
        }

        /**
         * 验证是否通过
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * 获取错误消息列表
         */
        public List<String> getErrors() {
            return errors;
        }

        /**
         * 获取第一个错误消息
         */
        public String getFirstError() {
            return errors.isEmpty() ? null : errors.get(0);
        }

        /**
         * 获取所有错误消息，以分号分隔
         */
        public String getErrorMessages() {
            return String.join("; ", errors);
        }

        @Override
        public String toString() {
            if (valid) {
                return "ValidationResult{valid=true}";
            }
            return "ValidationResult{valid=false, errors=" + errors + "}";
        }
    }
}
