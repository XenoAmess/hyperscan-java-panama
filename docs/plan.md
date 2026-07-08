# hyperscan-java-panama 项目构建计划

## 1. 目标

- 保留 `hyperscan-java` 的上层 Java API 设计与全部测试用例。
- 替换 JavaCPP 为 Project Panama（FFM API）。
- 复用 `hyperscan-java-native` 的多 ISA native 库构建与运行时选择逻辑。
- 技术栈：Java 25、Maven、jextract、Vectorscan/Hyperscan。

## 2. 关键决策

| 决策项 | 选择 |
|---|---|
| Java 版本 | 25（FFM API 已稳定） |
| groupId | `com.xenoamess.hyperscan_panama` |
| 项目结构 | 多模块 Maven：父 POM + `native` + `wrapper` |
| 绑定生成 | 构建时下载并运行 jextract |
| Native 变体 | 保留 multi-ISA（x86_64 baseline/avx2/avx512、arm64 baseline/sve2 等） |

## 3. 项目结构

```
/home/xenoamess/workspace/hyperscan-java-panama/
├── pom.xml                                 # 父 POM，聚合 + 版本管理
├── README.md
├── LICENSE
├── CHANGELOG.md
├── .github/
│   ├── dependabot.yml
│   └── workflows/
│       ├── build.yml                       # 原生构建 + 测试
│       └── release.yml
├── native/                                 # hyperscan-java-panama-native
│   ├── pom.xml
│   ├── build.sh
│   ├── build-windows.sh
│   ├── download-jextract.sh
│   ├── src/main/java/com/gliwka/hyperscan/jni/
│   │   ├── HyperscanNativeLoader.java
│   │   └── hyperscan.h                     # 聚合头，供 jextract 使用
│   └── src/main/resources/
│       └── com/xenoamess/hyperscan_panama/jni/
│           └── (native libs, 构建后打包)
└── wrapper/                                # hyperscan-java-panama
    ├── pom.xml
    └── src/
        ├── main/java/com/gliwka/hyperscan/
        │   ├── jni/                        # jextract 生成代码
        │   ├── wrapper/
        │   │   ├── Database.java
        │   │   ├── Scanner.java
        │   │   ├── Expression.java
        │   │   ├── Match.java
        │   │   ├── ExpressionFlag.java
        │   │   ├── HyperscanException.java
        │   │   ├── CompileErrorException.java
        │   │   ├── NativeExpression.java
        │   │   ├── NativeExpressionCollection.java
        │   │   ├── Utf8Encoder.java
        │   │   ├── StringMatchEventHandler.java
        │   │   ├── ByteMatchEventHandler.java
        │   │   ├── RawMatchEventHandler.java
        │   │   ├── BitFlag.java
        │   │   └── mapping/
        │   │       ├── ByteCharMapping.java
        │   │       ├── ByteMapping.java
        │   │       ├── ShortMapping.java
        │   │       └── IntMapping.java
        │   └── util/PatternFilter.java
        └── test/java/com/gliwka/hyperscan/
            ├── wrapper/
            │   ├── DatabaseTest.java
            │   ├── ScannerTest.java
            │   ├── ExpressionTest.java
            │   ├── Utf8EncoderTest.java
            │   ├── DeallocationTest.java (改写)
            │   └── benchmark/SimpleBenchmark.java
            ├── util/PatternFilterTest.java
            └── regression/
                ├── Gh228VectorscanX86RegressionTest.java
                └── Gh231MatchesNotReportedTest.java
```

## 4. 模块 GAV 与包结构

| 模块 | artifactId | packaging | 说明 |
|---|---|---|---|
| 父 | `hyperscan-java-panama` | `pom` | 聚合两个子模块 |
| 原生 | `hyperscan-java-panama-native` | `jar` | 生成绑定 + 加载器 + native 库 |
| 包装 | `hyperscan-java-panama` | `jar` | 对外 Java API |

**包结构保持不变**（以兼容现有 API）：

- 上层 API：`com.gliwka.hyperscan.wrapper`、`com.gliwka.hyperscan.util`
- 原生绑定：`com.gliwka.hyperscan.jni`（jextract 生成 `hyperscan.java`）

Maven groupId 与 Java 包名不一致是允许的，目的在于保持用户 `import` 兼容。

## 5. Native 模块设计

### 5.1 复用原生构建脚本

保留 `build.sh` / `build-windows.sh` 的 vectorscan/Hyperscan 下载、CMake 编译逻辑，仍产出以下变体：

- `linux-x86_64` / `linux-x86_64-avx2` / `linux-x86_64-baseline`
- `linux-arm64` / `linux-arm64-baseline`
- `macosx-x86_64` / `macosx-arm64`
- `windows-x86_64` / `windows-x86_64-baseline`

不同点：不再调用 `org.bytedeco:javacpp`，而是把产物放到 `native/target/native/...` 供 Maven 打包。

### 5.2 jextract 绑定生成

在 `native/src/main/java/com/gliwka/hyperscan/jni/hyperscan.h` 中聚合头：

```c
#include <hs/hs_common.h>
#include <hs/hs_compile.h>
#include <hs/hs_runtime.h>
#include <hs/hs.h>
```

构建流程：

1. `download-jextract.sh`：按 OS/arch 下载 jextract 到 `native/target/jextract/`（如未下载）。
2. Maven 执行 jextract：
   ```bash
   target/jextract/bin/jextract \
     --output target/generated-sources \
     --target-package com.gliwka.hyperscan.jni \
     -I cppbuild/include \
     src/main/java/com/gliwka/hyperscan/jni/hyperscan.h
   ```
3. `build-helper-maven-plugin` 把 `target/generated-sources` 加入 source root。
4. 生成 `com/gliwka/hyperscan/jni/hyperscan.java`，包含所有常量、函数、struct 布局。

### 5.3 HyperscanNativeLoader

改写原 `HyperscanNativeLoader`：

- 保留 `selectPlatform()` CPU 特征选择逻辑。
- 从 classpath resource `com/xenoamess/hyperscan_panama/jni/<variant>/{libhs.so,libhs_runtime.so}` 解压到临时目录。
- `System.load(...)` 加载两个库。
- 确保 jextract 生成代码通过 `SymbolLookup.loaderLookup()` 能找到符号。

### 5.4 打包

- 主 JAR：生成代码 + `HyperscanNativeLoader`。
- classifier JAR：与原始一致，如 `linux-x86_64` classifier 包含 `linux-x86_64` / `linux-x86_64-avx2` / `linux-x86_64-baseline` 三个子目录。
- 使用 `maven-jar-plugin` 的 `classifier` execution 打包 native 目录。

## 6. Wrapper 模块设计

### 6.1 依赖

- `com.xenoamess.hyperscan_panama:hyperscan-java-panama-native`
- 各 platform classifier dependencies（运行时测试用）
- JUnit 5、AssertJ、JMH、Lombok（升级以支持 Java 25）

### 6.2 关键类改写

| 类 | 改动 |
|---|---|
| `Database` | `MemorySegment` 存数据库指针；`Cleaner` 管理释放；`save()` 改为标准两步序列化。 |
| `Scanner` | `MemorySegment` 存 scratch；`match_event_handler` 用静态 `upcallStub`。 |
| `NativeExpression` | `Arena.allocateFrom(expression)` 获取 C 字符串。 |
| `NativeExpressionCollection` | `ADDRESS` 数组 + `JAVA_INT` 数组，使用 `Arena`。 |
| `Expression` | `validate()` 改为 `hs_expr_info_t**` / `hs_compile_error_t**` 输出指针模式。 |
| `ExpressionFlag` | 常量从 jextract 生成的 `hyperscan` 类导入。 |
| `HyperscanException` | 错误常量从 jextract 生成的 `hyperscan` 类导入。 |
| `Utf8Encoder` / `mapping` / `PatternFilter` / `Match` / 事件接口 | 不变。 |

### 6.3 回调实现

`Scanner` 中静态注册 Panama upcall：

```java
FunctionDescriptor descriptor = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.JAVA_INT,
    ValueLayout.JAVA_LONG,
    ValueLayout.JAVA_LONG,
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS
);
// 绑定到 private static int onMatch(int id, long from, long to, int flags, MemorySegment context)
// 内部从 ThreadLocal<RawMatchEventHandler> 分发
```

## 7. 测试迁移

**原样迁移**：

- `DatabaseTest`
- `ScannerTest`
- `ExpressionTest`
- `Utf8EncoderTest`
- `PatternFilterTest`
- `Gh228VectorscanX86RegressionTest`
- `Gh231MatchesNotReportedTest`
- `SimpleBenchmark`

**需改写**：`DeallocationTest`

原因：原测试使用 `org.bytedeco.javacpp.Pointer.totalCount()`，Panama 无全局 native 指针计数。

改写方向：

- 保留 `close()` 后再调用 `getSize()` 抛 `IllegalStateException` 的测试。
- 保留 `WeakReference` + `System.gc()` 验证 Java 对象可被回收。
- 移除 `Pointer.totalCount()` 断言，改为验证 `Cleaner` 不抛异常。

## 8. CI / 构建流程

### 8.1 本地开发

1. 运行 `native/build.sh`：构建 native 库 + 生成 jextract 绑定 + 安装 `native` 模块到本地 Maven。
2. 运行 `mvn verify`：构建并测试 `wrapper`。

### 8.2 CI workflow

1. 矩阵构建 native 各 ISA 变体（复用原 `hyperscan-java-native/.github/workflows/build.yml` 矩阵）。
2. 聚合 classifier JARs。
3. 运行 `mvn verify` 测试 wrapper。

### 8.3 Release workflow

1. 构建 native 矩阵。
2. 上传 artifacts。
3. 聚合 classifier JARs。
4. `mvn deploy` 到 Maven Central。

## 9. 实施里程碑

1. **搭建骨架**：创建父 POM、两个子模块、目录结构、LICENSE/README/CHANGELOG。
2. **Native 模块**：
   - 复制并改写 `build.sh` / `build-windows.sh`（去掉 JavaCPP）。
   - 编写 `download-jextract.sh`。
   - 配置 jextract Maven execution。
   - 生成 `hyperscan.java` 并验证编译通过。
   - 改写 `HyperscanNativeLoader`。
   - 测试 native 库加载与 classifier 打包。
3. **Wrapper 模块**：
   - 复制原 wrapper 源码。
   - 改写 `NativeExpression` / `NativeExpressionCollection` / `Database` / `Scanner` / `Expression` / `HyperscanException` / `ExpressionFlag`。
   - 复制并改写 `DeallocationTest`。
4. **集成测试**：运行全部测试，修复 Panama 绑定差异。
5. **CI / 文档**：配置 GitHub Actions，更新 README。

## 10. 风险与注意事项

1. **jextract 下载 URL**：需按实际 release 命名确认；计划先用可配置 property，下载脚本中按平台拼接。
2. **jextract 生成代码风格**：struct 访问器、函数名、库加载方式可能与预期不同，需生成后根据实际输出微调 wrapper。
3. **Java 25 兼容性**：Lombok、JMH 需升级到支持 Java 25 的版本。
4. **MemorySegment 生命周期**：数据库指针在 `compile` 后需长期存活，使用 `MemorySegment.ofAddress(address)` 存储并传给 `Cleaner`。
5. **序列化流程**：Panama 版 `Database.save()` 已按标准 `hs_serialize_database(db, char **bytes, size_t *length)` 正确实现（库分配输出缓冲区，调用者用 `free()` 释放）。具体排查过程与修复方案参见 [docs/knowledge-base/panama-ffm/serialization-roundtrip.md](./knowledge-base/panama-ffm/serialization-roundtrip.md)。

---

*计划版本：1.0*
*生成日期：2026-07-07*
