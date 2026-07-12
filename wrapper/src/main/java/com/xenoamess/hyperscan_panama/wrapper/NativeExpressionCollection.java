package com.xenoamess.hyperscan_panama.wrapper;

import lombok.AccessLevel;
import lombok.Getter;

import java.io.Closeable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

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

        this.nativeExpressions = new ArrayList<>(size);
        this.expressionsBytes = arena.allocate(ValueLayout.ADDRESS, size);
        this.nativeFlags = arena.allocate(ValueLayout.JAVA_INT, size);
        this.nativeIds = arena.allocate(ValueLayout.JAVA_INT, size);

        for (int i = 0; i < size; i++) {
            Expression expression = expressions.get(i);
            NativeExpression nativeExpression = new NativeExpression(expression, arena);
            nativeExpressions.add(nativeExpression);
            expressionsBytes.setAtIndex(ValueLayout.ADDRESS, i, nativeExpression.getExpressionBytes());
            nativeFlags.setAtIndex(ValueLayout.JAVA_INT, i, expression.getFlagBits());
            nativeIds.setAtIndex(ValueLayout.JAVA_INT, i, expressionWithId ? expression.getId() : i);
        }
    }

    @Override
    public void close() {
        arena.close();
    }
}
