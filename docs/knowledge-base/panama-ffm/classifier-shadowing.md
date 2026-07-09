# classifier JAR 类 shadowing 踩坑记录

## 现象

`hyperscan-java-panama` 的 `native` 模块同时发行了 main JAR 和多个平台 classifier JAR（`linux-x86_64`、`linux-arm64`、`windows-x86_64` 等）。在 CI 中，当测试项目同时依赖多个 classifier JAR 时，运行时抛出 `ClassCastException`：

```
java.lang.ClassCastException: class java.lang.foreign.ValueLayout$OfInt
    cannot be cast to class java.lang.foreign.ValueLayout$OfLong
```

堆栈指向 jextract 生成的 `hyperscan$shared.C_LONG` 被当作 `ValueLayout.OfLong` 使用的地方。

## 排查过程

1. 不同平台的 jextract 生成类使用了完全相同的 FQCN：`com.xenoamess.hyperscan_panama.jni.generated.*`。
2. 当多个 classifier JAR 同时出现在 classpath 时，JVM 按 classpath 顺序加载第一个遇到的类，后续同名类被 shadow。
3. 在 Linux LP64 上 `C_LONG` 是 `ValueLayout.OfLong`，而在 Windows LLP64 上是 `ValueLayout.OfInt`。一旦 Linux 平台先加载了 Windows 的 `hyperscan$shared` 类，后续代码执行 `size.get(hyperscan.size_t, 0)` 时就会把 `OfInt` 当作 `OfLong` 使用，触发 `ClassCastException`。
4. 单平台运行也可能出问题：如果 classpath 中只引入了一个 classifier，不会 shadow，但如果同时引入 main JAR 里残留的同名生成类，也可能冲突。

## 根因

main JAR 和 classifier JAR 都包含 `com.xenoamess.hyperscan_panama.jni.generated.*`，且这些类的 FQCN 不区分平台。多个 classifier 共存时必然发生类 shadowing，而平台相关的 `C_LONG`/`size_t` 类型差异会在运行时暴露为 `ClassCastException`。

## 修复方案

采用 **平台专属包 + 平台无关接口** 的方案：

1. 将 jextract 生成类拆到平台专属包：
   - `com.xenoamess.hyperscan_panama.jni.linux_x86_64.generated.*`
   - `com.xenoamess.hyperscan_panama.jni.linux_arm64.generated.*`
   - `com.xenoamess.hyperscan_panama.jni.windows_x86_64.generated.*`
2. 在 `native` 模块中提供平台无关的 `HyperscanJni` 接口。
3. 每个 classifier 生成一个 `HyperscanJniImpl` 实现类，实现 `HyperscanJni`，并委托给本平台生成类。
4. `HyperscanNativeLoader` 运行时根据当前平台选择正确的 `HyperscanJniImpl`。
5. `wrapper` 不再直接 import 生成类，而是通过 `HyperscanNativeLoader.loadJni()` 拿到 `HyperscanJni` 后调用。
6. main JAR 只保留 `HyperscanJni` 和 `HyperscanNativeLoader` 等通用类；classifier JAR 只保留平台专属包 + `HyperscanJniImpl` + 原生库资源。

这样不同平台的类 FQCN 不再相同，即使多个 classifier JAR 共存也不会 shadow。

## 关键注意点

- `MemorySegment.get(ValueLayout, offset)` 不接受 `ValueLayout` 基类，因此 `HyperscanJni` 提供了 `readSize_t(MemorySegment, long)` 方法，在实现内部根据平台决定用 `OfLong` 还是 `OfInt` 读取。
- `hs_deserialize_database` 的 `length` 参数在 Linux 是 `long`、Windows 是 `int`。`HyperscanJni` 统一暴露为 `long`，wrapper 调用时显式转 `(long)`。
- `match_event_handler` 也随平台包迁移，`HyperscanJni` 提供 `allocateMatchEventHandler(MatchEventCallback, Arena)` 来屏蔽平台回调接口差异。
- 平台 family 计算规则：取 `DETECTED_PLATFORM` 的前两段。
  - `linux-x86_64-avx2` → `linux-x86_64` → 包名 `linux_x86_64`
  - `linux-arm64-baseline` → `linux-arm64` → 包名 `linux_arm64`
  - `windows-x86_64-baseline` → `windows-x86_64` → 包名 `windows_x86_64`

## 验证

- `mvn verify -pl wrapper -Dnative.classifier=linux-x86_64-avx2` 单 classifier 通过。
- 在 wrapper 依赖中同时加入 `linux-x86_64-avx2` 和 `linux-arm64` 两个 classifier 后再次运行测试，全部通过，证明多 classifier 共存无 shadowing。
- `mvn test -pl performance` 通过。

## 相关文件

- `native/src/main/java/com/xenoamess/hyperscan_panama/jni/HyperscanJni.java`
- `native/src/main/java/com/xenoamess/hyperscan_panama/jni/HyperscanNativeLoader.java`
- `native/src/main/template/HyperscanJniImpl.java.template`
- `native/run-jextract.sh`
- `native/generate-hyperscan-jni-impl.sh`
- `native/fix-jextract-shared.sh`
- `native/pom.xml`
- `wrapper/pom.xml`
- `wrapper/src/main/java/com/xenoamess/hyperscan_panama/wrapper/Database.java`
- `wrapper/src/main/java/com/xenoamess/hyperscan_panama/wrapper/Scanner.java`
- `wrapper/src/main/java/com/xenoamess/hyperscan_panama/wrapper/Expression.java`
- `wrapper/src/main/java/com/xenoamess/hyperscan_panama/wrapper/ExpressionFlag.java`
- `wrapper/src/main/java/com/xenoamess/hyperscan_panama/wrapper/HyperscanException.java`

## 时间线

- 2026-07-09：完成整改并验证。
