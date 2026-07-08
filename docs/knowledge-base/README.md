# hyperscan-java-panama 项目知识库

记录项目中的非平凡经验、踩坑记录和最佳实践，供后续开发和维护参考。

## 目录

- [FFM / Panama 绑定](./panama-ffm/)
  - [数据库序列化往返踩坑](./panama-ffm/serialization-roundtrip.md)
  - [指针输出参数处理模式](./panama-ffm/pointer-output-arguments.md)
- [Native 构建](./native-build/)

## 写作规范

1. **每个案例必须包含**：现象、根因、错误做法、正确做法、验证方式。
2. **通用模式单独成文**：案例文件引用模式文件，避免重复。
3. **代码片段真实可编译**：优先引用项目中实际存在的代码路径。
4. **中文撰写**：知识库统一使用中文。
5. **更新 AGENTS.md**：新增重大经验时，视情况在 `docs/AGENTS.md` 的 Pitfalls 或 Checklist 中同步摘要。
