package com.memoryplatform.extractor;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间解析器 - 支持中文相对时间和绝对时间
 * <p>
 * 支持的相对时间:
 * <ul>
 *   <li>"昨天"、"前天"、"大前天"</li>
 *   <li>"上周"、"上上周"、"这周"、"下周"</li>
 *   <li>"上个月"、"上上个月"、"这个月"、"下个月"</li>
 *   <li>"去年"、"前年"、"今年"、"明年"</li>
 *   <li>"3天前"、"2小时前"、"30分钟前"、"5秒前"</li>
 *   <li>"3天后"、"2小时后"、"30分钟后"</li>
 *   <li>"刚才"、"现在"、"之前"、"最近"</li>
 * </ul>
 * <p>
 * 支持的绝对时间:
 * <ul>
 *   <li>"2024-01-15"、"2024/01/15"、"2024年1月15日"</li>
 *   <li>"1月15日"、"1月15号" (默认当前年份)</li>
 *   <li>"2024-01-15 14:30:00"、"2024-01-15T14:30:00"</li>
 *   <li>"14:30"、"下午2点"、"上午10点"</li>
 * </ul>
 * <p>
 * 所有解析结果统一归一化到UTC的Instant
 */
public class TimeParser {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    // ==================== 相对时间正则 ====================

    /** N天/小时/分钟/秒 前/后 */
    private static final Pattern RELATIVE_UNIT = Pattern.compile(
            "(\\d+)\\s*(天|日|小时|时|h|分钟|分|min|秒|秒|s)(?:前|之前|以前|ago|前)"
            + "|(\\d+)\\s*(天|日|小时|时|h|分钟|分|min|秒|秒|s)(?:后|之后|以后|later|后)"
    );

    /** N周前/后 */
    private static final Pattern RELATIVE_WEEKS = Pattern.compile(
            "(\\d+)\\s*(?:个)?周(?:前|之前|以前|ago|前)|(\\d+)\\s*(?:个)?周(?:后|之后|以后|later|后)"
    );

    /** N个月前/后 */
    private static final Pattern RELATIVE_MONTHS = Pattern.compile(
            "(\\d+)\\s*(?:个)?月(?:前|之前|以前|ago|前)|(\\d+)\\s*(?:个)?月(?:后|之后|以后|later|后)"
    );

    /** N年前/后 */
    private static final Pattern RELATIVE_YEARS = Pattern.compile(
            "(\\d+)\\s*年(?:前|之前|以前|ago|前)|(\\d+)\\s*年(?:后|之后|以后|later|后)"
    );

    // ==================== 绝对时间正则 ====================

    /** YYYY-MM-DD HH:mm:ss 或 YYYY-MM-DDTHH:mm:ss */
    private static final Pattern FULL_DATETIME = Pattern.compile(
            "(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[\\sT](\\d{1,2}):(\\d{2})(?::(\\d{2}))?"
    );

    /** YYYY-MM-DD 或 YYYY/MM/DD 或 YYYY年M月D日 */
    private static final Pattern DATE_ONLY = Pattern.compile(
            "(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})"
            + "|(\\d{4})年(\\d{1,2})月(\\d{1,2})[日号]?"
    );

    /** M月D日 或 M月D号 */
    private static final Pattern SHORT_DATE = Pattern.compile(
            "(\\d{1,2})月(\\d{1,2})[日号]?"
    );

    /** HH:mm */
    private static final Pattern TIME_ONLY = Pattern.compile(
            "(\\d{1,2}):(\\d{2})(?::(\\d{2}))?"
    );

    /** 中文时间: 下午2点、上午10点半 */
    private static final Pattern CHINESE_TIME = Pattern.compile(
            "(上午|下午|早上|中午|晚上|凌晨)?\\s*(\\d{1,2})\\s*[点时](?:\\s*(\\d{1,2})\\s*(?:分|分钟)?)?(?:半)?"
    );

    /**
     * 从文本中解析相对时间
     * @param text 包含相对时间的文本
     * @return 解析后的Instant, 解析失败返回null
     */
    public Instant parseRelative(String text) {
        if (text == null || text.isBlank()) return null;
        text = text.trim();

        // 固定关键词
        Instant now = Instant.now();
        if (text.equals("现在") || text.equals("刚才")) return now;
        if (text.equals("之前") || text.equals("以前")) return now.minusSeconds(300); // 5分钟前
        if (text.equals("最近")) return now.minusSeconds(3600); // 1小时前
        if (text.equals("今天")) return truncateToStartOfDay(now, CHINA_ZONE);
        if (text.equals("昨天")) return truncateToStartOfDay(now.minus(Duration.ofDays(1)), CHINA_ZONE);
        if (text.equals("前天")) return truncateToStartOfDay(now.minus(Duration.ofDays(2)), CHINA_ZONE);
        if (text.equals("大前天")) return truncateToStartOfDay(now.minus(Duration.ofDays(3)), CHINA_ZONE);
        if (text.equals("明天")) return truncateToStartOfDay(now.plus(Duration.ofDays(1)), CHINA_ZONE);
        if (text.equals("后天")) return truncateToStartOfDay(now.plus(Duration.ofDays(2)), CHINA_ZONE);
        if (text.equals("大后天")) return truncateToStartOfDay(now.plus(Duration.ofDays(3)), CHINA_ZONE);

        // 这周/上周/下周
        Instant weekResult = parseRelativeWeekKeyword(text, now);
        if (weekResult != null) return weekResult;

        // 上个月/这个月/下个月
        Instant monthResult = parseRelativeMonthKeyword(text, now);
        if (monthResult != null) return monthResult;

        // 去年/前年/今年/明年
        Instant yearResult = parseRelativeYearKeyword(text, now);
        if (yearResult != null) return yearResult;

        // N天/小时/分钟/秒 前/后
        Instant unitResult = parseRelativeUnit(text, now);
        if (unitResult != null) return unitResult;

        // N周前/后
        Instant weekUnitResult = parseRelativeWeeks(text, now);
        if (weekUnitResult != null) return weekUnitResult;

        // N个月前/后
        Instant monthUnitResult = parseRelativeMonths(text, now);
        if (monthUnitResult != null) return monthUnitResult;

        // N年前/后
        Instant yearUnitResult = parseRelativeYears(text, now);
        if (yearUnitResult != null) return yearUnitResult;

        return null;
    }

    /**
     * 从文本中解析绝对时间
     * @param text 包含绝对时间的文本
     * @return 解析后的Instant, 解析失败返回null
     */
    public Instant parseAbsolute(String text) {
        if (text == null || text.isBlank()) return null;
        text = text.trim();

        // 尝试完整日期时间 YYYY-MM-DD HH:mm:ss
        Matcher m = FULL_DATETIME.matcher(text);
        if (m.find()) {
            return parseFullDatetime(m);
        }

        // 尝试日期 YYYY-MM-DD
        m = DATE_ONLY.matcher(text);
        if (m.find()) {
            return parseDateOnly(m);
        }

        // 尝试短日期 M月D日
        m = SHORT_DATE.matcher(text);
        if (m.find()) {
            return parseShortDate(m);
        }

        // 尝试中文时间
        m = CHINESE_TIME.matcher(text);
        if (m.find()) {
            return parseChineseTime(m);
        }

        // 尝试纯时间 HH:mm
        m = TIME_ONLY.matcher(text);
        if (m.find()) {
            return parseTimeOnly(m);
        }

        return null;
    }

    /**
     * 智能解析时间 - 先尝试绝对时间, 再尝试相对时间
     * @param text 时间文本
     * @return 解析后的Instant
     */
    public Instant parse(String text) {
        Instant result = parseAbsolute(text);
        if (result != null) return result;
        return parseRelative(text);
    }

    // ==================== 内部解析方法 ====================

    private Instant truncateToStartOfDay(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }

    private Instant parseRelativeWeekKeyword(String text, Instant now) {
        ZonedDateTime zdt = now.atZone(CHINA_ZONE);
        LocalDate today = zdt.toLocalDate();
        DayOfWeek dow = today.getDayOfWeek();
        LocalDate monday = today.with(DayOfWeek.MONDAY);

        if (text.equals("这周") || text.equals("本周")) {
            return monday.atStartOfDay(CHINA_ZONE).toInstant();
        } else if (text.equals("上周") || text.equals("上一周")) {
            return monday.minusWeeks(1).atStartOfDay(CHINA_ZONE).toInstant();
        } else if (text.equals("上上周")) {
            return monday.minusWeeks(2).atStartOfDay(CHINA_ZONE).toInstant();
        } else if (text.equals("下周") || text.equals("下一周")) {
            return monday.plusWeeks(1).atStartOfDay(CHINA_ZONE).toInstant();
        } else if (text.equals("下下周")) {
            return monday.plusWeeks(2).atStartOfDay(CHINA_ZONE).toInstant();
        }
        return null;
    }

    private Instant parseRelativeMonthKeyword(String text, Instant now) {
        ZonedDateTime zdt = now.atZone(CHINA_ZONE);
        YearMonth ym = YearMonth.from(zdt);

        if (text.equals("这个月") || text.equals("本月")) {
            return ym.atDay(1).atStartOfDay(CHINA_ZONE).toInstant();
        } else if (text.equals("上个月") || text.equals("上月")) {
            return ym.minusMonths(1).atDay(1).atStartOfDay(CHINA_ZONE).toInstant();
        } else if (text.equals("上上个月")) {
            return ym.minusMonths(2).atDay(1).atStartOfDay(CHINA_ZONE).toInstant();
        } else if (text.equals("下个月") || text.equals("下月")) {
            return ym.plusMonths(1).atDay(1).atStartOfDay(CHINA_ZONE).toInstant();
        }
        return null;
    }

    private Instant parseRelativeYearKeyword(String text, Instant now) {
        ZonedDateTime zdt = now.atZone(CHINA_ZONE);
        int year = zdt.getYear();

        if (text.equals("今年") || text.equals("本年")) {
            return Year.of(year).atDay(1).atStartOfDay(CHINA_ZONE).toInstant();
        } else if (text.equals("去年") || text.equals("上一年")) {
            return Year.of(year - 1).atDay(1).atStartOfDay(CHINA_ZONE).toInstant();
        } else if (text.equals("前年") || text.equals("上上年")) {
            return Year.of(year - 2).atDay(1).atStartOfDay(CHINA_ZONE).toInstant();
        } else if (text.equals("明年") || text.equals("下一年")) {
            return Year.of(year + 1).atDay(1).atStartOfDay(CHINA_ZONE).toInstant();
        } else if (text.equals("后年")) {
            return Year.of(year + 2).atDay(1).atStartOfDay(CHINA_ZONE).toInstant();
        }
        return null;
    }

    private Instant parseRelativeUnit(String text, Instant now) {
        Matcher m = RELATIVE_UNIT.matcher(text);
        if (m.find()) {
            int amount;
            Duration duration;
            boolean isPast;

            if (m.group(1) != null) {
                // N单位前
                amount = Integer.parseInt(m.group(1));
                String unit = m.group(2);
                duration = parseDuration(amount, unit);
                isPast = true;
            } else if (m.group(3) != null) {
                // N单位后
                amount = Integer.parseInt(m.group(3));
                String unit = m.group(4);
                duration = parseDuration(amount, unit);
                isPast = false;
            } else {
                return null;
            }

            return isPast ? now.minus(duration) : now.plus(duration);
        }
        return null;
    }

    private Instant parseRelativeWeeks(String text, Instant now) {
        Matcher m = RELATIVE_WEEKS.matcher(text);
        if (m.find()) {
            int amount;
            boolean isPast;
            if (m.group(1) != null) {
                amount = Integer.parseInt(m.group(1));
                isPast = true;
            } else if (m.group(2) != null) {
                amount = Integer.parseInt(m.group(2));
                isPast = false;
            } else {
                return null;
            }
            Duration duration = Duration.ofWeeks(amount);
            return isPast ? now.minus(duration) : now.plus(duration);
        }
        return null;
    }

    private Instant parseRelativeMonths(String text, Instant now) {
        Matcher m = RELATIVE_MONTHS.matcher(text);
        if (m.find()) {
            int amount;
            boolean isPast;
            if (m.group(1) != null) {
                amount = Integer.parseInt(m.group(1));
                isPast = true;
            } else if (m.group(2) != null) {
                amount = Integer.parseInt(m.group(2));
                isPast = false;
            } else {
                return null;
            }
            Duration duration = Duration.ofDays(30L * amount); // 近似
            return isPast ? now.minus(duration) : now.plus(duration);
        }
        return null;
    }

    private Instant parseRelativeYears(String text, Instant now) {
        Matcher m = RELATIVE_YEARS.matcher(text);
        if (m.find()) {
            int amount;
            boolean isPast;
            if (m.group(1) != null) {
                amount = Integer.parseInt(m.group(1));
                isPast = true;
            } else if (m.group(2) != null) {
                amount = Integer.parseInt(m.group(2));
                isPast = false;
            } else {
                return null;
            }
            Duration duration = Duration.ofDays(365L * amount); // 近似
            return isPast ? now.minus(duration) : now.plus(duration);
        }
        return null;
    }

    private Duration parseDuration(int amount, String unit) {
        switch (unit) {
            case "天": case "日": return Duration.ofDays(amount);
            case "小时": case "时": case "h": return Duration.ofHours(amount);
            case "分钟": case "分": case "min": return Duration.ofMinutes(amount);
            case "秒": case "s": return Duration.ofSeconds(amount);
            default: return Duration.ofSeconds(amount);
        }
    }

    private Instant parseFullDatetime(Matcher m) {
        try {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            int hour = Integer.parseInt(m.group(4));
            int minute = Integer.parseInt(m.group(5));
            int second = m.group(6) != null ? Integer.parseInt(m.group(6)) : 0;

            LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, second);
            return ldt.atZone(CHINA_ZONE).toInstant();
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }

    private Instant parseDateOnly(Matcher m) {
        try {
            int year, month, day;
            if (m.group(1) != null) {
                year = Integer.parseInt(m.group(1));
                month = Integer.parseInt(m.group(2));
                day = Integer.parseInt(m.group(3));
            } else if (m.group(4) != null) {
                year = Integer.parseInt(m.group(4));
                month = Integer.parseInt(m.group(5));
                day = Integer.parseInt(m.group(6));
            } else {
                return null;
            }

            LocalDate date = LocalDate.of(year, month, day);
            return date.atStartOfDay(CHINA_ZONE).toInstant();
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }

    private Instant parseShortDate(Matcher m) {
        try {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            int year = Year.now(CHINA_ZONE).getValue();

            LocalDate date = LocalDate.of(year, month, day);
            return date.atStartOfDay(CHINA_ZONE).toInstant();
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }

    private Instant parseTimeOnly(Matcher m) {
        try {
            int hour = Integer.parseInt(m.group(1));
            int minute = Integer.parseInt(m.group(2));
            int second = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;

            LocalDate today = LocalDate.now(CHINA_ZONE);
            LocalTime time = LocalTime.of(hour, minute, second);
            return today.atTime(time).atZone(CHINA_ZONE).toInstant();
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }

    private Instant parseChineseTime(Matcher m) {
        try {
            String period = m.group(1);
            int hour = Integer.parseInt(m.group(2));
            int minute = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;

            // 12小时制转换
            if ("下午".equals(period) || "晚上".equals(period)) {
                if (hour < 12) hour += 12;
            } else if ("上午".equals(period) || "早上".equals(period) || "凌晨".equals(period)) {
                if (hour == 12) hour = 0;
            } else if ("中午".equals(period)) {
                // 中午默认12点
                if (hour < 12) hour += 12;
            }

            LocalDate today = LocalDate.now(CHINA_ZONE);
            LocalTime time = LocalTime.of(hour, minute);
            return today.atTime(time).atZone(CHINA_ZONE).toInstant();
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }
}
