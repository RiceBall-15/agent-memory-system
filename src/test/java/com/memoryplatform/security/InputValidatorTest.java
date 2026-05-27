package com.memoryplatform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InputValidator 单元测试
 * 覆盖：userId/agentId验证、消息验证、HTML转义、sanitizeForLog、边界条件
 */
class InputValidatorTest {

    // ==================== validateUserId ====================

    @Nested
    @DisplayName("validateUserId 测试")
    class ValidateUserIdTests {

        @Test
        @DisplayName("有效userId - 纯字母")
        void validUserId_alphanumeric() {
            var result = InputValidator.validateUserId("alice");
            assertTrue(result.isValid());
            assertTrue(result.getErrors().isEmpty());
        }

        @Test
        @DisplayName("有效userId - 字母数字下划线混合")
        void validUserId_mixed() {
            var result = InputValidator.validateUserId("user_123_agent");
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("有效userId - 单字符")
        void validUserId_singleChar() {
            var result = InputValidator.validateUserId("a");
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("有效userId - 最大长度64")
        void validUserId_maxLength() {
            String id = "a".repeat(64);
            var result = InputValidator.validateUserId(id);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("null userId - 无效")
        void nullUserId_invalid() {
            var result = InputValidator.validateUserId(null);
            assertFalse(result.isValid());
            assertEquals(1, result.getErrors().size());
            assertTrue(result.getErrors().get(0).contains("不能为空"));
        }

        @Test
        @DisplayName("空字符串userId - 无效")
        void emptyUserId_invalid() {
            var result = InputValidator.validateUserId("");
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("userId含特殊字符 - 无效")
        void userId_withSpecialChars_invalid() {
            var result = InputValidator.validateUserId("user@#$%");
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream()
                    .anyMatch(e -> e.contains("字母、数字和下划线")));
        }

        @Test
        @DisplayName("userId含空格 - 无效")
        void userId_withSpace_invalid() {
            var result = InputValidator.validateUserId("user name");
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("userId超长 - 无效")
        void userId_tooLong_invalid() {
            String id = "a".repeat(65);
            var result = InputValidator.validateUserId(id);
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream()
                    .anyMatch(e -> e.contains("长度不能超过")));
        }

        @Test
        @DisplayName("userId含中文 - 无效")
        void userId_withChinese_invalid() {
            var result = InputValidator.validateUserId("用户123");
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("userId含连字符 - 无效")
        void userId_withHyphen_invalid() {
            var result = InputValidator.validateUserId("user-name");
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("userId同时超长和含特殊字符 - 多个错误")
        void userId_multipleErrors() {
            String id = "a@".repeat(40); // 80 chars, contains @
            var result = InputValidator.validateUserId(id);
            assertFalse(result.isValid());
            assertTrue(result.getErrors().size() >= 2);
        }
    }

    // ==================== validateAgentId ====================

    @Nested
    @DisplayName("validateAgentId 测试")
    class ValidateAgentIdTests {

        @Test
        @DisplayName("有效agentId")
        void validAgentId() {
            var result = InputValidator.validateAgentId("agent_01");
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("null agentId - 无效")
        void nullAgentId_invalid() {
            var result = InputValidator.validateAgentId(null);
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("空agentId - 无效")
        void emptyAgentId_invalid() {
            var result = InputValidator.validateAgentId("");
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("agentId含特殊字符 - 无效")
        void agentId_withSpecialChars_invalid() {
            var result = InputValidator.validateAgentId("agent/1");
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("agentId超长 - 无效")
        void agentId_tooLong_invalid() {
            var result = InputValidator.validateAgentId("a".repeat(65));
            assertFalse(result.isValid());
        }
    }

    // ==================== validateMessage ====================

    @Nested
    @DisplayName("validateMessage 测试")
    class ValidateMessageTests {

        @Test
        @DisplayName("有效消息")
        void validMessage() {
            var result = InputValidator.validateMessage("Hello World");
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("null消息 - 无效")
        void nullMessage_invalid() {
            var result = InputValidator.validateMessage(null);
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("空消息 - 无效")
        void emptyMessage_invalid() {
            var result = InputValidator.validateMessage("");
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("消息超长 - 无效")
        void message_tooLong_invalid() {
            String msg = "x".repeat(10001);
            var result = InputValidator.validateMessage(msg);
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream()
                    .anyMatch(e -> e.contains("长度不能超过")));
        }

        @Test
        @DisplayName("消息刚好最大长度 - 有效")
        void message_exactlyMax_valid() {
            String msg = "x".repeat(10000);
            var result = InputValidator.validateMessage(msg);
            assertTrue(result.isValid());
        }
    }

    // ==================== validateMessageOptional ====================

    @Nested
    @DisplayName("validateMessageOptional 测试")
    class ValidateMessageOptionalTests {

        @Test
        @DisplayName("null消息 - 有效（可选字段）")
        void nullMessage_valid() {
            var result = InputValidator.validateMessageOptional(null);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("空消息 - 有效（可选字段）")
        void emptyMessage_valid() {
            var result = InputValidator.validateMessageOptional("");
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("超长消息 - 无效")
        void tooLongMessage_invalid() {
            var result = InputValidator.validateMessageOptional("x".repeat(10001));
            assertFalse(result.isValid());
        }
    }

    // ==================== validateStringParam ====================

    @Nested
    @DisplayName("validateStringParam 测试")
    class ValidateStringParamTests {

        @Test
        @DisplayName("null参数 - 有效")
        void nullParam_valid() {
            var result = InputValidator.validateStringParam(null, "test");
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("正常长度参数 - 有效")
        void normalParam_valid() {
            var result = InputValidator.validateStringParam("hello", "test");
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("超长参数 - 无效")
        void tooLongParam_invalid() {
            var result = InputValidator.validateStringParam("x".repeat(513), "myParam");
            assertFalse(result.isValid());
            assertTrue(result.getErrors().get(0).contains("myParam"));
        }
    }

    // ==================== validateRequired ====================

    @Nested
    @DisplayName("validateRequired 测试")
    class ValidateRequiredTests {

        @Test
        @DisplayName("有效值 - 有效")
        void validValue() {
            var result = InputValidator.validateRequired("hello", "name");
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("null值 - 无效")
        void nullValue_invalid() {
            var result = InputValidator.validateRequired(null, "name");
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("空白值 - 无效")
        void blankValue_invalid() {
            var result = InputValidator.validateRequired("   ", "name");
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("超长值 - 无效")
        void tooLongValue_invalid() {
            var result = InputValidator.validateRequired("x".repeat(513), "name");
            assertFalse(result.isValid());
        }
    }

    // ==================== escapeHtml ====================

    @Nested
    @DisplayName("escapeHtml 测试")
    class EscapeHtmlTests {

        @Test
        @DisplayName("null文本 - 返回null")
        void nullText_returnsNull() {
            assertNull(InputValidator.escapeHtml(null));
        }

        @Test
        @DisplayName("普通文本不转义")
        void plainText_noEscaping() {
            assertEquals("hello world", InputValidator.escapeHtml("hello world"));
        }

        @Test
        @DisplayName("转义 & 字符")
        void escapeAmpersand() {
            assertEquals("a&amp;b", InputValidator.escapeHtml("a&b"));
        }

        @Test
        @DisplayName("转义 < 字符")
        void escapeLessThan() {
            assertEquals("&lt;script&gt;", InputValidator.escapeHtml("<script>"));
        }

        @Test
        @DisplayName("转义 > 字符")
        void escapeGreaterThan() {
            assertEquals("a&gt;b", InputValidator.escapeHtml("a>b"));
        }

        @Test
        @DisplayName("转义双引号")
        void escapeDoubleQuote() {
            assertEquals("&quot;hello&quot;", InputValidator.escapeHtml("\"hello\""));
        }

        @Test
        @DisplayName("转义单引号")
        void escapeSingleQuote() {
            assertEquals("&#x27;test&#x27;", InputValidator.escapeHtml("'test'"));
        }

        @Test
        @DisplayName("XSS攻击字符串完全转义")
        void xssAttackString_fullyEscaped() {
            String xss = "<script>alert('xss')</script>";
            String escaped = InputValidator.escapeHtml(xss);
            assertFalse(escaped.contains("<"));
            assertFalse(escaped.contains(">"));
            assertFalse(escaped.contains("'"));
            assertTrue(escaped.contains("&lt;script&gt;"));
        }

        @Test
        @DisplayName("混合特殊字符转义")
        void mixedSpecialChars() {
            String input = "a&b<c>d\"e'f";
            String expected = "a&amp;b&lt;c&gt;d&quot;e&#x27;f";
            assertEquals(expected, InputValidator.escapeHtml(input));
        }

        @Test
        @DisplayName("空字符串 - 原样返回")
        void emptyString_returned() {
            assertEquals("", InputValidator.escapeHtml(""));
        }
    }

    // ==================== sanitizeForLog ====================

    @Nested
    @DisplayName("sanitizeForLog 测试")
    class SanitizeForLogTests {

        @Test
        @DisplayName("null - 返回字符串\"null\"")
        void null_returnsStringNull() {
            assertEquals("null", InputValidator.sanitizeForLog(null));
        }

        @Test
        @DisplayName("普通ID - 原样返回")
        void normalId_returned() {
            assertEquals("user123", InputValidator.sanitizeForLog("user123"));
        }

        @Test
        @DisplayName("含XSS字符的ID - 转义")
        void xssId_escaped() {
            assertEquals("a&lt;b", InputValidator.sanitizeForLog("a<b"));
        }
    }

    // ==================== ValidationResult ====================

    @Nested
    @DisplayName("ValidationResult 测试")
    class ValidationResultTests {

        @Test
        @DisplayName("getFirstError - 无错误返回null")
        void getFirstError_noErrors() {
            var result = InputValidator.validateUserId("alice");
            assertNull(result.getFirstError());
        }

        @Test
        @DisplayName("getFirstError - 有错误返回第一条")
        void getFirstError_withErrors() {
            var result = InputValidator.validateUserId(null);
            assertNotNull(result.getFirstError());
        }

        @Test
        @DisplayName("getErrorMessages - 多个错误以分号分隔")
        void getErrorMessages_multipleErrors() {
            var result = InputValidator.validateUserId("a@".repeat(40));
            String msgs = result.getErrorMessages();
            assertTrue(msgs.contains(";"));
        }

        @Test
        @DisplayName("toString - 有效结果")
        void toString_valid() {
            var result = InputValidator.validateUserId("alice");
            assertTrue(result.toString().contains("valid=true"));
        }

        @Test
        @DisplayName("toString - 无效结果含错误")
        void toString_invalid() {
            var result = InputValidator.validateUserId(null);
            assertTrue(result.toString().contains("valid=false"));
        }
    }
}
