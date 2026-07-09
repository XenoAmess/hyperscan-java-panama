# Agent Instructions for hyperscan-java-panama

## Project Overview

- **Name**: `hyperscan-java-panama`
- **Goal**: Java bindings for the high-performance multiple regex library Hyperscan/VectorScan, replacing JavaCPP with Project Panama (FFM API).
- **Java**: JDK 25 (required for stable FFM API).
- **Build**: Maven 3.9+ multi-module project.
- **Modules**:
  - `native`: native library build, jextract bindings, `HyperscanNativeLoader`.
  - `wrapper`: public Java API (`com.xenoamess.hyperscan_panama.wrapper`).
  - `performance`: benchmarks and smoke tests.
- **Native variants**: Linux x86_64 (baseline / AVX2 / AVX-512), Linux ARM64 (baseline / SVE2), Windows x86_64 (baseline / AVX2).

## Knowledge Base

- **Location**: `docs/knowledge-base/` (written in Chinese).
- **Rule**: Always check relevant entries before starting non-trivial work.
- **Rule**: After solving any non-trivial bug or discovering a gotcha, document it in `docs/knowledge-base/`.
- **Rule**: Update `docs/AGENTS.md` Common Pitfalls when a new recurring issue is identified.
- **Entry points**:
  - `docs/knowledge-base/panama-ffm/pointer-output-arguments.md` — how to handle C `T**` output arguments in Panama FFM.
  - `docs/knowledge-base/panama-ffm/serialization-roundtrip.md` — serialization round-trip debugging case.
  - `docs/knowledge-base/panama-ffm/classifier-shadowing.md` — why multiple platform classifier JARs cannot share the same generated class package.
  - `docs/knowledge-base/javacpp/callback-reuse.md` — JavaCPP `FunctionPointer` callbacks must be reused across scans.

## Local Development

```bash
# Build and install native module locally (requires DETECTED_PLATFORM env var!)
DETECTED_PLATFORM=linux-x86_64-avx2 mvn install -pl native -DskipTests

# Build and test wrapper
DETECTED_PLATFORM=linux-x86_64-avx2 mvn verify -pl wrapper -am

# Run performance benchmarks
DETECTED_PLATFORM=linux-x86_64-avx2 mvn test -pl performance -am

# Run both wrapper and performance tests
DETECTED_PLATFORM=linux-x86_64-avx2 mvn test -pl wrapper,performance -am
```

### Important Environment Variables

- `DETECTED_PLATFORM` is read by the `native` module via `${env.DETECTED_PLATFORM}`. It is **not** the same as `-Dnative.classifier=...`, which is used by the `wrapper` module only.
- Available values: `linux-x86_64-baseline`, `linux-x86_64-avx2`, `linux-x86_64`, `linux-arm64-baseline`, `linux-arm64`, `windows-x86_64-baseline`, `windows-x86_64`.
- Runtime native access flag: `--enable-native-access=ALL-UNNAMED`.

## Common Pitfalls

1. **Serialization `char**`**: `hs_serialize_database` expects `char **bytes` as an output pointer. Do not pass a pre-allocated buffer. See `docs/knowledge-base/panama-ffm/serialization-roundtrip.md`.
2. **Pointer output arguments**: For any C function taking `T**`, allocate with `arena.allocate(ValueLayout.ADDRESS)`. See `docs/knowledge-base/panama-ffm/pointer-output-arguments.md`.
3. **jextract C_LONG on old Linux**: On CentOS 7-like environments, jextract may misidentify `C_LONG` as `OfInt`. The build runs `native/fix-jextract-shared.sh` to correct it.
4. **Wrapper depends on a classifier JAR**: Wrapper compilation requires a platform-specific `hyperscan-java-panama-native` classifier JAR to be available in the local Maven repository.
5. **Serialization tests were previously disabled**: After the fix, both `wrapper/DatabaseTest#testSerializationDeserializationRoundtrip` and `performance/WrapperSmokeTest#databaseSerializationRoundTrip` are enabled. Do not disable them unless the upstream library genuinely breaks.
6. **JavaCPP callback reuse**: JavaCPP `FunctionPointer` callbacks (e.g., `match_event_handler`) must be reused across scans. Creating a new anonymous callback per `hs_scan` causes zero-match failures after a small number of scans. See `docs/knowledge-base/javacpp/callback-reuse.md`.
7. **Classifier JAR class shadowing**: Multiple platform classifier JARs must not share the same jextract generated class package. The main JAR only contains `HyperscanJni` and `HyperscanNativeLoader`; each classifier JAR contains its own platform-specific package (`...jni.linux_x86_64.generated.*`, `...jni.linux_arm64.generated.*`, etc.) and `HyperscanJniImpl`. See `docs/knowledge-base/panama-ffm/classifier-shadowing.md`.

## Code Style

- Match existing style in the file being edited.
- Do not add unnecessary comments unless explicitly requested.
- Never commit secrets or API keys.
- Keep public API in `com.xenoamess.hyperscan_panama.wrapper` compatible with the original `hyperscan-java` project where possible.

## Commit & Push Policy

- Commit automatically after completing a coherent set of changes.
- Inspect `git status` and `git diff` before each commit; never commit secrets, credentials, or build artifacts such as `target/`.
- Write concise commit messages in English that summarize the change.
- Push automatically once local commits are in a stable, tested state.
- For release-related actions (tagging, Maven Central deploy, GitHub Releases), wait for explicit approval.

## Release Notes

- Do not create releases, tags, or pull requests unless explicitly asked.
- Always run the relevant tests and lint/type-check commands before declaring work done.
