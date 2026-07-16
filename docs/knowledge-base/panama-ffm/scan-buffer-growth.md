# SCAN_BUFFER 只增不减：以空间换时间的取舍

## 现象

`wrapper/.../Scanner.java` 中的 `SCAN_BUFFER`（以及 `NON_ASCII_BUFFER`）是 `ThreadLocal` 缓存的 native/直接缓冲区，用于 `byte[]`、heap `ByteBuffer`、ASCII `String` 扫描的零分配快路径。容量不足时按新输入长度重新分配，**旧缓冲区不释放，缓冲区只增不减**。

## 原因（有意为之）

- 缓冲区分配在 `Arena.global()`（或等价的 direct buffer）上，生命周期与线程一致。
- 重新分配时无法安全释放旧缓冲区：旧缓冲区上的 `MemorySegment` 视图可能仍被 native 调用引用，提前关闭会悬空。
- 设计目标是用常驻内存换取热路径零分配：每线程最多保留"该线程见过的最大输入"大小的缓冲区。

## 代价

- 每个扫描线程常驻一块与历史最大输入等大的 native 内存。线程多且输入尺寸差异大时，占用可达 `线程数 × 最大输入`。
- 线程长期存活（线程池）时无法随 GC 回收。

## 何时需要关注

- 应用使用大量扫描线程，或单线程偶尔扫描超大输入后又长期运行小输入 —— 内存会被峰值输入永久占用。

## 可选应对（未实施，按需取用）

1. 容量上限：超过阈值（如 64MB）不缓存，退回每次 `Arena.ofConfined()` 分配 —— 用确定性内存换取峰值场景性能下降。
2. 分桶缓存：按尺寸档位缓存多块小缓冲区，限制单块上限。
3. 显式释放 API：提供 `Scanner.trimBuffers()` 之类的静态方法让应用主动清空 `ThreadLocal`。

## 结论

当前维持只增不减策略。hyperscan-java 的 `rawScanBuffer`/`scanBuffer`/`hasMatchBuffer`（JavaCPP direct buffer 版）采用相同策略，同样只增不减。
