package com.memoryplatform.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆上下文 - 检索到的相关记忆及其评分
 */
public class MemoryContext {
    private List<Memory> memories;
    private Map<String, Double> scores;
    private int windowSize;
    private double totalRelevance;

    public MemoryContext() {}

    public MemoryContext(List<Memory> memories, Map<String, Double> scores,
                         int windowSize, double totalRelevance) {
        this.memories = memories;
        this.scores = scores;
        this.windowSize = windowSize;
        this.totalRelevance = totalRelevance;
    }

    public List<Memory> getMemories() { return memories; }
    public Map<String, Double> getScores() { return scores; }
    public int getWindowSize() { return windowSize; }
    public double getTotalRelevance() { return totalRelevance; }

    public void setMemories(List<Memory> memories) { this.memories = memories; }
    public void setScores(Map<String, Double> scores) { this.scores = scores; }
    public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
    public void setTotalRelevance(double totalRelevance) { this.totalRelevance = totalRelevance; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private List<Memory> memories;
        private Map<String, Double> scores = new HashMap<>();
        private int windowSize = 10;
        private double totalRelevance = 0.0;

        public Builder memories(List<Memory> memories) { this.memories = memories; return this; }
        public Builder scores(Map<String, Double> scores) { this.scores = scores; return this; }
        public Builder windowSize(int windowSize) { this.windowSize = windowSize; return this; }
        public Builder totalRelevance(double totalRelevance) { this.totalRelevance = totalRelevance; return this; }

        public MemoryContext build() {
            return new MemoryContext(memories, scores, windowSize, totalRelevance);
        }
    }
}
