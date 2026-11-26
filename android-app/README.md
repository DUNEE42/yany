# 言语 Android 个人版

言语是 Android 原生的 AI 陪伴聊天应用，当前定位是个人 BYOK 版本：

```text
Android App -> DeepSeek API
```

它不使用 `server/`，也没有公共账号、充值或平台代理。

伴侣名为“林”。内部包名继续使用 `com.xinyu.companion`，用于覆盖升级并保留本机加密数据。

## 当前能力

- Kotlin + Jetpack Compose 原生界面。
- DeepSeek OpenAI-compatible SSE 流式回复。
- 停止生成、失败重试、断流恢复。
- API Key 使用 Android Keystore + AES-GCM 加密保存在本机。
- 聊天记录使用 Room + SQLCipher 加密。
- 对话手感可调：temperature、亲近感、回复长度。
- 短期工作记忆、可追溯长期记忆和用户画像。
- 按应用开启的有限环境感知：通知摘要、前台应用、粗略城市、天气和 SearXNG。
- 搜索前确认实际外发查询词，确认只绑定当前消息。
- 本地加密定时提醒，使用 Android 系统通知并支持重启恢复。
- 设置页分离模型连接、对话手感、记忆、提醒、环境感知、隐私与数据、关于。
- Provider、设置存储和会话存储都通过领域端口接入。
- 危机表达和强烈痛苦表达使用不同的本地安全处理。
- 只记录不带聊天正文的生成耗时和错误码，便于本机排障。

## 架构

```text
UI / Compose
  -> ChatViewModel
  -> Domain
     -> ChatEngine
     -> ChatProvider
     -> ChatConversationStore
     -> MemoryContextProvider / PerceptionContextProvider
     -> ProviderSettingsStore
  <- Data adapters
     -> DeepSeekApi
     -> ChatRepository
     -> MemoryRepository / PerceptionRepository
     -> ReminderCoordinator
     -> Room + SQLCipher
     -> Android Keystore
     -> AlarmManager + BroadcastReceiver
```

领域层不得导入 `data`、`ui` 或 `di` 包。该规则由
`ArchitectureBoundaryTest` 自动检查。

## 运行

环境要求：

- JDK 17
- Android SDK 35
- 可用网络访问 Maven 仓库

```powershell
$env:JAVA_HOME="C:\tools\jdk-17.0.21.9"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:ANDROID_HOME="C:\Users\Administrator\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME

.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

如果 Windows/JDK 17 报 `Unable to establish loopback connection`：

```powershell
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\__xinyu_no_such_dir__"
```

## 数据边界

- API Key 不会写入源码或 APK。
- 聊天记录默认只保存在本机。
- 每轮请求的必要上下文会通过 HTTPS 发送给 DeepSeek。
- 删除 Key 和清空聊天是两个独立操作。
- Android 云备份和设备迁移不包含数据库与 SharedPreferences。

## 测试

JVM/Robolectric 测试覆盖：

- 领域依赖边界。
- 消息生命周期、停止、重试和运行代次隔离。
- SSE 正常完成、401、提前断流、流内错误和畸形数据。
- Room 事务、外键、级联删除、迁移和记忆查询上限。
- 设置存储、用户流程和非正式设备交互。
- 安全策略的危机、痛苦、否定与普通表达。
- 本地提醒校验、保存失败回滚、取消和调度。
- 搜索确认、取消、编辑和硬敏感字段阻断。

Android instrumentation 测试覆盖：

- 首次连接页。
- 设置页主要分区和提醒入口。
- 隐私文案不再暗示聊天内容只留在本机。
- 搜索确认面板和提醒页视觉渲染。
- 加密提醒规则、系统闹钟和通知送达闭环。

该目录需要通过 Android 手机或模拟器运行。

## 当前边界

- 单会话。
- 无账户、支付和公共代理。
- 无向量 RAG、工具调用、日历导入或后台自动推断课程。
- 主动提醒必须由用户明确设定时间，省电模式可能延迟。
- 不读取屏幕、无障碍节点或其他应用内部内容。
- 无正式 release 签名。
- AI 伴侣不能替代现实关系、专业心理支持或紧急服务。
