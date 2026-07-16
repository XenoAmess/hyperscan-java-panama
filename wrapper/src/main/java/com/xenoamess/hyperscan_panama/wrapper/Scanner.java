package com.xenoamess.hyperscan_panama.wrapper;

import com.xenoamess.hyperscan_panama.jni.HyperscanJni;
import com.xenoamess.hyperscan_panama.jni.HyperscanNativeLoader;
import com.xenoamess.hyperscan_panama.wrapper.mapping.ByteCharMapping;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import sun.misc.Unsafe;

import static java.util.Collections.emptyList;

public class Scanner implements Closeable {
    static {
        HyperscanNativeLoader.load();
    }

    private static final Unsafe UNSAFE = getUnsafe();
    private static final long STRING_VALUE_OFFSET = objectFieldOffset(String.class, "value");
    private static final long STRING_CODER_OFFSET = objectFieldOffset(String.class, "coder");

    private static Unsafe getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long objectFieldOffset(Class<?> clazz, String name) {
        try {
            return UNSAFE.objectFieldOffset(clazz.getDeclaredField(name));
        } catch (NoSuchFieldException e) {
            return -1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final HyperscanJni JNI = HyperscanNativeLoader.loadJni();

    private static final Arena CALLBACK_ARENA = Arena.global();
    private static final Arena SCAN_BUFFER_ARENA = Arena.global();
    private static final ThreadLocal<MemorySegment> SCAN_BUFFER = ThreadLocal.withInitial(() -> MemorySegment.NULL);
    private static final ThreadLocal<ByteBuffer> NON_ASCII_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(0));
    private static final ThreadLocal<MemorySegment> SCRATCH_SIZE_BUFFER = ThreadLocal.withInitial(
            () -> Arena.global().allocate(JNI.size_t())
    );

    private static final ThreadLocal<DirectBufferCache> DIRECT_BUFFER_CACHE = ThreadLocal.withInitial(DirectBufferCache::new);

    private static final class DirectBufferCache {
        ByteBuffer buffer;
        int position = -1;
        int limit = -1;
        MemorySegment segment = MemorySegment.NULL;
    }

    private static MemorySegment directBufferSegment(ByteBuffer input) {
        DirectBufferCache cache = DIRECT_BUFFER_CACHE.get();
        if (cache.buffer == input && cache.position == input.position() && cache.limit == input.limit()) {
            return cache.segment;
        }
        MemorySegment segment = MemorySegment.ofBuffer(input).asSlice(input.position(), input.remaining());
        cache.buffer = input;
        cache.position = input.position();
        cache.limit = input.limit();
        cache.segment = segment;
        return segment;
    }

    private static final class CallbackContext {
        Database db;
        Expression[] expressionsById;
        Database.IntExpressionMap sparseExpressions;
        ByteMatchEventHandler byteHandler;
        StringMatchEventHandler stringHandler;
        RawMatchEventHandler rawHandler;
        ByteCharMapping mapping;
        boolean inUse;
    }

    private static final ThreadLocal<CallbackContext> ACTIVE_CONTEXT = ThreadLocal.withInitial(CallbackContext::new);

    private static final MemorySegment MATCH_HANDLER = JNI.allocateMatchEventHandler(
            (id, from, to, flags) -> {
                CallbackContext ctx = ACTIVE_CONTEXT.get();
                if (ctx.rawHandler != null) {
                    return ctx.rawHandler.onMatch(id, from, to, flags) ? 0 : -1;
                }
                Expression expression = null;
                Expression[] byId = ctx.expressionsById;
                if (id >= 0 && id < byId.length) {
                    expression = byId[id];
                } else if (ctx.sparseExpressions != null) {
                    expression = ctx.sparseExpressions.get(id);
                }
                if (expression == null) {
                    return 0;
                }
                if (ctx.byteHandler != null) {
                    return ctx.byteHandler.onMatch(expression, from, to) ? 0 : -1;
                }
                if (ctx.stringHandler != null) {
                    long fromStringIndex = ctx.mapping != null ? ctx.mapping.getCharIndex((int) from) : from;
                    long toStringIndex = 0;
                    if (to > 0) {
                        toStringIndex = ctx.mapping != null ? ctx.mapping.getCharIndex((int) (to - 1)) : to - 1;
                    }
                    return ctx.stringHandler.onMatch(expression, fromStringIndex, toStringIndex) ? 0 : -1;
                }
                return 0;
            }, CALLBACK_ARENA);

    private static final RawMatchEventHandler TERMINATION_HANDLER = (expressionId, fromByteIdx, toByteIdx, flags) -> false;

    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

    private final State state;
    private final java.lang.ref.Cleaner.Cleanable cleanable;

    private static class State implements Runnable {
        private volatile MemorySegment scratch;

        State() {
            this.scratch = MemorySegment.NULL;
        }

        MemorySegment getScratch() {
            if (scratch == null) {
                throw new IllegalStateException("Scratch space has already been deallocated");
            }
            return scratch;
        }

        void setScratch(MemorySegment scratch) {
            this.scratch = scratch;
        }

        @Override
        public synchronized void run() {
            if (scratch != null && scratch.address() != 0) {
                JNI.hsFreeScratch(scratch);
                scratch = null;
            }
        }
    }

    public Scanner() {
        this.state = new State();
        this.cleanable = CLEANER.register(this, state);
    }

    public static boolean getIsValidPlatform() {
        return JNI.hsValidPlatform() == 0;
    }

    public static String getVersion() {
        MemorySegment version = JNI.hsVersion();
        try (Arena arena = Arena.ofConfined()) {
            return version.reinterpret(256, arena, null).getString(0);
        }
    }

    public long getSize() {
        MemorySegment scratch = state.getScratch();
        MemorySegment size = SCRATCH_SIZE_BUFFER.get();
        int hsError = JNI.hsScratchSize(scratch, size);
        if (hsError != 0) {
            throw HyperscanException.hsErrorToException(hsError);
        }
        return JNI.readSize_t(size, 0);
    }

    public void allocScratch(final Database db) {
        synchronized (state) {
            MemorySegment currentScratch = state.getScratch();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment scratchOut = arena.allocate(ValueLayout.ADDRESS);
                if (currentScratch.address() != 0) {
                    scratchOut.set(ValueLayout.ADDRESS, 0, currentScratch);
                }
                int hsError = JNI.hsAllocScratch(db.getDatabase(), scratchOut);
                if (hsError != 0) {
                    throw HyperscanException.hsErrorToException(hsError);
                }
                state.setScratch(scratchOut.get(ValueLayout.ADDRESS, 0));
            }
        }
    }

    public List<Match> scan(final Database db, final String input) {
        final ArrayList<Match> matches = new ArrayList<>();

        CallbackContext ctx = ACTIVE_CONTEXT.get();
        ctx.db = db;
        ctx.stringHandler = (expression, fromStringIndexLong, toStringIndexLong) -> {
            if (expression.getFlags().contains(ExpressionFlag.SOM_LEFTMOST)) {
                matches.add(new Match(input, (int) fromStringIndexLong, (int) toStringIndexLong, expression));
            } else {
                matches.add(new Match((int) fromStringIndexLong, (int) toStringIndexLong, "", expression));
            }
            return true;
        };
        ctx.byteHandler = null;
        ctx.rawHandler = null;

        if (isAscii(input)) {
            ctx.mapping = null;
            scanRaw(db, getAsciiSegment(input), input.length());
        } else {
            ByteBuffer byteBuffer = getNonAsciiBuffer(input);
            final ByteCharMapping mapping = Utf8Encoder.encodeToBufferAndMap(byteBuffer, input);
            ctx.mapping = mapping;
            scanRaw(db, byteBuffer);
        }

        return matches.isEmpty() ? emptyList() : matches;
    }

    public void scan(final Database db, final String input, StringMatchEventHandler eventHandler) {
        CallbackContext ctx = ACTIVE_CONTEXT.get();
        ctx.db = db;
        ctx.stringHandler = eventHandler;
        ctx.byteHandler = null;
        ctx.rawHandler = null;

        if (isAscii(input)) {
            ctx.mapping = null;
            scanRaw(db, getAsciiSegment(input), input.length());
            return;
        }

        ByteBuffer byteBuffer = getNonAsciiBuffer(input);
        final ByteCharMapping mapping = Utf8Encoder.encodeToBufferAndMap(byteBuffer, input);
        ctx.mapping = mapping;
        scanRaw(db, byteBuffer);
    }

    public void scan(final Database db, final byte[] input, ByteMatchEventHandler eventHandler) {
        CallbackContext ctx = ACTIVE_CONTEXT.get();
        ctx.db = db;
        ctx.byteHandler = eventHandler;
        ctx.stringHandler = null;
        ctx.rawHandler = null;
        ctx.mapping = null;
        scanRaw(db, input);
    }

    public void scan(final Database db, final ByteBuffer input, ByteMatchEventHandler eventHandler) {
        CallbackContext ctx = ACTIVE_CONTEXT.get();
        ctx.db = db;
        ctx.byteHandler = eventHandler;
        ctx.stringHandler = null;
        ctx.rawHandler = null;
        ctx.mapping = null;

        int position = input.position();
        int length = input.remaining();
        if (input.isDirect()) {
            MemorySegment data = directBufferSegment(input);
            scanRaw(db, data, length);
        } else {
            MemorySegment data = getScanBuffer(input, position, length);
            scanRaw(db, data, length);
        }
    }

    public void scan(final Database db, final MemorySegment input, final int length, ByteMatchEventHandler eventHandler) {
        CallbackContext ctx = ACTIVE_CONTEXT.get();
        ctx.db = db;
        ctx.byteHandler = eventHandler;
        ctx.stringHandler = null;
        ctx.rawHandler = null;
        ctx.mapping = null;
        scanRaw(db, input, length);
    }

    private static MemorySegment getScanBuffer(byte[] data) {
        return getScanBuffer(data, 0, data.length);
    }

    private static MemorySegment getScanBuffer(byte[] data, int offset, int length) {
        MemorySegment buffer = SCAN_BUFFER.get();
        if (buffer == MemorySegment.NULL || buffer.byteSize() < length) {
            buffer = SCAN_BUFFER_ARENA.allocate(Math.max(length, 1), 64);
            SCAN_BUFFER.set(buffer);
        }
        UNSAFE.copyMemory(data, Unsafe.ARRAY_BYTE_BASE_OFFSET + offset, null, buffer.address(), length);
        return buffer;
    }

    private static MemorySegment getScanBuffer(ByteBuffer input, int position, int length) {
        MemorySegment buffer = SCAN_BUFFER.get();
        if (buffer == MemorySegment.NULL || buffer.byteSize() < length) {
            buffer = SCAN_BUFFER_ARENA.allocate(Math.max(length, 1), 64);
            SCAN_BUFFER.set(buffer);
        }
        UNSAFE.copyMemory(input.array(), Unsafe.ARRAY_BYTE_BASE_OFFSET + input.arrayOffset() + position,
                null, buffer.address(), length);
        return buffer;
    }

    private static MemorySegment getAsciiSegment(String input) {
        int length = input.length();
        MemorySegment buffer = SCAN_BUFFER.get();
        if (buffer == MemorySegment.NULL || buffer.byteSize() < length) {
            buffer = SCAN_BUFFER_ARENA.allocate(length, 64);
            SCAN_BUFFER.set(buffer);
        }
        if (STRING_VALUE_OFFSET >= 0 && STRING_CODER_OFFSET >= 0
                && UNSAFE.getByte(input, STRING_CODER_OFFSET) == 0) {
            byte[] value = (byte[]) UNSAFE.getObject(input, STRING_VALUE_OFFSET);
            UNSAFE.copyMemory(value, Unsafe.ARRAY_BYTE_BASE_OFFSET, null, buffer.address(), length);
        } else {
            long address = buffer.address();
            for (int i = 0; i < length; i++) {
                UNSAFE.putByte(address + i, (byte) input.charAt(i));
            }
        }
        return buffer;
    }

    private static ByteBuffer getNonAsciiBuffer(String input) {
        int required = input.length() * 4;
        ByteBuffer buffer = NON_ASCII_BUFFER.get();
        if (buffer.capacity() < required) {
            buffer = ByteBuffer.allocateDirect(required);
            NON_ASCII_BUFFER.set(buffer);
        } else {
            buffer.clear();
        }
        // No slice(): the encoder flips the buffer at the end, so the limit is
        // set to the number of bytes written either way.
        return buffer;
    }

    private int scanRaw(final Database db, final byte[] data) {
        return scanRaw(db, data, 0, data.length);
    }

    private int scanRaw(final Database db, final byte[] data, int offset, int length) {
        MemorySegment segment = getScanBuffer(data, offset, length);
        return scanRaw(db, segment, length);
    }

    private int scanRaw(final Database db, final ByteBuffer input) {
        int position = input.position();
        int length = input.remaining();
        if (input.isDirect()) {
            MemorySegment data = MemorySegment.ofBuffer(input).asSlice(position, length);
            return scanRaw(db, data, length);
        }
        return scanRaw(db, getScanBuffer(input, position, length), length);
    }

    private int scanRaw(final Database db, final MemorySegment data, final int length) {
        CallbackContext ctx = ACTIVE_CONTEXT.get();
        if (ctx.inUse) {
            throw new IllegalStateException("Recursive scanning is not supported.");
        }

        ctx.inUse = true;
        ctx.expressionsById = db.getExpressionsById();
        ctx.sparseExpressions = db.getSparseExpressions();

        try {
            MemorySegment database = db.getDatabase();
            MemorySegment scratch = state.getScratch();
            if (scratch.address() == 0) {
                throw new IllegalStateException("Scratch space has not been allocated. Call allocScratch() before scanning.");
            }
            int hsError = JNI.hsScan(database, data, length, 0, scratch, MATCH_HANDLER, MemorySegment.NULL);
            if (hsError != 0 && hsError != JNI.hsScanTerminated()) {
                throw HyperscanException.hsErrorToException(hsError);
            }
            return hsError;
        } finally {
            ctx.inUse = false;
            ctx.rawHandler = null;
            ctx.byteHandler = null;
            ctx.stringHandler = null;
            ctx.mapping = null;
            ctx.expressionsById = null;
            ctx.sparseExpressions = null;
        }
    }

    private static boolean isAscii(String input) {
        if (STRING_VALUE_OFFSET >= 0 && STRING_CODER_OFFSET >= 0
                && UNSAFE.getByte(input, STRING_CODER_OFFSET) == 0) {
            byte[] value = (byte[]) UNSAFE.getObject(input, STRING_VALUE_OFFSET);
            int i = 0;
            int wordLimit = value.length & ~7;
            for (; i < wordLimit; i += 8) {
                if ((UNSAFE.getLong(value, Unsafe.ARRAY_BYTE_BASE_OFFSET + i) & 0x8080808080808080L) != 0) {
                    return false;
                }
            }
            for (; i < value.length; i++) {
                if (value[i] < 0) {
                    return false;
                }
            }
            return true;
        }
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    public boolean hasMatch(final Database db, final ByteBuffer input) {
        CallbackContext ctx = ACTIVE_CONTEXT.get();
        ctx.db = db;
        ctx.rawHandler = TERMINATION_HANDLER;
        ctx.byteHandler = null;
        ctx.stringHandler = null;
        ctx.mapping = null;
        int hsError = scanRaw(db, input);
        return hsError == JNI.hsScanTerminated();
    }

    public boolean hasMatch(final Database db, final byte[] input) {
        return hasMatch(db, input, 0, input.length);
    }

    public boolean hasMatch(final Database db, final String input) {
        if (isAscii(input)) {
            return hasMatch(db, getAsciiSegment(input), input.length());
        }
        ByteBuffer byteBuffer = getNonAsciiBuffer(input);
        Utf8Encoder.encodeToBufferAndMap(byteBuffer, input);
        return hasMatch(db, byteBuffer);
    }

    private boolean hasMatch(final Database db, final MemorySegment data, final int length) {
        CallbackContext ctx = ACTIVE_CONTEXT.get();
        ctx.db = db;
        ctx.rawHandler = TERMINATION_HANDLER;
        ctx.byteHandler = null;
        ctx.stringHandler = null;
        ctx.mapping = null;
        int hsError = scanRaw(db, data, length);
        return hsError == JNI.hsScanTerminated();
    }

    private boolean hasMatch(final Database db, final byte[] input, int offset, int length) {
        CallbackContext ctx = ACTIVE_CONTEXT.get();
        ctx.db = db;
        ctx.rawHandler = TERMINATION_HANDLER;
        ctx.byteHandler = null;
        ctx.stringHandler = null;
        ctx.mapping = null;
        int hsError = scanRaw(db, input, offset, length);
        return hsError == JNI.hsScanTerminated();
    }

    /**
     * Scans a sequence of byte arrays as one logical input using the vectored
     * scanning mode. The segments are matched as if concatenated, and reported
     * byte indices are relative to the start of the first segment.
     * Requires a database compiled with {@link Mode#VECTORED}.
     * Neither the array nor its elements may be null.
     *
     * @param db           Database containing expressions to use for matching.
     * @param inputs       Segments to match against.
     * @param eventHandler Handler to receive match events with byte indices.
     */
    public void scanVector(final Database db, final byte[][] inputs, ByteMatchEventHandler eventHandler) {
        int total = 0;
        for (byte[] input : inputs) {
            total += input.length;
        }
        MemorySegment buffer = SCAN_BUFFER.get();
        if (buffer == MemorySegment.NULL || buffer.byteSize() < total) {
            buffer = SCAN_BUFFER_ARENA.allocate(Math.max(total, 1), 64);
            SCAN_BUFFER.set(buffer);
        }
        long offset = 0;
        for (byte[] input : inputs) {
            UNSAFE.copyMemory(input, Unsafe.ARRAY_BYTE_BASE_OFFSET, null, buffer.address() + offset, input.length);
            offset += input.length;
        }
        int[] lengths = new int[inputs.length];
        long[] starts = new long[inputs.length];
        offset = 0;
        for (int i = 0; i < inputs.length; i++) {
            lengths[i] = inputs[i].length;
            starts[i] = offset;
            offset += inputs[i].length;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dataPtrs = arena.allocate(ValueLayout.ADDRESS, inputs.length);
            MemorySegment lengthSeg = arena.allocate(ValueLayout.JAVA_INT, inputs.length);
            for (int i = 0; i < inputs.length; i++) {
                dataPtrs.setAtIndex(ValueLayout.ADDRESS, i, buffer.asSlice(starts[i], lengths[i]));
                lengthSeg.setAtIndex(ValueLayout.JAVA_INT, i, lengths[i]);
            }
            scanVectorRaw(db, dataPtrs, lengthSeg, inputs.length, eventHandler);
        }
    }

    /**
     * Scans a sequence of {@link ByteBuffer}s as one logical input using the
     * vectored scanning mode. For every segment, bytes from position to limit
     * are scanned; positions and limits are not modified.
     * Direct segments are scanned zero-copy; heap segments are first copied
     * into a reused per-thread native buffer.
     * The segments are matched as if concatenated, and reported byte indices
     * are relative to the start of the first segment.
     * Requires a database compiled with {@link Mode#VECTORED}.
     * Neither the array nor its elements may be null.
     *
     * @param db           Database containing expressions to use for matching.
     * @param inputs       Segments to match against.
     * @param eventHandler Handler to receive match events with byte indices.
     */
    public void scanVector(final Database db, final ByteBuffer[] inputs, ByteMatchEventHandler eventHandler) {
        int heapTotal = 0;
        for (ByteBuffer input : inputs) {
            if (!input.isDirect()) {
                heapTotal += input.remaining();
            }
        }
        MemorySegment bulk = null;
        if (heapTotal > 0) {
            bulk = SCAN_BUFFER.get();
            if (bulk == MemorySegment.NULL || bulk.byteSize() < heapTotal) {
                bulk = SCAN_BUFFER_ARENA.allocate(heapTotal, 64);
                SCAN_BUFFER.set(bulk);
            }
        }
        int n = inputs.length;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dataPtrs = arena.allocate(ValueLayout.ADDRESS, n);
            MemorySegment lengthSeg = arena.allocate(ValueLayout.JAVA_INT, n);
            long bulkOffset = 0;
            for (int i = 0; i < n; i++) {
                ByteBuffer input = inputs[i];
                int length = input.remaining();
                lengthSeg.setAtIndex(ValueLayout.JAVA_INT, i, length);
                if (input.isDirect()) {
                    dataPtrs.setAtIndex(ValueLayout.ADDRESS, i,
                            MemorySegment.ofBuffer(input).asSlice(input.position(), length));
                } else {
                    UNSAFE.copyMemory(input.array(), Unsafe.ARRAY_BYTE_BASE_OFFSET + input.arrayOffset() + input.position(),
                            null, bulk.address() + bulkOffset, length);
                    dataPtrs.setAtIndex(ValueLayout.ADDRESS, i, bulk.asSlice(bulkOffset, length));
                    bulkOffset += length;
                }
            }
            scanVectorRaw(db, dataPtrs, lengthSeg, n, eventHandler);
        }
    }

    private int scanVectorRaw(final Database db, final MemorySegment dataPtrs, final MemorySegment lengthSeg,
                              final int count, ByteMatchEventHandler eventHandler) {
        if (db.getMode() != null && db.getMode() != Mode.VECTORED) {
            throw new IllegalArgumentException("Vectored scanning requires a database compiled with Mode.VECTORED");
        }
        CallbackContext ctx = ACTIVE_CONTEXT.get();
        if (ctx.inUse) {
            throw new IllegalStateException("Recursive scanning is not supported.");
        }
        ctx.inUse = true;
        ctx.db = db;
        ctx.byteHandler = eventHandler;
        ctx.stringHandler = null;
        ctx.rawHandler = null;
        ctx.mapping = null;
        ctx.expressionsById = db.getExpressionsById();
        ctx.sparseExpressions = db.getSparseExpressions();
        try {
            MemorySegment scratch = state.getScratch();
            if (scratch.address() == 0) {
                throw new IllegalStateException("Scratch space has not been allocated. Call allocScratch() before scanning.");
            }
            int hsError = JNI.hsScanVector(db.getDatabase(), dataPtrs, lengthSeg, count, 0, scratch, MATCH_HANDLER, MemorySegment.NULL);
            if (hsError != 0 && hsError != JNI.hsScanTerminated()) {
                throw HyperscanException.hsErrorToException(hsError);
            }
            return hsError;
        } finally {
            ctx.inUse = false;
            ctx.rawHandler = null;
            ctx.byteHandler = null;
            ctx.stringHandler = null;
            ctx.mapping = null;
            ctx.expressionsById = null;
            ctx.sparseExpressions = null;
        }
    }

    /**
     * Opens a streaming scan session over the given database. The returned
     * stream shares this scanner's scratch space and must be closed after use,
     * preferably with try-with-resources. Closing without a handler discards
     * pending matches; use {@link Stream#close(ByteMatchEventHandler)} to
     * receive them.
     * Not thread-safe, just like the owning scanner.
     * Requires a database compiled with {@link Mode#STREAM}.
     *
     * @param db Database containing expressions to use for matching.
     * @return Open stream ready for scanning.
     */
    public Stream openStream(final Database db) {
        if (db.getMode() != null && db.getMode() != Mode.STREAM) {
            throw new IllegalArgumentException("Streaming requires a database compiled with Mode.STREAM");
        }
        MemorySegment nativeStream;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment streamOut = arena.allocate(ValueLayout.ADDRESS);
            int hsError = JNI.hsOpenStream(db.getDatabase(), 0, streamOut);
            if (hsError != 0) {
                throw HyperscanException.hsErrorToException(hsError);
            }
            nativeStream = streamOut.get(ValueLayout.ADDRESS, 0);
        }
        return new Stream(db, nativeStream);
    }

    /**
     * A streaming scan session created by {@link Scanner#openStream(Database)}.
     * Input is fed in chunks via {@link #scan(byte[], ByteMatchEventHandler)}
     * or {@link #scan(ByteBuffer, ByteMatchEventHandler)}; matches are reported
     * with byte offsets relative to the start of the stream, so patterns
     * spanning chunk boundaries are matched transparently.
     * Not thread-safe; the stream shares the owning scanner's scratch space.
     */
    public class Stream implements Closeable {
        private final Database database;
        private MemorySegment nativeStream;

        private Stream(final Database database, final MemorySegment nativeStream) {
            this.database = database;
            this.nativeStream = nativeStream;
        }

        /**
         * Feeds one chunk of input to the stream.
         *
         * @param input        Chunk to match against, may be empty (flush only).
         * @param eventHandler Handler to receive match events with byte indices.
         */
        public void scan(final byte[] input, ByteMatchEventHandler eventHandler) {
            ensureOpen();
            MemorySegment data = getScanBuffer(input, 0, input.length);
            scanStreamRaw(data, input.length, eventHandler);
        }

        /**
         * Feeds one chunk of input to the stream. Bytes from the buffer's
         * current position to its limit are scanned; position and limit are
         * not modified. Direct buffers are scanned zero-copy, heap buffers are
         * first copied into a reused per-thread native buffer.
         *
         * @param input        Chunk to match against.
         * @param eventHandler Handler to receive match events with byte indices.
         */
        public void scan(final ByteBuffer input, ByteMatchEventHandler eventHandler) {
            ensureOpen();
            int position = input.position();
            int length = input.remaining();
            if (input.isDirect()) {
                scanStreamRaw(MemorySegment.ofBuffer(input).asSlice(position, length), length, eventHandler);
            } else {
                scanStreamRaw(getScanBuffer(input, position, length), length, eventHandler);
            }
        }

        /**
         * Closes the stream and discards any pending matches.
         */
        @Override
        public void close() {
            if (nativeStream != null) {
                MemorySegment stream = nativeStream;
                nativeStream = null;
                int hsError = JNI.hsCloseStream(stream, state.getScratch(), MemorySegment.NULL, MemorySegment.NULL);
                if (hsError != 0 && hsError != JNI.hsScanTerminated()) {
                    throw HyperscanException.hsErrorToException(hsError);
                }
            }
        }

        /**
         * Closes the stream, reporting any matches still pending at the end of
         * the stream to the given handler.
         *
         * @param eventHandler Handler to receive trailing match events.
         */
        public void close(final ByteMatchEventHandler eventHandler) {
            if (nativeStream == null) {
                return;
            }
            CallbackContext ctx = ACTIVE_CONTEXT.get();
            if (ctx.inUse) {
                throw new IllegalStateException("Recursive scanning is not supported.");
            }
            ctx.inUse = true;
            ctx.db = database;
            ctx.byteHandler = eventHandler;
            ctx.stringHandler = null;
            ctx.rawHandler = null;
            ctx.mapping = null;
            ctx.expressionsById = database.getExpressionsById();
            ctx.sparseExpressions = database.getSparseExpressions();
            MemorySegment stream = nativeStream;
            nativeStream = null;
            try {
                int hsError = JNI.hsCloseStream(stream, state.getScratch(), MATCH_HANDLER, MemorySegment.NULL);
                if (hsError != 0 && hsError != JNI.hsScanTerminated()) {
                    throw HyperscanException.hsErrorToException(hsError);
                }
            } finally {
                ctx.inUse = false;
                ctx.byteHandler = null;
                ctx.expressionsById = null;
                ctx.sparseExpressions = null;
            }
        }

        private void scanStreamRaw(final MemorySegment data, final int length, ByteMatchEventHandler eventHandler) {
            CallbackContext ctx = ACTIVE_CONTEXT.get();
            if (ctx.inUse) {
                throw new IllegalStateException("Recursive scanning is not supported.");
            }
            ctx.inUse = true;
            ctx.db = database;
            ctx.byteHandler = eventHandler;
            ctx.stringHandler = null;
            ctx.rawHandler = null;
            ctx.mapping = null;
            ctx.expressionsById = database.getExpressionsById();
            ctx.sparseExpressions = database.getSparseExpressions();
            try {
                MemorySegment scratch = state.getScratch();
                if (scratch.address() == 0) {
                    throw new IllegalStateException("Scratch space has not been allocated. Call allocScratch() before scanning.");
                }
                int hsError = JNI.hsScanStream(nativeStream, data, length, 0, scratch, MATCH_HANDLER, MemorySegment.NULL);
                if (hsError != 0 && hsError != JNI.hsScanTerminated()) {
                    throw HyperscanException.hsErrorToException(hsError);
                }
            } finally {
                ctx.inUse = false;
                ctx.byteHandler = null;
                ctx.expressionsById = null;
                ctx.sparseExpressions = null;
            }
        }

        private void ensureOpen() {
            if (nativeStream == null) {
                throw new IllegalStateException("Stream is already closed");
            }
        }
    }

    @Override
    public void close() throws IOException {
        cleanable.clean();
    }
}
