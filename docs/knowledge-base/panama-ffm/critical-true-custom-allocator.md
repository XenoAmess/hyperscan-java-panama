# Linker.Option.critical(true) 与自定义分配器 upcall 的致命冲突

## 症状

smoke 测试 forked JVM 直接崩溃（exit 134），surefire 报 "The forked VM terminated without properly saying goodbye"，dumpstream 中为：

```
Internal Error (upcallLinker.cpp:77)
guarantee(thread->thread_state() == _thread_in_native) failed: wrong thread state for upcall
V [libjvm.so] UpcallLinker::on_entry(UpcallStub::FrameData*)
```

## 根因

`Linker.Option.critical(true)` 的 downcall 省略线程状态转换，**绝不允许在执行中触达 Java upcall**。hs 的自定义分配器机制（`hs_set_allocator` / `hs_set_scratch_allocator`）让"看似纯查询/释放"的函数也会产生 upcall：

- `hs_free_database` / `hs_free_scratch` / `hs_free_compile_error` → 调用用户 `hs_free_t`
- `hs_database_info` / `hs_serialized_database_info` → 通过用户 `hs_alloc_t` 分配返回字符串

把这些函数标成 critical(true) 后，一旦用户安装了自定义分配器（如 `AllocatorsTest`），free/alloc upcall 从 critical downcall 中触发，JVM 直接 fatal。崩溃时机依赖 GC/分配路径，表现为跨平台非确定性（同一版本 7 个平台 6 个崩 1 个侥幸过）。

## 规则

critical(true) 只能用于**任何情况下都不触碰分配器、不触发回调的函数**。当前白名单：

- `hs_version`、`hs_valid_platform`
- `hs_database_size`、`hs_scratch_size`、`hs_stream_size`

判断标准：函数只写调用方提供的输出参数、返回静态数据，源码路径上不出现 `hs_alloc_t`/`hs_free_t`/`match_event_handler` 调用。给新函数加 critical(true) 前，必须先在 vectorscan 源码里确认该函数（及其调用链）不使用分配器。

## 参考

- 引入问题的 commit：0f9dee3（10 函数名单）
- 修复 commit：fdda225（收敛为 5 函数）
- 事故 run：hyperscan-java-test actions/runs/29547930096
