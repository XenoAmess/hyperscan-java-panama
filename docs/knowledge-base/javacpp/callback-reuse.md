# JavaCPP FunctionPointer Callback Reuse

When benchmarking or testing Hyperscan via the JavaCPP bindings, do **not** create a new `match_event_handler` (or any other `FunctionPointer` callback) for every `hs_scan` call.

## Problem

Allocating a fresh anonymous callback per scan looks convenient:

```java
public static List<Match> hsScan(hs_database_t database, String input) {
    hs_scratch_t scratch = new hs_scratch_t();
    hs_alloc_scratch(database, scratch);

    List<Match> matches = new ArrayList<>();
    match_event_handler handler = new match_event_handler() {
        @Override
        public int call(int id, long from, long to, int flags, Pointer context) {
            matches.add(new Match(id, from, to));
            return 0;
        }
    };

    hs_scan(database, input, input.length(), 0, scratch, handler, null);
    hs_free_scratch(scratch);
    return matches;
}
```

After a small number of scans (in our case ~20) the callback stops firing and `hs_scan` returns success with **zero matches**. The native library is fine; the issue is that JavaCPP `FunctionPointer` instances are tied to native trampolines that become unreliable when repeatedly allocated and discarded.

## Solution

Use a single stable callback instance and pass the per-scan state through a `ThreadLocal` context:

```java
private static final ThreadLocal<List<Match>> CURRENT_MATCHES =
        ThreadLocal.withInitial(ArrayList::new);

private static final match_event_handler HANDLER = new match_event_handler() {
    @Override
    public int call(int id, long from, long to, int flags, Pointer context) {
        CURRENT_MATCHES.get().add(new Match(id, from, to));
        return 0;
    }
};

public static List<Match> hsScan(hs_database_t database, String input) {
    hs_scratch_t scratch = new hs_scratch_t();
    hs_alloc_scratch(database, scratch);

    List<Match> matches = CURRENT_MATCHES.get();
    matches.clear();

    hs_scan(database, input, input.length(), 0, scratch, HANDLER, null);
    hs_free_scratch(scratch);

    return new ArrayList<>(matches);
}
```

The `HANDLER` is kept alive by the static reference, so the underlying native trampoline remains valid across all scans. Each scan still allocates and frees its own `hs_scratch_t`, which is safe and matches the original helper design.

## Result

With the stable callback, the 20-iteration global warm-up in `InstructionSetGranularityTest` works correctly and the benchmark reports the expected number of matches (2773 for the fixed 500-pattern/20 KB workload) instead of zero.

## See Also

- `hyperscan-java-test/src/test/java/com/xenoamess/hyperscan/smoke/HyperscanTestHelper.java`
- `hyperscan-java-test/src/test/java/com/xenoamess/hyperscan/smoke/InstructionSetGranularityTest.java`
