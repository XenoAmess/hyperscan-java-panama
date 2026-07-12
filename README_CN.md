# hyperscan-java-panama

[![Build and Test](https://github.com/XenoAmess/hyperscan-java-panama/actions/workflows/build.yml/badge.svg)](https://github.com/XenoAmess/hyperscan-java-panama/actions/workflows/build.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://xenoamess.github.io/hyperscan-java-panama/coverage-wrapper.json)](https://xenoamess.github.io/hyperscan-java-panama/coverage/coverage.html)

📊 **[查看最新性能报告](https://xenoamess.github.io/hyperscan-java-panama/)**

[![最新性能摘要](https://xenoamess.github.io/hyperscan-java-panama/summary.svg)](https://xenoamess.github.io/hyperscan-java-panama/)

[English](README.md) | [中文](README_CN.md)

使用 [Project Panama（FFM）](https://openjdk.org/projects/panama/) 为 [Hyperscan/VectorScan](https://github.com/VectorCamp/vectorscan) 实现的高性能 Java 绑定。本项目用 JDK 25 提供的新式 Java 外部函数与内存 API 替代了 JavaCPP。

## 特性

- **Project Panama FFM** — 无需 native JNI 或 JavaCPP。
- **多平台原生二进制** — Linux x86_64（baseline / AVX2 / AVX-512）、Linux ARM64（baseline / SVE2）、Windows x86_64（baseline / AVX2）。
- **兼容 API** — 保留 `com.xenoamess.hyperscan_panama.wrapper` 公开 API。
- **自动 CPU 特性检测** — 加载器在运行时自动选择最合适的原生 ISA 变体。

## 环境要求

- JDK 25 或更高版本（推荐 Temurin）
- Maven 3.9+
- Linux、Windows 或 macOS（macOS 需要手动编译原生库）

## 快速开始

在 `pom.xml` 中添加依赖：

```xml
<dependency>
    <groupId>com.xenoamess.hyperscan_panama</groupId>
    <artifactId>hyperscan-java-panama</artifactId>
    <version>5.4.12-rc5</version>
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

运行时需要开启 FFM native access：

```bash
java --enable-native-access=ALL-UNNAMED -jar your-app.jar
```

## 构建

```bash
export DETECTED_PLATFORM=linux-x86_64-avx2
mvn verify -pl wrapper -am -Dnative.classifier=${DETECTED_PLATFORM}
```

可用 platform classifier：

- `linux-x86_64-baseline`
- `linux-x86_64-avx2`
- `linux-x86_64`
- `linux-arm64-baseline`
- `linux-arm64`
- `windows-x86_64-baseline`
- `windows-x86_64`

## 性能

`performance` 模块包含可重复执行的基准测试。CI 会在所有支持的平台（Linux x86_64 baseline/AVX2/AVX-512、Linux ARM64 baseline/SVE2、Windows x86_64 baseline/AVX2）上运行测试，并把结果聚合成一个 GitHub Pages 报告：

https://xenoamess.github.io/hyperscan-java-panama

报告包含与 [hyperscan-java-test](https://xenoamess.github.io/hyperscan-java-test/) 完全相同的固定负载（500 个混合模式、约 20 KB 输入、5 次迭代），方便跨项目对比，同时保留原有的 hyperscan-java-panama 多项基准。

本地运行基准测试：

```bash
export DETECTED_PLATFORM=linux-x86_64-avx2
mvn test -pl performance -am -Dnative.classifier=${DETECTED_PLATFORM}
```


## 致谢

本项目是 [hyperscan-java](https://github.com/gliwka/hyperscan-java) 的分支与延续，原作者为 [Matthias Gliwka](https://github.com/gliwka)。在从 JavaCPP 迁移到 Project Panama FFM 的过程中，我们尽可能保留了原项目的 wrapper API 设计与项目结构。

## 许可证

3-Clause BSD License。详见 [LICENSE](LICENSE)。
