package com.xenoamess.hyperscan_panama.wrapper;

import com.xenoamess.hyperscan_panama.util.PatternFilter;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Benchmark for PatternFilter.filter() allocation reduction.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = "--enable-native-access=ALL-UNNAMED")
@Threads(1)
@State(Scope.Benchmark)
public class PatternFilterBenchmark {

    private PatternFilter filter;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        List<Pattern> patterns = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            patterns.add(Pattern.compile("word" + i + "([0-9]+)"));
        }
        filter = new PatternFilter(patterns);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (filter != null) {
            filter.close();
        }
    }

    @Benchmark
    public List<Matcher> filter() {
        return filter.filter("word50 abc word75 123");
    }
}
