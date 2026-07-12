package com.xenoamess.hyperscan_panama.wrapper;

import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for sparse expression ID lookup in Database.getExpression().
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = "--enable-native-access=ALL-UNNAMED")
@Threads(4)
@State(Scope.Benchmark)
public class SparseIdLookupBenchmark {

    private Database database;
    private int[] ids;

    @Setup(Level.Trial)
    public void setup() throws CompileErrorException {
        List<Expression> expressions = new ArrayList<>();
        ids = new int[100];
        for (int i = 0; i < 100; i++) {
            int id = i * 1000;
            ids[i] = id;
            expressions.add(new Expression("pattern" + i, ExpressionFlag.SOM_LEFTMOST, id));
        }
        database = Database.compile(expressions);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        database.close();
    }

    @Benchmark
    public void getExpressionSparseIds() {
        for (int id : ids) {
            database.getExpression(id);
        }
    }
}
