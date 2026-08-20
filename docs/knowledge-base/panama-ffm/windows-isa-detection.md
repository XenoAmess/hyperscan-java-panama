# Windows ISA 自动选择

## 现象

Windows AVX2 主机仍自动加载 baseline，AVX-512 主机也无法落到兼容的 AVX2 tier。

## 根因

`PROCESSOR_IDENTIFIER` 通常只包含 CPU 型号，不包含可靠的 `avx2`、`bmi2` 等 feature 名称。根据该字符串推断 ISA 基本总会失败，也没有验证 OS XSAVE 状态。

## 错误做法

- 搜索 `PROCESSOR_IDENTIFIER` 中是否出现 `AVX2` 或 `AVX512`。
- 只依据硬件型号选择需要扩展寄存器状态的 native binary。

## 正确做法

HotSpot 上通过 `com.sun.management:type=HotSpotDiagnostic` 读取 `UseAVX` 和 `UseSSE`。`UseAVX >= 2` 选择 AVX2 tier；已知 `UseSSE < 4` 时拒绝 baseline；非 HotSpot 或探测失败时保守回退 baseline，并允许系统属性显式覆盖。

## 验证

Windows CI 应分别覆盖默认 JVM、`-XX:UseAVX=1` 和显式 platform override，并断言实际加载的 resource tier。
