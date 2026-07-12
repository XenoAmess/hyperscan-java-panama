package com.xenoamess.hyperscan_panama.wrapper;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for Expression.getFlagBits() caching.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(4)
@State(Scope.Benchmark)
public class ExpressionFlagBitsBenchmark {

    private Expression expression;

    @Setup(Level.Trial)
    public void setup() {
        EnumSet<ExpressionFlag> flags = EnumSet.of(
                ExpressionFlag.CASELESS,
                ExpressionFlag.SOM_LEFTMOST,
                ExpressionFlag.DOTALL,
                ExpressionFlag.MULTILINE
        );
        expression = new Expression("benchmark", flags, 1);
    }

    @Benchmark
    public void getFlagBits(Blackhole blackhole) {
        blackhole.consume(expression.getFlagBits());
    }
}
