package com.xenoamess.hyperscan_panama.wrapper;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for ExpressionFlag native value batch initialization.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = "--enable-native-access=ALL-UNNAMED")
@Threads(4)
@State(Scope.Benchmark)
public class ExpressionFlagBenchmark {

    @Benchmark
    public void readFlagBits(Blackhole blackhole) {
        blackhole.consume(ExpressionFlag.CASELESS.getBits());
        blackhole.consume(ExpressionFlag.DOTALL.getBits());
        blackhole.consume(ExpressionFlag.MULTILINE.getBits());
        blackhole.consume(ExpressionFlag.SINGLEMATCH.getBits());
        blackhole.consume(ExpressionFlag.ALLOWEMPTY.getBits());
        blackhole.consume(ExpressionFlag.UTF8.getBits());
        blackhole.consume(ExpressionFlag.UCP.getBits());
        blackhole.consume(ExpressionFlag.PREFILTER.getBits());
        blackhole.consume(ExpressionFlag.SOM_LEFTMOST.getBits());
        blackhole.consume(ExpressionFlag.COMBINATION.getBits());
        blackhole.consume(ExpressionFlag.QUIET.getBits());
    }

    @Benchmark
    public void createExpressionWithFlags(Blackhole blackhole) {
        EnumSet<ExpressionFlag> flags = EnumSet.of(
                ExpressionFlag.CASELESS,
                ExpressionFlag.DOTALL,
                ExpressionFlag.MULTILINE,
                ExpressionFlag.SOM_LEFTMOST
        );
        blackhole.consume(new Expression("benchmark", flags, 1));
    }
}
