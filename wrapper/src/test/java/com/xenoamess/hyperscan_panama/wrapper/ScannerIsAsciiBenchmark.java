package com.xenoamess.hyperscan_panama.wrapper;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for Scanner.isAscii() LATIN1 byte-level check.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(4)
@State(Scope.Benchmark)
public class ScannerIsAsciiBenchmark {

    private Method isAsciiMethod;
    private String asciiString;
    private String nonAsciiString;
    private String latin1NonAsciiString;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        isAsciiMethod = Scanner.class.getDeclaredMethod("isAscii", String.class);
        isAsciiMethod.setAccessible(true);

        StringBuilder asciiBuilder = new StringBuilder();
        for (int i = 0; i < 1024; i++) {
            asciiBuilder.append((char) ('a' + (i % 26)));
        }
        asciiString = asciiBuilder.toString();

        nonAsciiString = asciiString + "你好";

        latin1NonAsciiString = new String(
                (asciiString.substring(0, 100) + "é").getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.ISO_8859_1
        );
    }

    @Benchmark
    public void isAscii_asciiString(Blackhole blackhole) throws InvocationTargetException, IllegalAccessException {
        blackhole.consume(isAsciiMethod.invoke(null, asciiString));
    }

    @Benchmark
    public void isAscii_nonAsciiString(Blackhole blackhole) throws InvocationTargetException, IllegalAccessException {
        blackhole.consume(isAsciiMethod.invoke(null, nonAsciiString));
    }

    @Benchmark
    public void isAscii_latin1NonAsciiString(Blackhole blackhole) throws InvocationTargetException, IllegalAccessException {
        blackhole.consume(isAsciiMethod.invoke(null, latin1NonAsciiString));
    }
}
