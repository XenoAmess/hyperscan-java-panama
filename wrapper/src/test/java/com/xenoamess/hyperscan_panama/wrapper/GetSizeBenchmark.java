package com.xenoamess.hyperscan_panama.wrapper;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for Database.getSize() and Scanner.getSize() buffer reuse.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = "--enable-native-access=ALL-UNNAMED")
@Threads(1)
@State(Scope.Benchmark)
public class GetSizeBenchmark {

    private Database database;
    private Scanner scanner;

    @Setup(Level.Trial)
    public void setup() throws CompileErrorException {
        Expression expression = new Expression(
                "benchmark",
                EnumSet.of(ExpressionFlag.CASELESS, ExpressionFlag.SOM_LEFTMOST),
                1
        );
        database = Database.compile(expression);
        scanner = new Scanner();
        scanner.allocScratch(database);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        scanner.close();
        database.close();
    }

    @Benchmark
    public void databaseGetSize() {
        database.getSize();
    }

    @Benchmark
    public void scannerGetSize() {
        scanner.getSize();
    }
}
