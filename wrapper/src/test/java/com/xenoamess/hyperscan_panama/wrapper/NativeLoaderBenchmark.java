package com.xenoamess.hyperscan_panama.wrapper;

import com.xenoamess.hyperscan_panama.jni.HyperscanJni;
import com.xenoamess.hyperscan_panama.jni.HyperscanNativeLoader;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark for HyperscanNativeLoader platform selection caching.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = "--enable-native-access=ALL-UNNAMED")
@Threads(4)
@State(Scope.Benchmark)
public class NativeLoaderBenchmark {

    @Benchmark
    public void loadJni(Blackhole blackhole) {
        HyperscanJni jni = HyperscanNativeLoader.loadJni();
        blackhole.consume(jni);
    }
}
