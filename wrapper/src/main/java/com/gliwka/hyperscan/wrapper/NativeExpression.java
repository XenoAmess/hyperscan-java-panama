package com.gliwka.hyperscan.wrapper;

import lombok.AccessLevel;
import lombok.Getter;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

class NativeExpression {
    @Getter(AccessLevel.PACKAGE)
    private final MemorySegment expressionBytes;

    @Getter(AccessLevel.PACKAGE)
    private final Expression expression;

    NativeExpression(Expression expression, Arena arena) {
        this.expressionBytes = arena.allocateFrom(expression.getExpression());
        this.expression = expression;
    }
}
