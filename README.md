# 本地笔记 (LocalNotes)

Kotlin + Jetpack Compose 本地笔记 App。数据在用户选择的文件夹中，换机拷贝整库即可。

## 库目录

```
MyNotesLibrary/
  library.json
  tree.json
  docs/{docId}/note.md
  docs/{docId}/assets/...
```

## 功能

- 创建 / 打开笔记库（防覆盖已有库）
- 虚拟文件夹树、收藏、更新时间
- Markdown 编辑 + 预览（标题/加粗/斜体/列表/链接/代码块/图片）
- 光标处插入、自动保存
- 全文搜索
- 回收站（恢复 / 永久删除 / 清空）
- 打开库完整性校验与孤儿文档恢复
- 导出单篇 Markdown / 整库 ZIP
- 插入图片自动压缩

## 运行

用 Android Studio 打开本目录，Sync 后运行到真机或模拟器。
