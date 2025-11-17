# AI解题助手 App

一个基于Kotlin开发的智能解题辅助应用，通过AI技术帮助用户快速解答各类问题。

## 📋 项目简介

AI解题助手是一款功能强大的移动应用程序，集成了人工智能技术，为学生和学习者提供实时的解题支持和学习辅导。应用采用Kotlin语言开发，确保代码质量和开发效率。

## ✨ 核心功能

- 🤖 **AI智能解答** - 利用先进的AI模型快速解答数学、物理、化学等各科问题
- 📸 **拍照识题** - 支持拍照上传题目，自动识别并解答
- 💬 **对话交互** - 与AI进行多轮对话，深入理解题目解法
- 📚 **题库管理** - 收藏和整理常见题型，方便复习
- 🔍 **详细解析** - 提供详细的解题步骤和知识点讲解
- 📊 **学习统计** - 记录学习进度，分析薄弱环节

## 🛠 技术栈

- **语言**: Kotlin 100%
- **平台**: Android
- **架构**: MVVM/MVC
- **数据库**: Room Database / SQLite
- **网络请求**: Retrofit / OkHttp
- **UI框架**: Jetpack Compose / Material Design

## 📦 项目结构

```
AI-/
├── app/                    # 应用主模块
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/      # Kotlin源代码
│   │   │   ├── res/       # 资源文件
│   │   │   └── AndroidManifest.xml
│   │   ├── test/          # 单元测试
│   │   └── androidTest/   # 仪器测试
│   └── build.gradle
├── gradle/
├── settings.gradle
└── README.md
```

## 🚀 快速开始

### 环境要求

- Android Studio Arctic Fox 或更高版本
- JDK 11+
- Android SDK 30+
- Kotlin 1.8+

### 安装步骤

1. **克隆项目**
   ```bash
   git clone https://github.com/ayyj76/AI-.git
   cd AI-
   ```

2. **打开项目**
   - 使用Android Studio打开项目文件夹

3. **配置环境**
   - 同步Gradle依赖
   - 配置API密钥（如需要）

4. **构建应用**
   ```bash
   ./gradlew build
   ```

5. **运行应用**
   - 在Android Studio中点击Run，或使用命令：
   ```bash
   ./gradlew installDebug
   ```

## 📖 使用指南

### 基础操作

1. **打开应用** - 启动AI解题助手
2. **选择科目** - 选择相关学科（数学、物理等）
3. **输入题目** - 手动输入或拍照上传题目
4. **获取解答** - AI自动分析并提供解题方案
5. **查看详解** - 点击展开查看详细步骤

### 高级功能

- 保存收藏的题目
- 导出解题过程
- 搜索历史记录
- 自定义学习计划

## 🔧 配置说明

### API配置(项目自带免费额度APIKey，用完需自配！)

在 `local.properties` 中配置API密钥：
```properties
API_KEY=your_api_key_here
API_BASE_URL=https://api.example.com/
```

### 开发配置

在 `build.gradle` 中配置编译选项：
```gradle
android {
    compileSdk 34
    defaultConfig {
        minSdk 24
        targetSdk 34
    }
}
```

## 📝 开发规范

- 遵循Kotlin官方编码规范
- 使用驼峰命名法
- 添加必要的代码注释
- 编写单元测试覆盖核心功能
- 提交前进行代码审查

## 🐛 常见问题

**Q: 应用无法连接API？**
A: 请检查网络连接和API密钥配置是否正确。

**Q: 识题功能不准确？**
A: 确保照片清晰，题目完整，光线充足。

**Q: 应用崩溃？**
A: 查看Logcat日志，检查依赖版本是否兼容。

## 🤝 贡献指南

欢迎提交Issue和Pull Request！

### 贡献步骤

1. Fork本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启Pull Request

## 📄 许可证

本项目采用 MIT License - 详见 [LICENSE](LICENSE) 文件

## 👤 作者

- **GitHub**: [@ayyj76](https://github.com/ayyj76)

## 📞 联系方式

- 提交Issue进行反馈
- 邮箱：yujiann111@gmail.com

## 🙏 致谢

感谢所有贡献者和用户的支持！

---

**最后更新**: 2025年11月17日
