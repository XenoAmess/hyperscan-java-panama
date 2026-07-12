package com.xenoamess.hyperscan_panama.wrapper;

import lombok.AccessLevel;
import lombok.Getter;

import java.io.Closeable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

class NativeExpressionCollection implements Closeable {
    @Getter(AccessLevel.PACKAGE)
    private final MemorySegment expressionsBytes;

    @Getter(AccessLevel.PACKAGE)
    private final MemorySegment nativeFlags;

    @Getter(AccessLevel.PACKAGE)
    private final MemorySegment nativeIds;

    @Getter(AccessLevel.PACKAGE)
    private final int size;

    private final Arena arena;

    NativeExpressionCollection(List<Expression> expressions) {
        this.arena = Arena.ofConfined();
        this.size = expressions.size();

        boolean expressionWithoutId = false;
        boolean expressionWithId = false;
        for (Expression expression : expressions) {
            if (expression.getId() == null) {
                expressionWithoutId = true;
            } else {
                expressionWithId = true;
            }
        }

        if (expressionWithId && expressionWithoutId) {
            throw new IllegalStateException("You can't mix expressions with and without id's in a single database");
        }

        this.expressionsBytes = arena.allocate(ValueLayout.ADDRESS, size);
        this.nativeFlags = arena.allocate(ValueLayout.JAVA_INT, size);
        this.nativeIds = arena.allocate(ValueLayout.JAVA_INT, size);

        // Bulk encode all pattern strings into a single native block to avoid O(n) separate allocations.
        byte[][] encoded = new byte[size][];
        long totalBytes = 0;
        for (int i = 0; i < size; i++) {
            byte[] bytes = expressions.get(i).getExpression().getBytes(StandardCharsets.UTF_8);
            encoded[i] = bytes;
            totalBytes += (long) bytes.length + 1L;
        }
        MemorySegment strings = arena.allocate(totalBytes);

        long offset = 0;
        for (int i = 0; i < size; i++) {
            Expression expression = expressions.get(i);
            byte[] bytes = encoded[i];
            long len = bytes.length;
            MemorySegment ptr = strings.asSlice(offset, len + 1L);
            ptr.copyFrom(MemorySegment.ofArray(bytes).asSlice(0, len));
            ptr.set(ValueLayout.JAVA_BYTE, len, (byte) 0);
            expressionsBytes.setAtIndex(ValueLayout.ADDRESS, i, ptr);
            nativeFlags.setAtIndex(ValueLayout.JAVA_INT, i, expression.getFlagBits());
            nativeIds.setAtIndex(ValueLayout.JAVA_INT, i, expressionWithId ? expression.getId() : i);
            offset += len + 1L;
        }
    }

    @Override
    public void close() {
        arena.close();
    }
}
