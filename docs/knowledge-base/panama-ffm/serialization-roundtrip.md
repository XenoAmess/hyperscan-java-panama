# 数据库序列化往返踩坑记录

## 现象

`Database.save()` + `Database.load()` 的往返测试在 wrapper 和 performance 模块中均失败：

```
com.gliwka.hyperscan.wrapper.HyperscanException: An invalid parameter has been passed. Is scratch allocated?
    at com.gliwka.hyperscan.wrapper.HyperscanException.hsErrorToException(HyperscanException.java:32)
    at com.gliwka.hyperscan.wrapper.Database.load(Database.java:337)
```

错误码为 `HS_INVALID`（-1），消息是 wrapper 中映射的通用文案，实际与 scratch 无关。

## 排查过程

1. 最初怀疑是 **Vectorscan 5.4.12 的 bug**，因为早先 summary 里记录“`hs_serialize_database` 产生 raw pointer copy、`hs_deserialize_database` 返回 -1”。
2. 用户提示：**原 JavaCPP 版本在 Linux 上跑的也是 Vectorscan**，且序列化测试通过。
3. 对比原 `hyperscan-java` 的 `Database.save()`：原代码使用 `BytePointer(1)` 极小缓冲区，先调用 `hs_serialize_database` 拿到 `size`，再调整容量读取。
4. 检查 jextract 生成代码，发现关键差异：

```java
public static int hs_serialize_database(MemorySegment db, MemorySegment bytes, MemorySegment length)
```

其 C 原型为：

```c
hs_error_t hs_serialize_database(const hs_database_t *db, char **bytes, size_t *length);
```

注意第二个参数是 **`char **bytes`**，不是 `char *bytes`。

## 根因

旧实现把第二个参数当成了接收缓冲区：

```java
// 错误
MemorySegment size = arena.allocate(hyperscan.size_t);
size.set(hyperscan.size_t, 0, dbLength);
MemorySegment bytes = arena.allocate(dbLength);
hsError = hyperscan.hs_serialize_database(database, bytes, size);
```

FFM 把 `bytes` 这个 `MemorySegment` 解释为“指向 `char*` 的指针的地址”，于是函数把 `dbLength` 等内容误当作指针写入了缓冲区开头的 8 字节，导致序列化数据完全损坏。后续 `load()` 拿到损坏数据后 `hs_deserialize_database` 返回 `HS_INVALID`。

## 正确做法

按库的两步输出参数语义实现：传一个 `ValueLayout.ADDRESS` 大小的输出指针，由库分配缓冲区，读取后使用标准 C `free()` 释放。

见 `wrapper/src/main/java/com/gliwka/hyperscan/wrapper/Database.java`：

```java
MemorySegment bytesOut = arena.allocate(ValueLayout.ADDRESS);
MemorySegment size = arena.allocate(hyperscan.size_t);
int hsError = hyperscan.hs_serialize_database(database, bytesOut, size);
if (hsError != 0) {
    throw HyperscanException.hsErrorToException(hsError);
}

long length = size.get(hyperscan.size_t, 0);
MemorySegment bytes = bytesOut.get(ValueLayout.ADDRESS, 0).reinterpret(length);

DataOutputStream databaseDataOut = new DataOutputStream(databaseOut);
databaseDataOut.writeInt((int) length);
databaseDataOut.write(bytes.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE));
databaseDataOut.flush();

hyperscan.free(bytes);
```

`load()` 一侧原实现就是正确的：分配 `MemorySegment db = arena.allocate(ValueLayout.ADDRESS)` 作为 `hs_database_t**` 输出指针，调用 `hs_deserialize_database(bytePtr, length, db)`，再读出指针即可。

## 验证

- `wrapper/src/test/java/com/gliwka/hyperscan/wrapper/DatabaseTest.java#testSerializationDeserializationRoundtrip` 重新启用后通过。
- `performance/src/test/java/com/xenoamess/hyperscan/performance/WrapperSmokeTest.java#databaseSerializationRoundTrip` 重新启用后通过。
- `mvn test -pl wrapper,performance -am` 全绿。

## 相关模式

- [指针输出参数处理模式](./pointer-output-arguments.md)

## 时间线

- 2026-07-08：修复并启用序列化测试。
