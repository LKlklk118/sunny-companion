# 阳光陪伴 · 项目配置速查

## Dify 云端（已完成 ✅）
- 应用名称：情感陪伴（Chatflow）
- 应用 ID：`6e4c70e6-2f0c-4c33-8cb6-bebdc4829680`
- API Endpoint：`https://api.dify.ai/v1`
- API Key：见 `local.secrets.properties`（已 gitignore）
- Web 演示：`https://udify.app/chat/oHg2bLwvUI8FoOqy`
- 模型：deepseek-v4-flash（Dify 消息额度计费）
- 角色切换：开始节点变量 `companion`（boy=小七 / girl=小妮），LLM 内用 Jinja 按变量渲染对应人设
- 记忆：LLM 节点记忆窗口 10 轮
- 开场白 + 3 条建议问题：已配置

## 调用说明（给安卓端 / API 客户端）
POST {endpoint}/chat-messages
Header: Authorization: Bearer {DIFY_API_KEY}
Body:
{
  "inputs": { "companion": "boy" },        // boy=男友小七 | girl=闺蜜小妮
  "query": "用户说的话",
  "response_mode": "streaming",
  "user": "sunny-android",
  "conversation_id": ""
}
SSE 事件类型：message（增量 answer）/ message_end（结束，含 conversation_id）

## 人设要点
- 男友小七（boy）：24 岁阳光温柔元气少年，称呼「宝」，宠溺克制
- 闺蜜小妮（girl）：22 岁元气软萌治愈系，称呼「宝子」，贴心撒娇
- 统一规则：短句 2~4 句适合朗读、无括号旁白、先共情不说教、安全护栏

## 待办
- [ ] 注册语音平台拿 TTS Key（minimax/siliconflow），填入 local.secrets.properties
- [ ] 补全 Android H5 聊天 UI + MainActivity 壳
- [ ] GitHub Actions 打包流水线
