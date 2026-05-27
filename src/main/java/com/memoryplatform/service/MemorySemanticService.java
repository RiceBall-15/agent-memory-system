package com.memoryplatform.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.memoryplatform.llm.LlmClient;
import com.memoryplatform.llm.LlmClient.LlmException;
import com.memoryplatform.model.Memory;
import com.memoryplatform.model.Message;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记忆语义理解服务
 * <p>
 * 使用LLM对记忆进行语义分析，提取标签、分类和增强语义描述。
 * </p>
 * <ul>
 *   <li>记忆分类: FACTUAL / PROCEDURAL / EPISODIC / SEMANTIC</li>
 *   <li>自动标签提取: 从记忆内容中提取关键词作为标签</li>
 *   <li>语义向量增强: 使用LLM生成更精确的语义描述</li>
 * </ul>
 */
public class MemorySemanticService {

    /** 记忆分类枚举 */
    public enum MemoryCategory {
        FACTUAL,    // 事实性记忆(用户信息、偏好)
        PROCEDURAL, // 程序性记忆(操作步骤、工作流)
        EPISODIC,   // 情景性记忆(事件、对话)
        SEMANTIC    // 语义性记忆(知识、概念)
    }

    private final LlmClient llmClient;

    /** 统计: 分类数 */
    private final AtomicLong classificationCount = new AtomicLong(0);
    /** 统计: 标签提取数 */
    private final AtomicLong tagExtractionCount = new AtomicLong(0);
    /** 统计: 语义增强数 */
    private final AtomicLong semanticEnhancementCount = new AtomicLong(0);

    /** 分类结果缓存: memoryId -> category */
    private final ConcurrentHashMap<String, MemoryCategory> categoryCache = new ConcurrentHashMap<>();

    public MemorySemanticService(LlmClient llmClient) {
        this.llmClient = llmClient;
        System.out.println("[MemorySemanticService] 初始化完成");
    }

    /**
     * 对记忆进行完整的语义分析
     *
     * @param memory 待分析的记忆
     * @return 语义分析结果
     */
    public SemanticAnalysisResult analyze(Memory memory) {
        String text = memory.getText();
        if (text == null || text.isBlank()) {
            return new SemanticAnalysisResult(MemoryCategory.EPISODIC, List.of(), "");
        }

        MemoryCategory category = classify(text);
        List<String> tags = extractTags(text);
        String semanticDescription = enhanceSemantics(text, category);

        return new SemanticAnalysisResult(category, tags, semanticDescription);
    }

    /**
     * 记忆分类 - 使用LLM判断记忆类型
     *
     * @param text 记忆文本
     * @return 记忆分类
     */
    public MemoryCategory classify(String text) {
        try {
            String prompt = buildClassificationPrompt(text);
            List<Message> messages = List.of(
                new Message("system", "你是一个记忆分类专家。根据记忆内容判断其类型，只返回分类名称。"),
                new Message("user", prompt)
            );

            String response = llmClient.chat(messages);
            return parseCategory(response.trim());
        } catch (LlmException e) {
            System.err.println("[MemorySemanticService] LLM分类调用失败: " + e.getMessage());
            // 降级: 基于关键词的简单分类
            return classifyByKeywords(text);
        }
    }

    /**
     * 自动标签提取 - 从记忆内容中提取关键词
     *
     * @param text 记忆文本
     * @return 标签列表
     */
    public List<String> extractTags(String text) {
        try {
            String prompt = buildTagExtractionPrompt(text);
            List<Message> messages = List.of(
                new Message("system", "你是关键词提取专家。从文本中提取3-5个核心关键词/标签，用JSON数组格式返回。"),
                new Message("user", prompt)
            );

            String response = llmClient.chat(messages);
            List<String> tags = parseTags(response);
            tagExtractionCount.incrementAndGet();
            return tags;
        } catch (LlmException e) {
            System.err.println("[MemorySemanticService] LLM标签提取失败: " + e.getMessage());
            // 降级: 简单分词
            return extractSimpleTags(text);
        }
    }

    /**
     * 语义向量增强 - 使用LLM生成更精确的语义描述
     *
     * @param text     记忆文本
     * @param category 记忆分类
     * @return 增强后的语义描述
     */
    public String enhanceSemantics(String text, MemoryCategory category) {
        try {
            String prompt = buildEnhancementPrompt(text, category);
            List<Message> messages = List.of(
                new Message("system", "你是语义分析专家。请用一段简短的话概括这段记忆的核心语义，不超过50字。"),
                new Message("user", prompt)
            );

            String description = llmClient.chat(messages);
            semanticEnhancementCount.incrementAndGet();
            return description;
        } catch (LlmException e) {
            System.err.println("[MemorySemanticService] LLM语义增强失败: " + e.getMessage());
            // 降级: 截取原文前100字
            return text.length() > 100 ? text.substring(0, 100) + "..." : text;
        }
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("classificationCount", classificationCount.get());
        stats.put("tagExtractionCount", tagExtractionCount.get());
        stats.put("semanticEnhancementCount", semanticEnhancementCount.get());
        stats.put("cacheSize", categoryCache.size());
        return stats;
    }

    // ==================== Prompt构建 ====================

    private String buildClassificationPrompt(String text) {
        return "请判断以下记忆属于哪种类型：\n" +
               "- FACTUAL: 事实性记忆（用户信息、偏好、事实）\n" +
               "- PROCEDURAL: 程序性记忆（操作步骤、工作流、方法）\n" +
               "- EPISODIC: 情景性记忆（事件、对话、经历）\n" +
               "- SEMANTIC: 语义性记忆（知识、概念、定义）\n\n" +
               "记忆内容: \"" + text + "\"\n\n" +
               "只返回分类名称（FACTUAL/PROCEDURAL/EPISODIC/SEMANTIC）。";
    }

    private String buildTagExtractionPrompt(String text) {
        return "请从以下文本中提取3-5个核心关键词/标签：\n\n" +
               "\"" + text + "\"\n\n" +
               "返回JSON数组格式: [\"标签1\", \"标签2\", ...]";
    }

    private String buildEnhancementPrompt(String text, MemoryCategory category) {
        return "记忆类型: " + category + "\n" +
               "原始内容: \"" + text + "\"\n\n" +
               "请用一段简短的话概括这段记忆的核心语义。";
    }

    // ==================== 解析方法 ====================

    private MemoryCategory parseCategory(String response) {
        String upper = response.toUpperCase().trim();
        if (upper.contains("FACTUAL")) return MemoryCategory.FACTUAL;
        if (upper.contains("PROCEDURAL")) return MemoryCategory.PROCEDURAL;
        if (upper.contains("EPISODIC")) return MemoryCategory.EPISODIC;
        if (upper.contains("SEMANTIC")) return MemoryCategory.SEMANTIC;
        return MemoryCategory.EPISODIC; // 默认
    }

    private List<String> parseTags(String response) {
        List<String> tags = new ArrayList<>();
        try {
            // 尝试解析JSON数组
            String jsonStr = response;
            // 移除markdown代码块
            if (jsonStr.contains("```")) {
                jsonStr = jsonStr.replaceAll("```json\\s*", "").replaceAll("```\\s*", "");
            }
            // 找到 [ ... ] 部分
            int start = jsonStr.indexOf('[');
            int end = jsonStr.lastIndexOf(']');
            if (start >= 0 && end > start) {
                jsonStr = jsonStr.substring(start, end + 1);
            }
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(jsonStr).getAsJsonArray();
            for (JsonElement el : arr) {
                tags.add(el.getAsString().trim());
            }
        } catch (Exception e) {
            // JSON解析失败，按逗号分割
            String cleaned = response.replaceAll("[\\[\\]\"']", "").trim();
            for (String tag : cleaned.split(",")) {
                String t = tag.trim();
                if (!t.isEmpty()) tags.add(t);
            }
        }
        return tags;
    }

    // ==================== 降级方法 ====================

    /**
     * 基于关键词的简单分类（降级策略）
     */
    private MemoryCategory classifyByKeywords(String text) {
        String lower = text.toLowerCase();

        // 程序性关键词
        if (lower.contains("步骤") || lower.contains("流程") || lower.contains("操作") ||
            lower.contains("step") || lower.contains("procedure") || lower.contains("how to")) {
            classificationCount.incrementAndGet();
            return MemoryCategory.PROCEDURAL;
        }
        // 事实性关键词
        if (lower.contains("喜欢") || lower.contains("偏好") || lower.contains("名字") ||
            lower.contains("preference") || lower.contains("name") || lower.contains("birthday")) {
            classificationCount.incrementAndGet();
            return MemoryCategory.FACTUAL;
        }
        // 语义性关键词
        if (lower.contains("定义") || lower.contains("概念") || lower.contains("知识") ||
            lower.contains("definition") || lower.contains("concept") || lower.contains("知识")) {
            classificationCount.incrementAndGet();
            return MemoryCategory.SEMANTIC;
        }

        classificationCount.incrementAndGet();
        return MemoryCategory.EPISODIC;
    }

    /**
     * 简单标签提取（降级策略）
     */
    private List<String> extractSimpleTags(String text) {
        List<String> tags = new ArrayList<>();
        // 简单按标点和空格分词，取前5个
        String[] tokens = text.split("[\\s,，。！？、；：""''\\-]+");
        Set<String> seen = new HashSet<>();
        for (String token : tokens) {
            String t = token.trim();
            if (t.length() >= 2 && !seen.contains(t) && tags.size() < 5) {
                tags.add(t);
                seen.add(t);
            }
        }
        tagExtractionCount.incrementAndGet();
        return tags;
    }

    /**
     * 语义分析结果
     */
    public static class SemanticAnalysisResult {
        private final MemoryCategory category;
        private final List<String> tags;
        private final String semanticDescription;

        public SemanticAnalysisResult(MemoryCategory category, List<String> tags,
                                       String semanticDescription) {
            this.category = category;
            this.tags = tags;
            this.semanticDescription = semanticDescription;
        }

        public MemoryCategory getCategory() { return category; }
        public List<String> getTags() { return tags; }
        public String getSemanticDescription() { return semanticDescription; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("category", category.name());
            map.put("tags", tags);
            map.put("semanticDescription", semanticDescription);
            return map;
        }
    }
}
