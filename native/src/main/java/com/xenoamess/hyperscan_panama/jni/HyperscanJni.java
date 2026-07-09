package com.xenoamess.hyperscan_panama.jni;

import java.lang.foreign.Arena;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Platform-independent facade for the Hyperscan/VectorScan native bindings.
 *
 * <p>Implementations are generated per platform and delegate to the jextract
 * generated classes located in the matching platform-specific package. Callers
 * obtain an instance through {@link HyperscanNativeLoader#loadJni()}.</p>
 */
public interface HyperscanJni {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------
    int hsSuccess();
    int hsCompilerError();
    int hsScanTerminated();
    int hsModeBlock();

    int hsFlagCaseless();
    int hsFlagDotall();
    int hsFlagMultiline();
    int hsFlagSinglematch();
    int hsFlagAllowempty();
    int hsFlagUtf8();
    int hsFlagUcp();
    int hsFlagPrefilter();
    int hsFlagSomLeftmost();
    int hsFlagCombination();
    int hsFlagQuiet();

    // ------------------------------------------------------------------
    // Layouts
    // ------------------------------------------------------------------
    ValueLayout size_t();

    long readSize_t(MemorySegment segment, long offset);

    // ------------------------------------------------------------------
    // Struct hs_compile_error
    // ------------------------------------------------------------------
    GroupLayout hsCompileErrorLayout();

    MemorySegment hsCompileErrorMessage(MemorySegment error);

    int hsCompileErrorExpression(MemorySegment error);

    // ------------------------------------------------------------------
    // Native functions
    // ------------------------------------------------------------------
    int hsFreeDatabase(MemorySegment db);

    int hsCompileMulti(
            MemorySegment expressions,
            MemorySegment flags,
            MemorySegment ids,
            int elements,
            int mode,
            MemorySegment platform,
            MemorySegment db,
            MemorySegment error
    );

    int hsDatabaseSize(MemorySegment database, MemorySegment databaseSize);

    int hsSerializeDatabase(MemorySegment db, MemorySegment bytes, MemorySegment length);

    int hsDeserializeDatabase(MemorySegment bytes, long length, MemorySegment db);

    int hsFreeCompileError(MemorySegment error);

    int hsExpressionInfo(MemorySegment expression, int flags, MemorySegment info, MemorySegment error);

    int hsFreeScratch(MemorySegment scratch);

    MemorySegment hsVersion();

    int hsValidPlatform();

    int hsScratchSize(MemorySegment scratch, MemorySegment scratchSize);

    int hsAllocScratch(MemorySegment db, MemorySegment scratch);

    int hsScan(
            MemorySegment db,
            MemorySegment data,
            int length,
            int flags,
            MemorySegment scratch,
            MemorySegment onEvent,
            MemorySegment context
    );

    void free(MemorySegment ptr);

    // ------------------------------------------------------------------
    // Callback helpers
    // ------------------------------------------------------------------
    @FunctionalInterface
    interface MatchEventCallback {
        int onMatch(int id, long from, long to, int flags);
    }

    MemorySegment allocateMatchEventHandler(MatchEventCallback callback, Arena arena);
}
