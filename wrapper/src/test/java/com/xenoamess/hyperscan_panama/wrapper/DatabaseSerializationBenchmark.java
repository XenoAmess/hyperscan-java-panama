package com.xenoamess.hyperscan_panama.wrapper;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for Database.save() serialization performance.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = "--enable-native-access=ALL-UNNAMED")
@Threads(1)
@State(Scope.Benchmark)
public class DatabaseSerializationBenchmark {

    private Database database;
    private OutputStream nullOutputStream;

    @Setup(Level.Trial)
    public void setup() throws CompileErrorException {
        Expression expression = new Expression(
                "benchmark",
                EnumSet.of(ExpressionFlag.CASELESS, ExpressionFlag.SOM_LEFTMOST),
                1
        );
        database = Database.compile(expression);
        nullOutputStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                // discard
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                // discard
            }
        };
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Benchmark
    public void save() throws IOException {
        database.save(nullOutputStream);
    }
}
