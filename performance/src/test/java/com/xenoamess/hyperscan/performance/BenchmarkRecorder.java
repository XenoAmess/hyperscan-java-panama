package com.xenoamess.hyperscan.performance;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

public class BenchmarkRecorder {
    private static final String OUTPUT_DIR = System.getProperty(
            "benchmark.output.dir", "target/benchmark-results");
    private static final String OUTPUT_FILE = System.getProperty(
            "benchmark.output.file", "benchmark-result.json");

    private final String platform;
    private final String nativeVersion;
    private final String commitSha;
    private final String runnerOs;
    private final String runnerArch;
    private final String cpuModel;
    private final String cpuFlags;
    private final String timestamp;
    private final List<BenchmarkResult> benchmarks;

    public BenchmarkRecorder(String platform, String nativeVersion, String commitSha,
                             String runnerOs, String runnerArch, String cpuModel,
                             String cpuFlags, List<BenchmarkResult> benchmarks) {
        this.platform = platform;
        this.nativeVersion = nativeVersion;
        this.commitSha = commitSha;
        this.runnerOs = runnerOs;
        this.runnerArch = runnerArch;
        this.cpuModel = cpuModel;
        this.cpuFlags = cpuFlags;
        this.timestamp = Instant.now().toString();
        this.benchmarks = benchmarks;
    }

    public void write() throws Exception {
        File dir = new File(OUTPUT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create benchmark output directory: " + dir);
        }
        File file = new File(dir, OUTPUT_FILE);
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.println(toJson());
        }
        System.out.println("Benchmark result written to: " + file.getAbsolutePath());
    }

    private String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"platform\": ").append(jsonString(platform)).append(",\n");
        sb.append("  \"nativeVersion\": ").append(jsonString(nativeVersion)).append(",\n");
        sb.append("  \"commitSha\": ").append(jsonString(commitSha)).append(",\n");
        sb.append("  \"runnerOs\": ").append(jsonString(runnerOs)).append(",\n");
        sb.append("  \"runnerArch\": ").append(jsonString(runnerArch)).append(",\n");
        sb.append("  \"cpuModel\": ").append(jsonString(cpuModel)).append(",\n");
        sb.append("  \"cpuFlags\": ").append(jsonString(cpuFlags)).append(",\n");
        sb.append("  \"timestamp\": ").append(jsonString(timestamp)).append(",\n");
        sb.append("  \"benchmarks\": [\n");
        for (int i = 0; i < benchmarks.size(); i++) {
            BenchmarkResult result = benchmarks.get(i);
            sb.append("    {\n");
            sb.append("      \"name\": ").append(jsonString(result.getName())).append(",\n");
            sb.append("      \"metrics\": {\n");
            int metricIndex = 0;
            for (java.util.Map.Entry<String, Object> entry : result.getMetrics().entrySet()) {
                sb.append("        ").append(jsonString(entry.getKey())).append(": ");
                sb.append(jsonValue(entry.getValue()));
                if (metricIndex++ < result.getMetrics().size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("      }\n");
            sb.append("    }");
            if (i < benchmarks.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String jsonString(Object value) {
        if (value == null) {
            return "null";
        }
        String s = String.valueOf(value);
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return jsonString(value);
    }
}
