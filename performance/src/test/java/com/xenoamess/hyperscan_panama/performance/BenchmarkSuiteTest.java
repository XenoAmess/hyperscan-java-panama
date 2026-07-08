package com.xenoamess.hyperscan_panama.performance;

import com.xenoamess.hyperscan_panama.wrapper.Database;
import com.xenoamess.hyperscan_panama.wrapper.Expression;
import com.xenoamess.hyperscan_panama.wrapper.ExpressionFlag;
import com.xenoamess.hyperscan_panama.wrapper.Match;
import com.xenoamess.hyperscan_panama.wrapper.Scanner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BenchmarkSuiteTest {

    private static final String BENCHMARK_PLATFORM = System.getProperty("benchmark.platform", "unknown");
    private static final String NATIVE_VERSION = Scanner.getVersion();
    private static final String RUNNER_OS = System.getProperty("os.name", "");
    private static final String RUNNER_ARCH = System.getProperty("os.arch", "");

    private final List<BenchmarkResult> results = new ArrayList<>();

    @AfterAll
    void writeReport() throws Exception {
        BenchmarkRecorder recorder = new BenchmarkRecorder(
                BENCHMARK_PLATFORM,
                NATIVE_VERSION,
                System.getenv("GITHUB_SHA"),
                RUNNER_OS,
                RUNNER_ARCH,
                readCpuModel(),
                readCpuFlags(),
                results
        );
        recorder.write();
    }

    private static String readCpuModel() {
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    Runtime.getRuntime().exec("cat /proc/cpuinfo").getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines()
                        .filter(line -> line.startsWith("model name"))
                        .map(line -> line.substring(line.indexOf(':') + 1).trim())
                        .findFirst()
                        .orElse("");
            } catch (Exception ignored) {
            }
        }
        return System.getenv("CPU_MODEL") == null ? "" : System.getenv("CPU_MODEL");
    }

    private static String readCpuFlags() {
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    Runtime.getRuntime().exec("cat /proc/cpuinfo").getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines()
                        .filter(line -> line.startsWith("flags"))
                        .map(line -> line.substring(line.indexOf(':') + 1).trim())
                        .findFirst()
                        .orElse("");
            } catch (Exception ignored) {
            }
        }
        return System.getenv("CPU_FLAGS") == null ? "" : System.getenv("CPU_FLAGS");
    }

    @Test
    void benchmarkCompileSmallSet() throws Exception {
        List<Expression> expressions = Arrays.asList(
                new Expression("password", ExpressionFlag.CASELESS, 1),
                new Expression("[0-9]{4,16}", ExpressionFlag.SOM_LEFTMOST, 2),
                new Expression("https?://[^\\s]+", ExpressionFlag.SOM_LEFTMOST, 3)
        );
        runAndRecord("compileSmallSet", 100, () -> {
            try (Database database = Database.compile(expressions)) {
                assertThat(database.getSize()).isGreaterThan(0);
            }
            return 0L;
        });
    }

    @Test
    void benchmarkCompileLargeSet() throws Exception {
        List<Expression> expressions = generateLiteralExpressions(1000);
        runAndRecord("compileLargeSet", 20, () -> {
            try (Database database = Database.compile(expressions)) {
                assertThat(database.getSize()).isGreaterThan(0);
            }
            return 0L;
        });
    }

    @Test
    void benchmarkScanShortText() throws Exception {
        List<Expression> expressions = Arrays.asList(
                new Expression("password", ExpressionFlag.CASELESS, 1),
                new Expression("[0-9]{4,16}", ExpressionFlag.SOM_LEFTMOST, 2),
                new Expression("https?://[^\\s]+", ExpressionFlag.SOM_LEFTMOST, 3)
        );
        try (Database database = Database.compile(expressions);
             Scanner scanner = new Scanner()) {
            scanner.allocScratch(database);
            String input = "The password is 1234 and the link is https://example.com/path.";
            runAndRecord("scanShortText", 100_000, () -> {
                List<Match> matches = scanner.scan(database, input);
                return (long) matches.size();
            });
        }
    }

    @Test
    void benchmarkScanLongText() throws Exception {
        List<Expression> expressions = Arrays.asList(
                new Expression("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", ExpressionFlag.SOM_LEFTMOST, 1),
                new Expression("[0-9]{3}-[0-9]{2}-[0-9]{4}", ExpressionFlag.SOM_LEFTMOST, 2),
                new Expression("\\bERROR\\b", ExpressionFlag.SOM_LEFTMOST, 3),
                new Expression("https?://[^\\s]+", ExpressionFlag.SOM_LEFTMOST, 4)
        );
        try (Database database = Database.compile(expressions);
             Scanner scanner = new Scanner()) {
            scanner.allocScratch(database);
            String input = generateLongText(1_000_000);
            runAndRecord("scanLongText", 1_000, () -> {
                List<Match> matches = scanner.scan(database, input);
                return (long) matches.size();
            });
        }
    }

    @Test
    void benchmarkHasMatchShortText() throws Exception {
        Expression expression = new Expression("password", ExpressionFlag.CASELESS, 1);
        try (Database database = Database.compile(expression);
             Scanner scanner = new Scanner()) {
            scanner.allocScratch(database);
            String input = "The password is secret.";
            runAndRecord("hasMatchShortText", 100_000, () -> scanner.hasMatch(database, input) ? 1L : 0L);
        }
    }

    @Test
    void benchmarkScanManyLiteralPatterns() throws Exception {
        List<Expression> expressions = generateLiteralExpressions(500);
        try (Database database = Database.compile(expressions);
             Scanner scanner = new Scanner()) {
            scanner.allocScratch(database);
            String input = buildHaystackWithMatches(expressions);
            runAndRecord("scanManyLiteralPatterns", 10_000, () -> {
                List<Match> matches = scanner.scan(database, input);
                return (long) matches.size();
            });
        }
    }

    @Test
    void benchmarkScanByteBuffer() throws Exception {
        List<Expression> expressions = Arrays.asList(
                new Expression("[0-9]{4,16}", ExpressionFlag.SOM_LEFTMOST, 1),
                new Expression("[A-Fa-f0-9]{32}", ExpressionFlag.SOM_LEFTMOST, 2)
        );
        try (Database database = Database.compile(expressions);
             Scanner scanner = new Scanner()) {
            scanner.allocScratch(database);
            byte[] input = generateLongText(100_000).getBytes(StandardCharsets.UTF_8);
            runAndRecord("scanByteBuffer", 10_000, () -> {
                long[] matches = new long[1];
                scanner.scan(database, input, (expression, fromByteIdx, toByteIdx) -> {
                    matches[0]++;
                    return true;
                });
                return matches[0];
            });
        }
    }

    @Test
    void benchmarkMixedPatternThroughput() throws Exception {
        List<Expression> expressions = buildMixedExpressions(500);
        String input = buildMixedInput(20_000, 50);

        try (Database database = Database.compile(expressions);
             Scanner scanner = new Scanner()) {
            scanner.allocScratch(database);

            int warmupIterations = 2;
            int measuredIterations = 5;

            for (int i = 0; i < warmupIterations; i++) {
                scanner.scan(database, input);
            }

            double[] elapsedMs = new double[measuredIterations];
            double[] throughputMBps = new double[measuredIterations];
            List<Match> lastMatches = null;
            for (int i = 0; i < measuredIterations; i++) {
                long start = System.nanoTime();
                List<Match> matches = scanner.scan(database, input);
                long elapsed = System.nanoTime() - start;
                elapsedMs[i] = elapsed / 1_000_000.0;
                throughputMBps[i] = input.length() * 1_000.0 / elapsed;
                lastMatches = matches;
            }

            BenchmarkResult result = new BenchmarkResult("mixedPatternThroughput")
                    .metric("patterns", expressions.size())
                    .metric("inputBytes", input.length())
                    .metric("matches", lastMatches == null ? 0 : lastMatches.size())
                    .metric("iterations", measuredIterations)
                    .metric("elapsedMsAvg", avg(elapsedMs))
                    .metric("elapsedMsMin", min(elapsedMs))
                    .metric("elapsedMsMax", max(elapsedMs))
                    .metric("throughputMBpsAvg", avg(throughputMBps))
                    .metric("throughputMBpsMin", min(throughputMBps))
                    .metric("throughputMBpsMax", max(throughputMBps));
            results.add(result);
        }
    }

    private void runAndRecord(String name, int iterations, ThrowingLongSupplier task) throws Exception {
        long totalMatches = 0;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            totalMatches += task.getAsLong();
        }
        long elapsedNanos = System.nanoTime() - start;
        double elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        double opsPerSecond = iterations / (elapsedNanos / 1_000_000_000.0);
        double nsPerOp = (double) elapsedNanos / iterations;

        BenchmarkResult result = new BenchmarkResult(name)
                .metric("iterations", iterations)
                .metric("elapsedMs", elapsedMs)
                .metric("opsPerSecond", opsPerSecond)
                .metric("nsPerOp", nsPerOp)
                .metric("totalMatches", totalMatches);
        results.add(result);
    }

    private static List<Expression> generateLiteralExpressions(int count) {
        List<Expression> expressions = new ArrayList<>(count);
        Random random = new Random(2028);
        for (int i = 0; i < count; i++) {
            String literal = "LIT_" + String.format("%08x", random.nextInt());
            expressions.add(new Expression(literal, ExpressionFlag.SOM_LEFTMOST, i));
        }
        return expressions;
    }

    private static String buildHaystackWithMatches(List<Expression> expressions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < expressions.size(); i++) {
            if (i % 5 == 0) {
                sb.append(expressions.get(i).getExpression()).append(' ');
            } else {
                sb.append("noise_").append(i).append(' ');
            }
        }
        return sb.toString();
    }

    @Test
    void benchmarkCrossPlatformFixedWorkload() throws Exception {
        // Replicates the fixed workload from hyperscan-java-test's InstructionSetGranularityTest
        // so the two projects can be compared on the same 500-pattern / ~20 KB input.
        List<Expression> expressions = buildCrossPlatformExpressions(500);
        String input = buildCrossPlatformInput(20_000, 50);

        try (Database database = Database.compile(expressions);
             Scanner scanner = new Scanner()) {
            scanner.allocScratch(database);

            int warmupIterations = 2;
            int measuredIterations = 5;

            for (int i = 0; i < warmupIterations; i++) {
                scanner.scan(database, input);
            }

            double[] elapsedMs = new double[measuredIterations];
            double[] throughputMBps = new double[measuredIterations];
            List<Match> lastMatches = null;
            for (int i = 0; i < measuredIterations; i++) {
                long start = System.nanoTime();
                List<Match> matches = scanner.scan(database, input);
                long elapsed = System.nanoTime() - start;
                elapsedMs[i] = elapsed / 1_000_000.0;
                throughputMBps[i] = input.length() * 1_000.0 / elapsed;
                lastMatches = matches;
            }

            BenchmarkResult result = new BenchmarkResult("ISA granularity benchmark")
                    .metric("patterns", expressions.size())
                    .metric("inputBytes", input.length())
                    .metric("matches", lastMatches == null ? 0 : lastMatches.size())
                    .metric("iterations", measuredIterations)
                    .metric("elapsedMsAvg", avg(elapsedMs))
                    .metric("elapsedMsMin", min(elapsedMs))
                    .metric("elapsedMsMax", max(elapsedMs))
                    .metric("throughputMBpsAvg", avg(throughputMBps))
                    .metric("throughputMBpsMin", min(throughputMBps))
                    .metric("throughputMBpsMax", max(throughputMBps));
            results.add(result);
        }
    }

    @Test
    void benchmarkFixedWorkloadCounting() throws Exception {
        // Same workload as ISA granularity benchmark, but the match handler only counts
        // matches instead of building Match objects and extracting substrings. This isolates
        // the overhead of the public API's object creation and string extraction from the
        // raw FFM/native scan performance.
        List<Expression> expressions = buildCrossPlatformExpressions(500);
        String input = buildCrossPlatformInput(20_000, 50);

        try (Database database = Database.compile(expressions);
             Scanner scanner = new Scanner()) {
            scanner.allocScratch(database);

            int warmupIterations = 2;
            int measuredIterations = 5;

            for (int i = 0; i < warmupIterations; i++) {
                scanner.scan(database, input, (expression, from, to) -> true);
            }

            double[] elapsedMs = new double[measuredIterations];
            double[] throughputMBps = new double[measuredIterations];
            long[] lastCount = new long[1];
            for (int i = 0; i < measuredIterations; i++) {
                long[] count = new long[1];
                long start = System.nanoTime();
                scanner.scan(database, input, (expression, from, to) -> {
                    count[0]++;
                    return true;
                });
                long elapsed = System.nanoTime() - start;
                elapsedMs[i] = elapsed / 1_000_000.0;
                throughputMBps[i] = input.length() * 1_000.0 / elapsed;
                lastCount[0] = count[0];
            }

            BenchmarkResult result = new BenchmarkResult("ISA fixed workload (counting only)")
                    .metric("patterns", expressions.size())
                    .metric("inputBytes", input.length())
                    .metric("matches", lastCount[0])
                    .metric("iterations", measuredIterations)
                    .metric("elapsedMsAvg", avg(elapsedMs))
                    .metric("elapsedMsMin", min(elapsedMs))
                    .metric("elapsedMsMax", max(elapsedMs))
                    .metric("throughputMBpsAvg", avg(throughputMBps))
                    .metric("throughputMBpsMin", min(throughputMBps))
                    .metric("throughputMBpsMax", max(throughputMBps));
            results.add(result);
        }
    }

    private static List<Expression> buildCrossPlatformExpressions(int count) {
        List<Expression> expressions = new ArrayList<>(count);
        expressions.add(new Expression("[0-9]{3}-[0-9]{2}-[0-9]{4}", ExpressionFlag.SOM_LEFTMOST, 0));
        expressions.add(new Expression("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", ExpressionFlag.SOM_LEFTMOST, 1));
        expressions.add(new Expression("https?://[^\\s]+", ExpressionFlag.SOM_LEFTMOST, 2));
        expressions.add(new Expression("\\bERROR\\b", ExpressionFlag.SOM_LEFTMOST, 3));
        expressions.add(new Expression("\\bWARNING\\b", ExpressionFlag.SOM_LEFTMOST, 4));
        Random random = new Random(2027);
        for (int i = 5; i < count; i++) {
            String token = String.format("%08x", random.nextInt());
            expressions.add(new Expression(Pattern.quote("TOKEN_" + token), ExpressionFlag.SOM_LEFTMOST, i));
        }
        return expressions;
    }

    private static String buildCrossPlatformInput(int size, int seedCount) {
        Random random = new Random(2026);
        Random tokenRandom = new Random(2027);
        StringBuilder sb = new StringBuilder(size);
        String[] fragments = {
                "Contact support@example.com for help. ",
                "SSN 123-45-6789 is fake. ",
                "Visit https://example.com/page for more. ",
                "ERROR: disk full. ",
                "WARNING: high latency. ",
        };
        while (sb.length() < size) {
            if (random.nextInt(10) == 0 && seedCount > 0) {
                sb.append("TOKEN_").append(String.format("%08x", tokenRandom.nextInt())).append(" ");
                seedCount--;
            } else {
                sb.append(fragments[random.nextInt(fragments.length)]);
            }
        }
        return sb.toString();
    }

    private static List<Expression> buildMixedExpressions(int count) {
        List<Expression> expressions = new ArrayList<>(count);
        expressions.add(new Expression("[0-9]{3}-[0-9]{2}-[0-9]{4}", ExpressionFlag.SOM_LEFTMOST, 0));
        expressions.add(new Expression("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", ExpressionFlag.SOM_LEFTMOST, 1));
        expressions.add(new Expression("https?://[^\\s]+", ExpressionFlag.SOM_LEFTMOST, 2));
        expressions.add(new Expression("\\bERROR\\b", ExpressionFlag.SOM_LEFTMOST, 3));
        expressions.add(new Expression("\\bWARNING\\b", ExpressionFlag.SOM_LEFTMOST, 4));
        Random random = new Random(2027);
        for (int i = 5; i < count; i++) {
            String token = String.format("%08x", random.nextInt());
            expressions.add(new Expression(Pattern.quote("TOKEN_" + token), ExpressionFlag.SOM_LEFTMOST, i));
        }
        return expressions;
    }

    private static String buildMixedInput(int size, int seedCount) {
        Random random = new Random(2026);
        Random tokenRandom = new Random(2027);
        StringBuilder sb = new StringBuilder(size);
        String[] fragments = {
                "Contact support@example.com for help. ",
                "SSN 123-45-6789 is fake. ",
                "Visit https://example.com/page for more. ",
                "ERROR: disk full. ",
                "WARNING: high latency. ",
        };
        while (sb.length() < size) {
            if (random.nextInt(10) == 0 && seedCount > 0) {
                sb.append("TOKEN_").append(String.format("%08x", tokenRandom.nextInt())).append(" ");
                seedCount--;
            } else {
                sb.append(fragments[random.nextInt(fragments.length)]);
            }
        }
        return sb.toString();
    }

    private static double avg(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double min(double[] values) {
        double min = Double.MAX_VALUE;
        for (double v : values) {
            if (v < min) {
                min = v;
            }
        }
        return min;
    }

    private static double max(double[] values) {
        double max = -Double.MAX_VALUE;
        for (double v : values) {
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    private static String generateLongText(int length) {
        Random random = new Random(12345);
        StringBuilder sb = new StringBuilder(length);
        String[] words = {"hello", "world", "foo", "bar", "1234", "test", "data", "error", "https://example.com/page"};
        while (sb.length() < length) {
            sb.append(words[random.nextInt(words.length)]).append(' ');
            if (random.nextInt(20) == 0) {
                sb.append("user@example.com ");
            }
            if (random.nextInt(50) == 0) {
                sb.append("ERROR: disk full ");
            }
            if (random.nextInt(100) == 0) {
                sb.append("123-45-6789 ");
            }
        }
        return sb.toString();
    }

    @FunctionalInterface
    interface ThrowingLongSupplier {
        long getAsLong() throws Exception;
    }
}
