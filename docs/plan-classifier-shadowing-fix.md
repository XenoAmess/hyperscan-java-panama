# hyperscan-java-panama classifier 类 shadowing 整改计划

## 背景

`hyperscan-java-panama` 的 `native` 模块同时发行了 main JAR 和多个平台 classifier JAR（`linux-x86_64`、`linux-arm64`、`windows-x86_64` 等）。这些 JAR 包含同一全限定名的 jextract 生成类 `com.xenoamess.hyperscan_panama.jni.generated.*`，但其中 `hyperscan$shared.C_LONG` 等平台相关类型不同：

- Linux LP64：`ValueLayout.OfLong`
- Windows LLP64：`ValueLayout.OfInt`

当多个 classifier JAR 同时出现在 classpath 时，先加载的平台类会 shadow 其他平台，导致运行时 `ClassCastException`。

## 目标

1. 引入多个平台的 classifier JAR 时不会报错，能自动选中符合当前系统的版本。
2. 只引入自己平台的 classifier JAR、其他平台没引入时，也不会报错。
3. 保持 `com.xenoamess.hyperscan_panama.wrapper.*` 公共 API 完全不变。

## 方案 B：平台包前缀 + 平台无关接口

### 核心设计

把平台相关的 jextract 生成类从默认包 `com.xenoamess.hyperscan_panama.jni.generated.*` 拆到平台专属包：

```
com.xenoamess.hyperscan_panama.jni.linux_x86_64.generated.*
com.xenoamess.hyperscan_panama.jni.linux_arm64.generated.*
com.xenoamess.hyperscan_panama.jni.windows_x86_64.generated.*
```

`native` 模块提供平台无关的 `HyperscanJni` 接口，`wrapper` 模块不再直接 import 生成类，而是通过接口动态获取平台相关的常量、layout、VarHandle 和 MethodHandle。

## 目录结构变更

```
native/src/main/java/com/xenoamess/hyperscan_panama/jni/
├── HyperscanJni.java                 # 新增：平台无关接口
├── HyperscanNativeLoader.java        # 修改：加载 native 库 + 选择 HyperscanJni 实现
└── <platform>/                        # classifier JAR 内才有
    ├── generated/                     # jextract 生成类
    └── HyperscanJniImpl.java        # 平台实现类

wrapper/src/main/java/com/xenoamess/hyperscan_panama/wrapper/
├── Database.java                     # 修改：通过 HyperscanJni 调用生成类
├── Scanner.java                      # 修改：通过 HyperscanJni 调用生成类
├── Expression.java                   # 修改：通过 HyperscanJni 获取常量
├── ExpressionFlag.java              # 修改：通过 HyperscanJni 获取常量
├── Match.java
├── CompileErrorException.java
└── util/PatternFilter.java           # 修改：通过 HyperscanJni 获取常量
```

## 接口设计

`HyperscanJni` 暴露 wrapper 实际需要的常量、layout、句柄，并统一 Java 签名：

```java
package com.xenoamess.hyperscan_panama.jni;

public interface HyperscanJni {
    // 常量
    int hsSuccess();
    int hsInvalid();
    int hsSerialVersion();
    int hsFlagCaseless();
    int hsFlagDotall();
    // ... 其他 flag

    // layout
    ValueLayout size_t();
    AddressLayout c_pointer();
    ValueLayout c_long();

    // 用于 MemorySegment 读写的 VarHandle
    VarHandle size_tVarHandle();

    // 函数描述符 + 适配后的 MethodHandle
    FunctionDescriptor hsCompileDatabaseDesc();
    MethodHandle hsCompileDatabase(); // 统一签名，内部用 asType 适配

    FunctionDescriptor hsFreeDatabaseDesc();
    MethodHandle hsFreeDatabase();

    FunctionDescriptor hsScanDesc();
    MethodHandle hsScan();

    FunctionDescriptor hsSerializeDatabaseDesc();
    MethodHandle hsSerializeDatabase();

    FunctionDescriptor hsDeserializeDatabaseDesc();
    MethodHandle hsDeserializeDatabase();

    // 结构体布局
    StructLayout hsCompileErrorLayout();
    VarHandle hsCompileErrorMessage();
    VarHandle hsCompileErrorExpression();

    StructLayout hsPlatformInfoLayout();
    // ... 其他结构
}
```

注意：

- `MemorySegment.get(ValueLayout, offset)` 不存在，因此同时提供 `ValueLayout`（用于 `arena.allocate`）和 `VarHandle`（用于读写）。
- `MethodHandle` 在实现类中用 `asType()` 把平台相关签名（如 Windows 上 `int` 参数）统一适配成一致 Java 签名（如全用 `long`），wrapper 按统一签名调用。

## 平台名与包名映射

沿用 `DETECTED_PLATFORM` 的命名：

| DETECTED_PLATFORM | 接口实现包名 |
|---|---|
| `linux-x86_64` / `linux-x86_64-avx2` / `linux-x86_64-baseline` | `com.xenoamess.hyperscan_panama.jni.linux_x86_64` |
| `linux-arm64` / `linux-arm64-baseline` | `com.xenoamess.hyperscan_panama.jni.linux_arm64` |
| `windows-x86_64` / `windows-x86_64-baseline` | `com.xenoamess.hyperscan_panama.jni.windows_x86_64` |

## 实现步骤

### 1. native 模块：新增 `HyperscanJni` 接口

- 在 `native/src/main/java/com/xenoamess/hyperscan_panama/jni/` 下创建 `HyperscanJni.java`。
- 接口内容先覆盖 wrapper 已使用的所有生成类 API。

### 2. native 模块：构建时生成平台专属包

- 修改 `native/pom.xml` 的 jextract 执行：
  - 输出目录改为 `target/generated-sources/<platform>/com/xenoamess/hyperscan_panama/jni/<platform>/generated/`。
  - 保持 `fix-jextract-shared.sh` 修正 `C_LONG` 的逻辑，路径相应调整。
- 新增代码生成步骤：
  - 生成 `com.xenoamess.hyperscan_panama.jni.<platform>.HyperscanJniImpl`。
  - 实现 `HyperscanJni` 接口，委托给 `...<platform>.generated.hyperscan` 等类。
  - 用 `MethodHandle.asType()` 统一 Java 签名。

### 3. native 模块：`HyperscanNativeLoader` 加载实现

修改 `HyperscanNativeLoader`：

```java
public static HyperscanJni loadJni() {
    String platform = System.getProperty(PLATFORM_PROPERTY);
    if (platform == null || platform.isEmpty()) {
        platform = selectPlatform();
    }
    String pkg = "com.xenoamess.hyperscan_panama.jni." + platform.replace('-', '_');
    Class<?> impl = Class.forName(pkg + ".HyperscanJniImpl");
    return (HyperscanJni) impl.getConstructor().newInstance();
}
```

main JAR 只保留接口和加载器；classifier JAR 只保留平台专属包 + `HyperscanJniImpl`。

### 4. wrapper 模块：重构为通过接口调用

逐个修改以下文件，把直接引用 `com.xenoamess.hyperscan_panama.jni.generated.*` 的地方改成 `HyperscanNativeLoader.loadJni()`：

- `Database.java`
- `Scanner.java`
- `Expression.java`
- `ExpressionFlag.java`
- `util/PatternFilter.java`

示例变化：

```java
// 旧
MemorySegment size = arena.allocate(hyperscan.size_t);
return size.get(hyperscan.size_t, 0);

// 新
private static final HyperscanJni JNI = HyperscanNativeLoader.loadJni();
MemorySegment size = arena.allocate(JNI.size_t());
return (long) JNI.size_tVarHandle().get(size, 0L);
```

### 5. 包装与发布

- main JAR 不再包含 `generated.*` 类。
- `package-native` 合并步骤按 family 合并 classifier JAR，但合并的是平台专属包内容。
- 版本号：`5.4.12-rc2`。
- 运行 CI 验证后发布到 Maven Central。

### 6. hyperscan-java-test 回退临时方案

- 移除 `pom.xml` 里的 Maven profile 临时写法。
- 同时依赖三个 classifier：`linux-x86_64`、`linux-arm64`、`windows-x86_64`。
- 验证 CI 在多平台共存和单平台运行时都通过。

## 版本号

`5.4.12-rc2`

## 向后兼容性

- `com.xenoamess.hyperscan_panama.wrapper.*` 公共 API 完全不变。
- 用户无需修改业务代码即可升级。
- 如果用户项目直接引用了 `com.xenoamess.hyperscan_panama.jni.generated.*` 类，需要迁移到通过 `HyperscanJni` 接口调用，但这些属于内部实现，不建议直接使用。

## 风险

1. **wrapper 改动面较大**：需要逐一替换所有生成类的直接引用。建议先枚举 wrapper 实际使用的生成 API，确认接口范围。
2. **性能**：`MethodHandle.invokeExact` 和 `VarHandle` 在正确缓存后性能接近直接调用；实际 native 调用开销远大于 Java 层间接调用。
3. **MethodHandle 签名统一**：需要确保 `asType()` 适配覆盖所有平台差异（主要是 `C_LONG`/`size_t` 在 Linux 是 `long`、Windows 是 `int`）。
4. **测试覆盖**：需要在 Linux x86_64、Linux ARM64、Windows x86_64 三平台完整测试。

## 验证标准

1. `hyperscan-java-panama` 的 `wrapper` 模块在单平台 classifier 下测试通过。
2. `hyperscan-java-test` 同时引入三个 classifier 后，所有平台 CI 通过。
3. `hyperscan-java-test` 仅引入当前平台 classifier 时，也能通过。
4. 性能 benchmark 结果与整改前无明显差异。
