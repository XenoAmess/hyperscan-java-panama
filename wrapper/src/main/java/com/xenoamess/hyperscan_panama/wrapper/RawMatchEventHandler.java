package com.xenoamess.hyperscan_panama.wrapper;

@FunctionalInterface
interface RawMatchEventHandler {
    boolean onMatch(int expressionId, long fromByteIdx, long toByteIdxExclusive, int expressionFlags);
}
