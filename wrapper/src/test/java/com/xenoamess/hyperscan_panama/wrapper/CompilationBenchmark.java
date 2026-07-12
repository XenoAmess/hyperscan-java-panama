package com.xenoamess.hyperscan_panama.wrapper;

import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for NativeExpressionCollection stream-overhead reduction during compilation.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = "--enable-native-access=ALL-UNNAMED")
@Threads(1)
@State(Scope.Benchmark)
public class CompilationBenchmark {

    private List<Expression> expressions;

    private Database database;

    @Setup(Level.Trial)
    public void setup() {
        expressions = new ArrayList<>();
        EnumSet<ExpressionFlag> flags = EnumSet.of(ExpressionFlag.CASELESS, ExpressionFlag.SOM_LEFTMOST);
        for (int i = 0; i < 100; i++) {
            expressions.add(new Expression("pattern" + i + "([0-9]+)", flags, i));
        }
    }

    @TearDown(Level.Invocation)
    public void tearDown() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    @Benchmark
    public void compile() throws CompileErrorException {
        database = Database.compile(expressions);
    }
}
