# hyperscan-java-panama

[![Build and Test](https://github.com/XenoAmess/hyperscan-java-panama/actions/workflows/build.yml/badge.svg)](https://github.com/XenoAmess/hyperscan-java-panama/actions/workflows/build.yml)
[![Performance Report](https://img.shields.io/badge/Performance%20Report-View-blue)](https://xenoamess.github.io/hyperscan-java-panama)

[English](README.md) | [中文](README_CN.md)

使用 [Project Panama（FFM）](https://openjdk.org/projects/panama/) 为 [Hyperscan/VectorScan](https://github.com/VectorCamp/vectorscan) 实现的高性能 Java 绑定。本项目用 JDK 25 提供的新式 Java 外部函数与内存 API 替代了 JavaCPP。

## 特性

- **Project Panama FFM** — 无需 native JNI 或 JavaCPP。
- **多平台原生二进制** — Linux x86_64（baseline / AVX2 / AVX-512）、Linux ARM64（baseline / SVE2）、Windows x86_64（baseline / AVX2）。
- **兼容 API** — 保留 `com.gliwka.hyperscan.wrapper` 公开 API。
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
    <version>5.4.12-1.0.0-SNAPSHOT</version>
</dependency>
```

```java
import com.gliwka.hyperscan.wrapper.*;

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

`performance` 模块包含可重复执行的基准测试。最新报告发布在 GitHub Pages：

https://xenoamess.github.io/hyperscan-java-panama

本地运行基准测试：

```bash
export DETECTED_PLATFORM=linux-x86_64-avx2
mvn test -pl performance -am -Dnative.classifier=${DETECTED_PLATFORM}
```


## 许可证

3-Clause BSD License。
