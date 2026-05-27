package com.memoryplatform.service;

import com.memoryplatform.model.Message;

import java.util.List;

/**
 * 记忆提取提示词模板 - Additive Extraction策略
 * <p>
 * Additive Extraction是一种增量提取策略:
 * <ul>
 *   <li>不试图一次性从整段对话中提取所有信息</li>
 *   <li>每次只提取新的、之前未记录的关键信息</li>
 *   <li>避免重复提取已有记忆</li>
 *   <li>注重提取用户偏好、事实、关系等结构化信息</li>
 * </ul>
 * <p>
 * 输出格式: JSON数组
 * <pre>
 * [
 *   {
 *     "text": "提取的记忆文本",
 *     "entities": [
 *       {"name": "实体名", "type": "实体类型", "confidence": 0.9}
 *     ],
 *     "importance": 0.8
 *   }
 * ]
 * </pre>
 */
public class AdditiveExtractionPrompt {

    private static final String SYSTEM_PROMPT = """
你是一个精准的记忆提取引擎。你的任务是从对话文本中提取值得记住的关键信息。

## 提取原则
1. 只提取**新的、具体的**事实信息, 忽略闲聊和无关内容
2. 每条记忆应该是**独立的、完整的**信息单元
3. 优先提取: 用户偏好、个人事实、重要事件、实体关系、技能经验
4. 忽略: 问候语、确认语、重复信息、模糊表达
5. 保持原文表述, 不要过度概括或推断

## 记忆类型示例
- 事实: "用户叫张三"、"用户在北京工作"
- 偏好: "用户喜欢Python"、"用户偏好深色主题"
- 技能: "用户擅长React前端开发"
- 关系: "张三是用户的同事"
- 事件: "用户上周参加了一个技术会议"
- 项目: "用户正在开发一个记忆平台"

## 输出格式
严格以JSON数组格式输出, 每条记忆包含:
- text: 记忆文本 (简洁准确)
- entities: 相关实体列表
- importance: 重要性分数 (0-1)

如果没有值得提取的信息, 返回空数组: []
""";

    /**
     * 构建记忆提取的完整消息列表
     *
     * @param messages     对话消息列表
     * @param userId       用户ID
     * @param agentId      Agent ID
     * @param existingContext 已有记忆上下文 (可为null)
     * @return 发送给LLM的消息列表
     */
    public static List<Message> buildExtractionPrompt(
            List<Message> messages,
            String userId,
            String agentId,
            String existingContext
    ) {
        StringBuilder userPrompt = new StringBuilder();

        // 添加已有记忆上下文
        if (existingContext != null && !existingContext.isBlank()) {
            userPrompt.append("## 已有记忆 (请避免重复提取)\n");
            userPrompt.append(existingContext).append("\n\n");
        }

        // 添加对话内容
        userPrompt.append("## 对话内容\n");
        for (Message msg : messages) {
            String roleDisplay = "user".equals(msg.getRole()) ? "用户" : "助手";
            userPrompt.append(roleDisplay).append(": ").append(msg.getContent()).append("\n");
        }

        userPrompt.append("\n## 要求\n");
        userPrompt.append("请从上述对话中提取值得记住的关键信息。");
        userPrompt.append("每条记忆应该是独立的、有价值的信息单元。\n");
        userPrompt.append("严格以JSON数组格式输出, 不要输出任何其他内容。\n");

        return List.of(
                new Message("system", SYSTEM_PROMPT),
                new Message("user", userPrompt.toString())
        );
    }

    /**
     * 构建单条消息的记忆提取提示词
     *
     * @param message      单条消息
     * @param userId       用户ID
     * @param agentId      Agent ID
     * @return 发送给LLM的消息列表
     */
    public static List<Message> buildSingleMessagePrompt(
            Message message,
            String userId,
            String agentId
    ) {
        String roleDisplay = "user".equals(message.getRole()) ? "用户" : "助手";
        String content = roleDisplay + ": " + message.getContent();

        String userPrompt = "## 要提取的消息\n" + content
                + "\n\n## 要求\n"
                + "请从这条消息中提取值得记住的关键信息。\n"
                + "如果消息中没有有价值的信息, 返回空数组: []\n"
                + "严格以JSON数组格式输出, 不要输出任何其他内容。\n";

        return List.of(
                new Message("system", SYSTEM_PROMPT),
                new Message("user", userPrompt.toString())
        );
    }

    /**
     * 获取系统提示词 (供外部使用)
     * @return 系统提示词文本
     */
    public static String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * 合并消息列表为纯文本
     * @param messages 消息列表
     * @return 合并后的文本
     */
    public static String messagesToText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }
}
