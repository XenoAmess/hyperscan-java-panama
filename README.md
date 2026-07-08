# hyperscan-java-panama

[![Build and Test](https://github.com/XenoAmess/hyperscan-java-panama/actions/workflows/build.yml/badge.svg)](https://github.com/XenoAmess/hyperscan-java-panama/actions/workflows/build.yml)
[![Performance Report](https://img.shields.io/badge/Performance%20Report-View-blue)](https://xenoamess.github.io/hyperscan-java-panama)

[English](README.md) | [中文](README_CN.md)

High-performance Java bindings for [Hyperscan](https://github.com/VectorCamp/vectorscan) using [Project Panama (FFM)](https://openjdk.org/projects/panama/). This project replaces JavaCPP with the modern Java Foreign Function & Memory API available in JDK 25.

## Features

- **Project Panama FFM** — no native JNI or JavaCPP required.
- **Multi-platform native binaries** — Linux x86_64 (baseline / AVX2 / AVX-512), Linux ARM64 (baseline / SVE2), Windows x86_64 (baseline / AVX2).
- **Compatible API** — the public `com.xenoamess.hyperscan_panama.wrapper` API is preserved.
- **Automatic CPU feature detection** — the loader selects the best available native ISA variant at runtime.

## Requirements

- JDK 25 or later ( Temurin recommended )
- Maven 3.9+
- Linux, Windows, or macOS (macOS builds require manual native compilation)

## Quick Start

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.xenoamess.hyperscan_panama</groupId>
    <artifactId>hyperscan-java-panama</artifactId>
    <version>5.4.11-rc1</version>
</dependency>
```

```java
import com.xenoamess.hyperscan_panama.wrapper.*;

public class Example {
    public static void main(String[] args) throws Exception {
        try (Database db = Database.compile(new Expression("test[0-9]+", ExpressionFlag.SOM_LEFTMOST))) {
            try (Scanner scanner = new Scanner()) {
                scanner.allocScratch(db);
                for (Match m : scanner.scan(db, "this is a test123 string")) {
                    System.out.println(m.getMatchedString());
                }
            }
        }
    }
}
```

Run with the FFM native-access flag:

```bash
java --enable-native-access=ALL-UNNAMED -jar your-app.jar
```

## Building

```bash
export DETECTED_PLATFORM=linux-x86_64-avx2
mvn verify -pl wrapper -am -Dnative.classifier=${DETECTED_PLATFORM}
```

Available platform classifiers:

- `linux-x86_64-baseline`
- `linux-x86_64-avx2`
- `linux-x86_64`
- `linux-arm64-baseline`
- `linux-arm64`
- `windows-x86_64-baseline`
- `windows-x86_64`

## Performance

The `performance` module contains repeatable benchmarks. CI runs them across all supported platforms (Linux x86_64 baseline/AVX2/AVX-512, Linux ARM64 baseline/SVE2, Windows x86_64 baseline/AVX2) and aggregates the results into a single GitHub Pages report:

https://xenoamess.github.io/hyperscan-java-panama

The report includes the same fixed workload used by [hyperscan-java-test](https://xenoamess.github.io/hyperscan-java-test/) (500 mixed patterns over ~20 KB input, 5 iterations) for direct cross-project comparison, alongside the original hyperscan-java-panama benchmarks.

Run benchmarks locally:

```bash
export DETECTED_PLATFORM=linux-x86_64-avx2
mvn test -pl performance -am -Dnative.classifier=${DETECTED_PLATFORM}
```


## Acknowledgments

This project is a fork and continuation of [hyperscan-java](https://github.com/gliwka/hyperscan-java) originally created by [Matthias Gliwka](https://github.com/gliwka). The original wrapper API design and project structure were preserved as much as possible while migrating from JavaCPP to Project Panama FFM.

## License

3-Clause BSD License. See [LICENSE](LICENSE) for details.
