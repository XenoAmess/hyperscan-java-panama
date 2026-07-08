package com.xenoamess.hyperscan_panama.wrapper;

import com.xenoamess.hyperscan_panama.jni.HyperscanNativeLoader;
import com.xenoamess.hyperscan_panama.jni.generated.hyperscan;
import com.xenoamess.hyperscan_panama.jni.generated.match_event_handler;
import com.xenoamess.hyperscan_panama.wrapper.mapping.ByteCharMapping;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

import static java.util.Collections.emptyList;

public class Scanner implements Closeable {
    static {
        HyperscanNativeLoader.load();
    }

    private static final ThreadLocal<RawMatchEventHandler> activeCallback = new ThreadLocal<>();

    private static final Arena CALLBACK_ARENA = Arena.global();
    private static final MemorySegment MATCH_HANDLER = match_event_handler.allocate(
            (id, from, to, flags, context) -> {
                RawMatchEventHandler handler = activeCallback.get();
                return handler.onMatch(id, from, to, flags) ? 0 : -1;
            }, CALLBACK_ARENA);

    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

    private final State state;
    private final java.lang.ref.Cleaner.Cleanable cleanable;

    private static class State implements Runnable {
        private MemorySegment scratch;

        State() {
            this.scratch = MemorySegment.NULL;
        }

        synchronized MemorySegment getScratch() {
            if (scratch == null) {
                throw new IllegalStateException("Scratch space has already been deallocated");
            }
            return scratch;
        }

        synchronized void setScratch(MemorySegment scratch) {
            this.scratch = scratch;
        }

        @Override
        public synchronized void run() {
            if (scratch != null && scratch.address() != 0) {
                hyperscan.hs_free_scratch(scratch);
                scratch = null;
            }
        }
    }

    public Scanner() {
        this.state = new State();
        this.cleanable = CLEANER.register(this, state);
    }

    public static boolean getIsValidPlatform() {
        return hyperscan.hs_valid_platform() == 0;
    }

    public static String getVersion() {
        MemorySegment version = hyperscan.hs_version();
        try (Arena arena = Arena.ofConfined()) {
            return version.reinterpret(256, arena, null).getString(0);
        }
    }

    public long getSize() {
        MemorySegment scratch = state.getScratch();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment size = arena.allocate(hyperscan.size_t);
            int hsError = hyperscan.hs_scratch_size(scratch, size);
            if (hsError != 0) {
                throw HyperscanException.hsErrorToException(hsError);
            }
            return size.get(hyperscan.size_t, 0);
        }
    }

    public void allocScratch(final Database db) {
        synchronized (state) {
            MemorySegment currentScratch = state.getScratch();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment scratchOut = arena.allocate(ValueLayout.ADDRESS);
                if (currentScratch.address() != 0) {
                    scratchOut.set(ValueLayout.ADDRESS, 0, currentScratch);
                }
                int hsError = hyperscan.hs_alloc_scratch(db.getDatabase(), scratchOut);
                if (hsError != 0) {
                    throw HyperscanException.hsErrorToException(hsError);
                }
                state.setScratch(scratchOut.get(ValueLayout.ADDRESS, 0));
            }
        }
    }

    public List<Match> scan(final Database db, final String input) {
        final LinkedList<Match> matches = new LinkedList<>();

        scan(db, input, (expression, fromStringIndexLong, toStringIndexLong) -> {
            String match = "";
            if (expression.getFlags().contains(ExpressionFlag.SOM_LEFTMOST)) {
                match = input.substring((int) fromStringIndexLong, (int) toStringIndexLong + 1);
            }

            matches.add(new Match((int) fromStringIndexLong, (int) toStringIndexLong, match, expression));
            return true;
        });

        return matches.isEmpty() ? emptyList() : matches;
    }

    public void scan(final Database db, final String input, StringMatchEventHandler eventHandler) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(input.length() * 4);
        final ByteCharMapping mapping = Utf8Encoder.encodeToBufferAndMap(byteBuffer, input);

        scan(db, byteBuffer, (expressionId, fromByteIdx, toByteIdx, flags) -> {
            Expression expression = db.getExpression(expressionId);
            long fromStringIndex = mapping.getMappingSize() > 0 ? mapping.getCharIndex((int) fromByteIdx) : 0;
            long toStringIndex = 0;

            if (toByteIdx > 0) {
                toStringIndex = mapping.getMappingSize() > 0 ? mapping.getCharIndex((int) toByteIdx - 1) : 0;
            }

            return eventHandler.onMatch(expression, fromStringIndex, toStringIndex);
        });
    }

    public void scan(final Database db, final byte[] input, ByteMatchEventHandler eventHandler) {
        scan(db, ByteBuffer.wrap(input),
                (expressionId, fromByteIdx, toByteIdx, expressionFlags) ->
                        eventHandler.onMatch(db.getExpression(expressionId), fromByteIdx, toByteIdx)
        );
    }

    private int scan(final Database db, final ByteBuffer input, RawMatchEventHandler eventHandler) {
        if (activeCallback.get() != null) {
            throw new IllegalStateException("Recursive scanning is not supported.");
        }

        activeCallback.set(eventHandler);

        try {
            MemorySegment database = db.getDatabase();
            MemorySegment scratch = state.getScratch();
            if (scratch.address() == 0) {
                throw new IllegalStateException("Scratch space has not been allocated. Call allocScratch() before scanning.");
            }
            int position = input.position();
            int length = input.remaining();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment data = getNativeSegment(arena, input, position, length);
                int hsError = hyperscan.hs_scan(database, data, length, 0, scratch, MATCH_HANDLER, MemorySegment.NULL);
                if (hsError != 0 && hsError != hyperscan.HS_SCAN_TERMINATED()) {
                    throw HyperscanException.hsErrorToException(hsError);
                }
                return hsError;
            }
        } finally {
            activeCallback.remove();
        }
    }

    private static MemorySegment getNativeSegment(Arena arena, ByteBuffer input, int position, int length) {
        if (input.isDirect()) {
            return MemorySegment.ofBuffer(input).asSlice(position, length);
        } else {
            byte[] array = input.array();
            int offset = input.arrayOffset() + position;
            MemorySegment source = MemorySegment.ofArray(array).asSlice(offset, length);
            MemorySegment segment = arena.allocate(length);
            segment.copyFrom(source);
            return segment;
        }
    }

    public boolean hasMatch(final Database db, final ByteBuffer input) {
        RawMatchEventHandler terminationHandler = (expressionId, fromByteIdx, toByteIdx, flags) -> false;

        int hsError = scan(db, input, terminationHandler);
        return hsError == hyperscan.HS_SCAN_TERMINATED();
    }

    public boolean hasMatch(final Database db, final byte[] input) {
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(input.length);
        directBuffer.put(input);
        ((Buffer) directBuffer).flip();
        return hasMatch(db, directBuffer);
    }

    public boolean hasMatch(final Database db, final String input) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(input.length() * 4);
        Utf8Encoder.encodeToBufferAndMap(byteBuffer, input);
        return hasMatch(db, byteBuffer);
    }

    @Override
    public void close() throws IOException {
        cleanable.clean();
    }
}
