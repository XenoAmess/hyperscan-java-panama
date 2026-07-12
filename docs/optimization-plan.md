# Performance Optimization Plan

This document tracks the performance optimization work for `hyperscan-java-panama`. Each item includes the target code, the optimization strategy, functional tests, and the performance benchmark used to verify improvement.

## Optimization Checklist

| # | Optimization | Target Files | Functional Tests | Performance Benchmark | Status |
|---|-------------|--------------|-------------------|----------------------|--------|
| 1 | Cache `Expression.getFlagBits()` | `wrapper/Expression.java` | `ExpressionTest` | `ExpressionFlagBitsBenchmark` | completed |
| 2 | Avoid large byte[] copy in `Database.save()` | `wrapper/Database.java` | `DatabaseTest` | `DatabaseSerializationBenchmark` | completed |
| 3 | LATIN1 byte-level `Scanner.isAscii()` check | `wrapper/Scanner.java` | `ScannerTest` | `ScannerIsAsciiBenchmark` | completed |
| 4 | Reuse buffer in `Database.getSize()` and `Scanner.getSize()` | `wrapper/Database.java`, `wrapper/Scanner.java` | `DatabaseTest`, `ScannerTest` | `GetSizeBenchmark` | completed |
| 5 | Reduce allocation in `PatternFilter.filter()` | `wrapper/util/PatternFilter.java` | `PatternFilterTest` | `PatternFilterBenchmark` | completed |
| 6 | Cache platform selection in `HyperscanNativeLoader` | `native/HyperscanNativeLoader.java` | `NativeLoaderTest` | `NativeLoaderBenchmark` | completed |
| 7 | Avoid stream overhead in `NativeExpressionCollection` | `wrapper/NativeExpressionCollection.java` | `DatabaseTest` | `CompilationBenchmark` | completed |
| 8 | Batch initialize `ExpressionFlag` native values | `wrapper/ExpressionFlag.java` | `ExpressionTest` | `ExpressionFlagBenchmark` | completed |
| 9 | Improve sparse ID expression lookup | `wrapper/Database.java` | `DatabaseTest` | `SparseIdLookupBenchmark` | completed |
| 10 | Reduce `readErrorMessage()` 4096 reinterpret | `wrapper/Database.java`, `wrapper/Expression.java` | `DatabaseTest`, `ExpressionTest` | `ErrorMessageBenchmark` | completed |

## Status Legend

- `pending`: Not started
- `in_progress`: Implementation ongoing
- `completed`: Implementation, tests, and benchmark done; no regressions

## General Verification Steps

For each optimization:

1. Implement the change.
2. Add or update functional tests covering the affected path.
3. Add or update a JMH benchmark in `wrapper/src/test/java/.../benchmark/`.
4. Run `mvn verify -pl wrapper` to ensure functional tests pass.
5. Run the JMH benchmark to confirm improvement or at least no regression.
6. Update this checklist.

## Notes

- All changes must preserve the public API.
- No feature removal; only internal implementation improvements.
- Native error paths and fallback branches remain untouched unless explicitly part of the optimization.
