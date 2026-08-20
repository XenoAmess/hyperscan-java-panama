# Native 生命周期与 FFM 回调边界

## 现象

- `Database` 或 `Scanner` 在关联的 native stream 仍存活时关闭，后续 stream 操作可能访问已释放内存。
- match callback 内关闭当前 scan 使用的 database、scanner 或 stream，会在 native 栈仍使用句柄时释放内存。
- native free/close 返回错误后，Java 对象已经清空句柄，无法重试清理。
- `hs_alloc_scratch` 扩容失败时可能已经释放旧 scratch 并把输出改为 null；只在成功路径读取输出会保留悬空指针。
- match handler 抛出的异常直接越过 FFM upcall 边界，可能造成 VM 级失败，或者被误判为正常终止。
- 数据库序列化成功后，Java `OutputStream` 写出失败会跳过 native buffer 释放。

## 根因

Java 对象之间的强引用不能替代 native 所有权关系。一个 stream 在 native 层同时依赖 database 和 scanner scratch；普通 scan、size、serialize 等 downcall 也必须在整个 native 调用期间持有短期 operation lease。

Cleaner 只执行一次且执行顺序不确定。stream 的 Cleaner action 必须保持 owner scanner 可达，并且只有 native 明确消耗 stream 后才能释放 owner lease；否则宁可安全泄漏，也不能让 owner 在残留 stream 下被释放。

FFM upcall 也不是 Java 异常传播边界。回调异常必须先转换成 native 可理解的停止信号，再在 downcall 返回 Java 后重抛。

## 错误做法

- 在调用 `hs_free_*` 或 `hs_close_stream` 前先把句柄设为 null。
- 允许 database/scanner 在 open stream 存活时关闭。
- 只在取出 native 地址时检查状态，随即释放锁或 lease，再执行 downcall。
- `hs_alloc_scratch` 失败后继续保留调用前的 scratch 地址。
- 让用户异常直接逃出 upcall method handle。
- 只在序列化正常返回路径调用 `free`。

## 正确做法

1. stream 打开前分别获取 database 和 scanner lease；打开失败时回滚。
2. database/scanner 的每个 native 使用获取短期 operation lease，并在 `finally` 释放；callback 内显式 close 因 lease 被拒绝。
3. database/scanner 有 lease 时拒绝显式关闭；stream 确认被 native 消耗后释放 lease。
4. native free 仅在 `HS_SUCCESS` 后清空句柄；失败时保留句柄供显式 close 重试。stream close 还需按 native 实现区分已消耗与未消耗错误。
5. `hs_alloc_scratch` 返回后无论成功失败都先安装输出槽的新值，再处理错误码。
6. upcall 捕获首个 `Throwable` 并返回停止值；downcall 返回后原样重抛。stream close 已完成时先更新 ownership，再重抛。
7. Cleaner action 保持依赖 owner 可达，并使用 `Reference.reachabilityFence` 防止 active downcall 的 referent 被提前清理。
8. native 输出 buffer 的释放放入 `finally`。

## 验证

```bash
DETECTED_PLATFORM=linux-x86_64-avx2 mvn test -pl wrapper -am
```

重点回归 `StreamTest`、`ScannerTest`、`DatabaseTest` 和 `DeallocationTest`。
