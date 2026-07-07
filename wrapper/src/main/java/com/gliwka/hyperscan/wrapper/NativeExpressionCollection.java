package com.gliwka.hyperscan.wrapper;

import lombok.AccessLevel;
import lombok.Getter;

import java.io.Closeable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

import static java.util.stream.Collectors.toList;

class NativeExpressionCollection implements Closeable {
    @Getter(AccessLevel.PACKAGE)
    private final List<NativeExpression> nativeExpressions;

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

        boolean expressionWithoutId = expressions.stream().anyMatch(expression -> expression.getId() == null);
        boolean expressionWithId = expressions.stream().anyMatch(expression -> expression.getId() != null);

        if (expressionWithId && expressionWithoutId) {
            throw new IllegalStateException("You can't mix expressions with and without id's in a single database");
        }

        this.nativeExpressions = expressions.stream().map(e -> new NativeExpression(e, arena)).collect(toList());
        this.expressionsBytes = arena.allocate(ValueLayout.ADDRESS, size);
        for (int i = 0; i < size; i++) {
            expressionsBytes.setAtIndex(ValueLayout.ADDRESS, i, nativeExpressions.get(i).getExpressionBytes());
        }

        this.nativeFlags = arena.allocate(ValueLayout.JAVA_INT, size);
        this.nativeIds = arena.allocate(ValueLayout.JAVA_INT, size);

        for (int i = 0; i < size; i++) {
            Expression expression = expressions.get(i);
            nativeFlags.setAtIndex(ValueLayout.JAVA_INT, i, expression.getFlagBits());
            nativeIds.setAtIndex(ValueLayout.JAVA_INT, i, expressionWithId ? expression.getId() : i);
        }
    }

    @Override
    public void close() {
        arena.close();
    }
}
