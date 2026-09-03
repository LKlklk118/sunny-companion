# ☀️ 阳光陪伴 · AI 情感陪伴安卓 App（Demo）

阳光可爱的 AI 情感陪伴应用：**男友「小七」/ 闺蜜「小妮」** 双角色聊天，支持男/女声语音朗读回复。

## 架构

```
安卓 App (WebView 壳 + H5 阳光 UI)
 ├─ 原生 Kotlin：Dify 流式对话 / TTS 男·女声朗读 / 语音输入 / 配置存取
 └─ Dify Cloud Chatflow「情感陪伴」：双角色人设 + 会话记忆（deepseek-v4-flash，额度计费）
```

## 目录结构

```
ai-companion-android/
├─ app/
│  ├─ src/main/
│  │  ├─ assets/chat/          # H5 阳光可爱聊天界面（index.html/style.css/app.js）
│  │  ├─ java/com/sunnycompanion/app/
│  │  │  ├─ MainActivity.kt    # WebView 壳 + JS 桥（SunnyBridge）
│  │  │  ├─ DifyClient.kt      # Dify chat-messages SSE 流式客户端
│  │  │  ├─ TtsManager.kt      # TTS：MiniMax / SiliconFlow 双驱动，男/女音色
│  │  │  ├─ VoiceInputHelper.kt# 可选：系统语音输入
│  │  │  └─ SettingsStore.kt   # 本地配置（SharedPreferences）
│  │  └─ res/                  # 图标（手绘小太阳）+ 主题
├─ .github/workflows/android-release.yml  # 云端打包 → APK/Release
├─ docs/DIFY_CONFIG.md         # Dify 云端配置与 API 调用说明
├─ local.secrets.properties    # ⚠️ 本地密钥（已 gitignore，勿上传）
└─ backup/                     # 旧应用 DSL 备份
```

## 快速开始

1. **Dify 云端**：已完成（应用「情感陪伴」，见 `docs/DIFY_CONFIG.md`）
2. **本地联调**（可选，需 Android SDK）：
   ```
   gradle assembleDebug
   ```
3. **云端打包**：把本目录推到 GitHub 仓库 main 分支
   - Actions 自动构建，产物在 Artifacts 下载
   - 打 tag（`v1.0.0`）→ 自动发布 GitHub Releases 直链
4. **手机安装**：APK 安装后打开 → 右上角 ⚙️ 填入：
   - Dify Endpoint `https://api.dify.ai/v1` + API Key（见 local.secrets.properties）
   - 语音平台 Key + 男/女音色 ID（注册 MiniMax 或 SiliconFlow 后填写）

## 语音平台注册提示（二选一）

| 平台 | 说明 |
|---|---|
| MiniMax | 音色自然，speech-02-hd；男声如 `male-qn-qingse`，女声如 `female-shaonv`（以官网音色库为准） |
| SiliconFlow | CosyVoice2 等；免费额度注册送 |

## 免责

Demo 用途：API Key 存本机/内置仅限测试，勿公开分发含密钥的包；正式上线请加服务端代理与内容安全。
