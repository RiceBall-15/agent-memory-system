package com.memoryplatform.extractor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.memoryplatform.llm.LlmClient;
import com.memoryplatform.model.Entity;
import com.memoryplatform.model.EntityType;
import com.memoryplatform.model.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
/**
 * 实体提取器 - 从文本中提取结构化实体
 * <p>
 * 采用规则+LLM双重提取策略:
 * <ul>
 *   <li>规则提取: 正则匹配日期、人名模式、组织名称</li>
 *   <li>LLM提取: 让模型从文本中智能识别实体</li>
 *   <li>实体归一化: 同名实体合并, 取最高置信度</li>
 * </ul>
 */
@Slf4j
public class EntityExtractor {

    private static final Gson GSON = new Gson();

    // ==================== 规则提取正则 ====================

    /** 日期模式: 2024-01-15, 2024/01/15, 2024年1月15日 等 */
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}"           // 2024-01-15, 2024/01/15
            + "|\\d{4}年\\d{1,2}月\\d{1,2}日"             // 2024年1月15日
            + "|\\d{1,2}月\\d{1,2}[日号]"                  // 1月15日, 1月15号
            + "|\\d{1,2}月\\d{1,2}"                        // 1月15 (无日)
    );

    /** 时间模式: 2024-01-15 14:30:00 等 */
    private static final Pattern DATETIME_PATTERN = Pattern.compile(
            "\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}[\\sT]\\d{1,2}:\\d{2}(:\\d{2})?"
    );

    /** 中文人名模式: 2-4个汉字, 前面有特定前缀或在句子开头 */
    private static final Pattern PERSON_PREFIX = Pattern.compile(
            "(?:我叫|我叫|我的名字是|你好我叫|我是|他叫|她叫|请问[你您])\\s*([\\u4e00-\\u9fa5]{2,4})"
    );

    /** 常见人名姓氏开头的中文名 */
    private static final Pattern CHINESE_PERSON_NAME = Pattern.compile(
            "(?:^|[，,。！!？?、\\s])([\\u4e00-\\u9fa5](?:[\\u4e00-\\u9fa5]){1,3})(?:说|问|认为|提到|告诉我|帮我)"
    );

    /** 组织名称模式 */
    private static final Pattern ORG_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,10}(?:公司|集团|大学|学院|研究院|研究所|实验室|机构|组织|团队|部门|委员会|协会|基金会))"
            + "|(?:[A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)*(?:\\s+(?:Inc|Corp|Ltd|LLC|Co|Ltd|GmbH|SA|SAS|Pty))\\.?)"
    );

    /** 地点模式 */
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,8}(?:市|省|区|县|镇|村|路|街|大厦|广场|园区|中心))"
            + "|((?:北京|上海|广州|深圳|杭州|成都|武汉|南京|重庆|西安|苏州|天津|长沙|郑州|青岛|大连|厦门|宁波|无锡|佛山|东莞|珠海|中山|惠州|温州|嘉兴|绍兴|金华|台州|泉州|福州|南昌|贵阳|昆明|南宁|太原|石家庄|合肥|济南|哈尔滨|长春|沈阳|兰州|银川|西宁|呼和浩特|乌鲁木齐|拉萨|海口|三亚)(?:市|省)?)"
    );

    /** 技能/技术名词模式 */
    private static final Pattern SKILL_PATTERN = Pattern.compile(
            "\\b(Java|Python|JavaScript|TypeScript|Go|Rust|C\\+\\+|C#|Kotlin|Swift|Ruby|PHP|Scala|R语言"
            + "|Docker|Kubernetes|K8s|AWS|Azure|GCP|Redis|MySQL|PostgreSQL|MongoDB|Neo4j"
            + "|React|Vue|Angular|Spring|Django|Flask|Node\\.js|FastAPI|TensorFlow|PyTorch|LangChain"
            + "|GPT|LLM|NLP|AI|机器学习|深度学习|自然语言处理|计算机视觉|强化学习"
            + "|前端|后端|全栈|DevOps|大数据|微服务|分布式|云原生|区块链)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final LlmClient llmClient;

    /**
     * 构造函数
     * @param llmClient LLM客户端, 用于LLM实体提取
     */
    public EntityExtractor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 从文本中提取实体 - 规则+LLM双重提取
     * @param text 输入文本
     * @return 实体列表 (已归一化合并)
     */
    public List<Entity> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        log.info("[EntityExtractor] 开始提取实体, 文本长度=" + text.length())

        // 1. 规则提取
        List<Entity> ruleEntities = extractByRules(text);
        log.info("[EntityExtractor] 规则提取: " + ruleEntities.size() + " 个实体")

        // 2. LLM提取
        List<Entity> llmEntities = extractByLlm(text);
        log.info("[EntityExtractor] LLM提取: " + llmEntities.size() + " 个实体")

        // 3. 合并归一化
        List<Entity> merged = mergeEntities(ruleEntities, llmEntities);
        log.info("[EntityExtractor] 合并后: " + merged.size() + " 个实体")

        return merged;
    }

    /**
     * 从消息列表中提取实体
     * @param messages 消息列表
     * @return 实体列表
     */
    public List<Entity> extractFromMessages(List<Message> messages) {
        String combinedText = messages.stream()
                .map(Message::getContent)
                .collect(Collectors.joining("\n"));
        return extract(combinedText);
    }

    // ==================== 规则提取 ====================

    /**
     * 基于正则规则提取实体
     */
    private List<Entity> extractByRules(String text) {
        List<Entity> entities = new ArrayList<>();

        // 提取日期
        extractDates(text, entities);
        // 提取人名
        extractPersonNames(text, entities);
        // 提取组织
        extractOrganizations(text, entities);
        // 提取地点
        extractLocations(text, entities);
        // 提取技能
        extractSkills(text, entities);

        return entities;
    }

    private void extractDates(String text, List<Entity> entities) {
        Matcher m = DATETIME_PATTERN.matcher(text);
        while (m.find()) {
            entities.add(new Entity(m.group().trim(), EntityType.DATE, 0.95));
        }
        m = DATE_PATTERN.matcher(text);
        while (m.find()) {
            entities.add(new Entity(m.group().trim(), EntityType.DATE, 0.9));
        }
    }

    private void extractPersonNames(String text, List<Entity> entities) {
        // 带前缀的人名
        Matcher m = PERSON_PREFIX.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (isValidPersonName(name)) {
                entities.add(new Entity(name, EntityType.PERSON, 0.85));
            }
        }
        // 动词前的人名
        m = CHINESE_PERSON_NAME.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (isValidPersonName(name)) {
                entities.add(new Entity(name, EntityType.PERSON, 0.7));
            }
        }
    }

    private void extractOrganizations(String text, List<Entity> entities) {
        Matcher m = ORG_PATTERN.matcher(text);
        while (m.find()) {
            String org = m.group().trim();
            if (org.length() >= 4) {
                entities.add(new Entity(org, EntityType.ORG, 0.85));
            }
        }
    }

    private void extractLocations(String text, List<Entity> entities) {
        Matcher m = LOCATION_PATTERN.matcher(text);
        while (m.find()) {
            String loc = m.group().trim();
            if (loc.length() >= 2) {
                entities.add(new Entity(loc, EntityType.LOCATION, 0.8));
            }
        }
    }

    private void extractSkills(String text, List<Entity> entities) {
        Matcher m = SKILL_PATTERN.matcher(text);
        while (m.find()) {
            entities.add(new Entity(m.group(), EntityType.SKILL, 0.9));
        }
    }

    /**
     * 验证人名是否合理 (排除常见误匹配)
     */
    private boolean isValidPersonName(String name) {
        if (name == null || name.length() < 2 || name.length() > 4) return false;
        // 排除常见非人名词汇
        String[] blacklist = {"一个", "他们", "我们", "你们", "这个", "那个", "什么", "怎么", "为什么", "因为", "所以", "可以", "不能", "已经", "正在", "需要", "应该", "能够"};
        for (String b : blacklist) {
            if (name.equals(b)) return false;
        }
        return true;
    }

    // ==================== LLM提取 ====================

    private static final String ENTITY_EXTRACTION_PROMPT = """
你是一个实体提取专家。请从以下文本中提取所有有意义的实体。

实体类型说明:
- PERSON: 人名
- ORG: 组织/公司/机构名称
- PRODUCT: 产品名称
- LOCATION: 地点/地址
- DATE: 日期/时间
- PREFERENCE: 用户偏好
- SKILL: 技能/技术
- PROJECT: 项目名称
- TOPIC: 话题/主题
- EMOTION: 情感表达

请严格以JSON数组格式输出，每个实体包含name、type、confidence字段。
confidence取值范围0-1，表示你对该实体识别的置信度。
如果文本中没有可识别的实体，返回空数组[]。

文本:
%s

输出格式 (JSON数组):
[{"name": "实体名", "type": "实体类型", "confidence": 0.9}]
""";

    /**
     * 基于LLM提取实体
     */
    private List<Entity> extractByLlm(String text) {
        if (llmClient == null) {
            log.info("[EntityExtractor] LLM客户端未配置, 跳过LLM提取")
            return List.of();
        }

        try {
            List<Message> messages = List.of(
                    new Message("system", "你是一个精准的实体提取引擎, 只输出JSON格式的实体列表, 不要输出任何其他内容。"),
                    new Message("user", String.format(ENTITY_EXTRACTION_PROMPT, text))
            );

            String response = llmClient.chat(messages);
            return parseLlmEntities(response);
        } catch (LlmClient.LlmException e) {
            log.info("[EntityExtractor] LLM提取失败: " + e.getMessage())
            return List.of();
        } catch (Exception e) {
            log.info("[EntityExtractor] LLM提取异常: " + e.getMessage())
            return List.of();
        }
    }

    /**
     * 解析LLM返回的实体JSON
     */
    private List<Entity> parseLlmEntities(String response) {
        List<Entity> entities = new ArrayList<>();
        if (response == null || response.isBlank()) return entities;

        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonArray arr = JsonParser.parseString(jsonStr).getAsJsonArray();

            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String name = obj.has("name") ? obj.get("name").getAsString() : "";
                String typeStr = obj.has("type") ? obj.get("type").getAsString() : "TOPIC";
                double confidence = obj.has("confidence") ? obj.get("confidence").getAsDouble() : 0.7;

                EntityType type = parseEntityType(typeStr);
                if (!name.isBlank() && confidence > 0.3) {
                    entities.add(new Entity(name.trim(), type, Math.min(confidence + 0.1, 1.0)));
                }
            }
        } catch (Exception e) {
            log.info("[EntityExtractor] LLM响应解析失败: " + e.getMessage())
        }

        return entities;
    }

    /**
     * 从响应中提取JSON
     */
    private String extractJsonFromResponse(String text) {
        // 提取代码块中的JSON
        java.util.regex.Matcher m = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```").matcher(text);
        if (m.find()) return m.find() ? m.group(1).trim() : m.group(0).trim();

        // 找到第一个 [ 或 {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == '{') return text.substring(i).trim();
        }
        return text;
    }

    /**
     * 解析实体类型字符串
     */
    private EntityType parseEntityType(String typeStr) {
        if (typeStr == null) return EntityType.TOPIC;
        try {
            return EntityType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            // 模糊匹配
            String lower = typeStr.toLowerCase();
            for (EntityType t : EntityType.values()) {
                if (t.name().toLowerCase().contains(lower) || t.getDisplayName().contains(typeStr)) {
                    return t;
                }
            }
            return EntityType.TOPIC;
        }
    }

    // ==================== 实体归一化 ====================

    /**
     * 合并规则提取和LLM提取的实体
     * 相同normalizedId的实体合并, 取最高置信度
     */
    private List<Entity> mergeEntities(List<Entity> ruleEntities, List<Entity> llmEntities) {
        // 使用LinkedHashMap保持插入顺序, 以normalizedId为key
        Map<String, Entity> merged = new LinkedHashMap<>();

        // 先加入规则提取的实体 (精确度高)
        for (Entity e : ruleEntities) {
            merged.merge(e.getNormalizedId(), e, (existing, newEntity) -> {
                if (newEntity.getConfidence() > existing.getConfidence()) {
                    return newEntity;
                }
                return existing;
            });
        }

        // 再加入LLM提取的实体 (补充遗漏)
        for (Entity e : llmEntities) {
            merged.merge(e.getNormalizedId(), e, (existing, newEntity) -> {
                // 合并置信度: 取最高值 + 小幅提升 (因为两方都识别到)
                double combinedConfidence = Math.min(
                        Math.max(existing.getConfidence(), newEntity.getConfidence()) + 0.05,
                        1.0
                );
                return new Entity(existing.getName(), existing.getType(), combinedConfidence);
            });
        }

        return new ArrayList<>(merged.values());
    }
}
