package com.xenoamess.hyperscan.performance;

import java.util.LinkedHashMap;
import java.util.Map;

public class BenchmarkResult {
    private final String name;
    private final Map<String, Object> metrics = new LinkedHashMap<>();

    public BenchmarkResult(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public BenchmarkResult metric(String key, Object value) {
        metrics.put(key, value);
        return this;
    }

    public Map<String, Object> getMetrics() {
        return new LinkedHashMap<>(metrics);
    }
}
