# 指针输出参数处理模式

在 Hyperscan 的 C API 中，很多函数通过 `T**` 输出参数返回动态分配的对象或指针。迁移到 Project Panama（FFM）时，必须把它们转成 `MemorySegment.allocate(ValueLayout.ADDRESS)`，而不是直接传缓冲区。

## 基本原理

C 签名示例：

```c
hs_error_t hs_deserialize_database(const char *bytes, size_t length, hs_database_t **db);
```

`hs_database_t **db` 表示“指向 `hs_database_t*` 的指针”。调用者分配一个指针变量（8 字节，64 位），函数把真正的数据库指针写进这个变量。在 Java FFM 中对应：

```java
MemorySegment dbOut = arena.allocate(ValueLayout.ADDRESS);
```

## 常用示例

### 1. `hs_serialize_database`：`char **bytes` + `size_t *length`

```java
MemorySegment bytesOut = arena.allocate(ValueLayout.ADDRESS);
MemorySegment sizeOut = arena.allocate(hyperscan.size_t);

int hsError = hyperscan.hs_serialize_database(database, bytesOut, sizeOut);
if (hsError != 0) {
    throw HyperscanException.hsErrorToException(hsError);
}

long length = sizeOut.get(hyperscan.size_t, 0);
MemorySegment bytes = bytesOut.get(ValueLayout.ADDRESS, 0).reinterpret(length);

// 读取 bytes 内容...

// 库内部用 malloc 分配，用标准 free 释放
hyperscan.free(bytes);
```

完整代码：`wrapper/src/main/java/com/xenoamess/hyperscan_panama/wrapper/Database.java:save()`。

### 2. `hs_deserialize_database`：`hs_database_t **db`

```java
MemorySegment bytePtr = arena.allocate(length);
bytePtr.copyFrom(MemorySegment.ofArray(bytes));

MemorySegment dbOut = arena.allocate(ValueLayout.ADDRESS);
int hsError = hyperscan.hs_deserialize_database(bytePtr, length, dbOut);
if (hsError != 0) {
    throw HyperscanException.hsErrorToException(hsError);
}

MemorySegment database = dbOut.get(ValueLayout.ADDRESS, 0);
database = database.reinterpret(Long.MAX_VALUE);
return new Database(database, expressions);
```

完整代码：`wrapper/src/main/java/com/xenoamess/hyperscan_panama/wrapper/Database.java:load()`。

### 3. `hs_compile_multi`：`hs_database_t **db` + `hs_compile_error_t **error`

```java
MemorySegment error = arena.allocate(ValueLayout.ADDRESS);
MemorySegment db = arena.allocate(ValueLayout.ADDRESS);

int hsError = hyperscan.hs_compile_multi(
        nativeExpressions.getExpressionsBytes(),
        nativeExpressions.getNativeFlags(),
        nativeExpressions.getNativeIds(),
        nativeExpressions.getSize(),
        hyperscan.HS_MODE_BLOCK(),
        MemorySegment.NULL,
        db,
        error);
```

### 4. `hs_expression_info`：`hs_expr_info_t **info` + `hs_compile_error_t **error`

```java
MemorySegment info = arena.allocate(ValueLayout.ADDRESS);
MemorySegment error = arena.allocate(ValueLayout.ADDRESS);

int hsResult = hyperscan.hs_expression_info(expressionPtr, getFlagBits(), info, error);
```

## 常见错误

| 错误 | 后果 |
|---|---|
| 把 `T**` 当作 `T*` 直接传缓冲区 | 函数把缓冲区内容当成指针，数据损坏或崩溃 |
| 输出指针变量未清空 | 某些函数在失败时不会写指针，残留值可能导致误判 |
| 未释放库分配的内存 | 如 `hs_serialize_database` 分配的 `char*` 需要调用者 `free()` |
| 用已关闭的 Arena 读取输出指针 | 输出指针变量本身要在 Arena 作用域内；读出的真实指针可长期持有 |

## 检查清单

- [ ] 参数类型是 `T*` 还是 `T**`？看 jextract 生成的 C 注释原型。
- [ ] 输出指针变量是否用 `ValueLayout.ADDRESS` 分配？
- [ ] 函数调用后是否从输出指针中读出真实地址？
- [ ] 库分配的内存是否需要手动释放？通常需要 `free()` 或对应 `hs_free_*`。
- [ ] 读取真实地址后是否用 `reinterpret(...)` 指定合理大小？

## 相关案例

- [数据库序列化往返踩坑](./serialization-roundtrip.md)
