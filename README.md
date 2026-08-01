# 本地笔记 (LocalNotes)

Kotlin + Jetpack Compose 的本地笔记 App。数据存放在你选择的文件夹中，换机时拷贝整个库目录即可迁移。

## 库目录结构

```
MyNotesLibrary/
  library.json          # 库元信息
  tree.json             # 虚拟文件夹/文档树
  docs/
    {docId}/
      note.md           # Markdown 正文
      assets/
        img_xxx.jpg     # 文档内图片
```

## 功能（MVP）

- 创建 / 打开笔记库（SAF 目录授权，可持久化）
- 虚拟树：新建文件夹、新建文档、重命名、删除
- Markdown 编辑：标题 / 加粗 / 列表 / 插入图片（图片复制进库内）
- 换机：拷贝整个库文件夹 → 新手机安装 App →「打开已有笔记库」

## 用 Android Studio 打开

1. 安装 [Android Studio](https://developer.android.com/studio)（带 JDK 17 + Android SDK）
2. `File → Open` 选择本目录 `android_app_note`
3. 等待 Gradle Sync
4. 连接真机或模拟器，运行 `app`

## 换机步骤

1. 旧手机：用文件管理器 / 电脑 USB，拷贝你的笔记库根目录
2. 新手机：把该目录放到任意位置（如下载、Documents）
3. 安装本 App → 点「打开已有笔记库」→ 选中该目录

## 技术栈

- Kotlin、Jetpack Compose、Navigation
- DataStore（记住上次打开的库 URI）
- DocumentFile / SAF（读写用户目录）
- kotlinx.serialization（`library.json` / `tree.json`）
