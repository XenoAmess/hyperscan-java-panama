package com.xenoamess.hyperscan_panama.wrapper;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for readErrorMessage() arena reduction.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = "--enable-native-access=ALL-UNNAMED")
@Threads(1)
@State(Scope.Benchmark)
public class ErrorMessageBenchmark {

    private Expression invalidExpression;

    @Setup(Level.Trial)
    public void setup() {
        invalidExpression = new Expression("test\\1", EnumSet.noneOf(ExpressionFlag.class));
    }

    @Benchmark
    public void validateInvalidExpression(Blackhole blackhole) {
        blackhole.consume(invalidExpression.validate());
    }
}
