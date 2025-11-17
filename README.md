# AI悬浮窗快速解题助手

一个基于 Kotlin + Jetpack Compose 开发的 AI 解题助手应用。

## 📋 项目简介

该助手是一款智能化的移动应用程序，通过 AI 技术为用户提供实时的问题解答和学习支持。

## ✨ 主要特性

- 🤖 **AI 智能解答** - 快速解答各类问题
- 💬 **网络请求** - 基于 OkHttp 的稳定网络通信
- ⚡ **高性能异步处理** - 使用 Kotlin Coroutines
- 🎨 **现代化 UI** - 基于 Jetpack Compose 构建

## 🛠 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| **Kotlin** | 100% | 核心开发语言 |
| **Jetpack Compose** | Latest | UI 框架 |
| **OkHttp** | Latest | HTTP 网络请求 |
| **Kotlin Coroutines** | Latest | 异步编程 |
| **Android SDK** | 36 | 目标 API 级别 |

## 📦 项目结构

```
app/
├── src/main/
│   ├── java/                    # Kotlin 源代码
│   │   └── com/yyj/aiapp/       # 应用包名
│   ├── res/                     # 资源文件
│   └── AndroidManifest.xml      # 应用清单
├── build.gradle.kts             # 应用模块构建配置
└── proguard-rules.pro           # 混淆规则
```

## 🚀 快速开始

### 环境要求

- Android Studio（最新版本）
- JDK 11 或更高版本
- Android SDK 36
- Kotlin 1.9+

### 构建与运行

1. **克隆项目**
   ```bash
   git clone https://github.com/ayyj76/AI-.git
   cd AI-
   ```

2. **使用 Android Studio 打开项目**
   - 打开 Android Studio
   - 选择 "Open an Existing Project"
   - 选择项目根目录

3. **构建项目**
   ```bash
   ./gradlew build
   ```

4. **运行应用**
   - 连接 Android 设备或启动模拟器
   - 点击 Run 按钮或执行：
   ```bash
   ./gradlew installDebug
   ```

## 📋 系统要求

- **最小 SDK**：Android 8.0（API 26）
- **目标 SDK**：Android 15（API 36）
- **Java 兼容性**：Java 11

## 🔌 依赖库

- `androidx.appcompat` - 应用兼容性库
- `androidx.core.ktx` - Kotlin 扩展
- `androidx.compose.*` - Jetpack Compose UI
- `androidx.lifecycle.runtime.ktx` - 生命周期管理
- `androidx.activity.compose` - Activity Compose 集成
- `androidx.constraintlayout` - 约束布局
- `androidx.fragment.ktx` - Fragment Kotlin 扩展
- `okhttp` - HTTP 网络客户端
- `kotlinx.coroutines.android` - 协程库
- `androidx.localbroadcastmanager` - 本地广播管理

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

MIT License

## 👤 作者

- **GitHub**: [@ayyj76](https://github.com/ayyj76)
- **Gmail**:yujiann111@gmail.com

---

**最后更新**: 2025-11-17
