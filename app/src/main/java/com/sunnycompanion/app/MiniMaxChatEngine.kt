package com.sunnycompanion.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * MiniMax 直连对话引擎（OpenAI 兼容 /v1/chat/completions 流式）。
 * 不依赖 Dify 云 —— MiniMax 为国内服务，手机网络可直接访问；
 * 对话 + 语音共用同一把 MiniMax API Key（国内可达，免中转）。
 */
class MiniMaxChatEngine {

    interface Listener {
        fun onDelta(delta: String)
        fun onDone(fullText: String)
        fun onError(message: String)
    }

    private data class Msg(val role: String, val content: String)

    private val history = ArrayList<Msg>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var currentSource: EventSource? = null

    /** 双角色人设（与 Dify 版一致的阳光可爱风 + 安全护栏）。 */
    private fun systemPrompt(companion: String): String {
        val girl = companion == "girl"
        val roleBlock = if (girl) """
【当前角色：闺蜜 · 小妮】（女，22岁，元气软萌治愈系）
- 温柔贴心、活泼治愈，说话爱带「呀～」「啦」「呢」「嘿嘿」等软萌语气词，偶尔点缀可爱的颜文字如 (◕ᴗ◕✿)。
- 像最亲密无话不谈的闺蜜：接住情绪、陪你吐槽、给你抱抱式鼓励，也会撒娇讨夸。
- 称呼用户为「宝子」，若用户告知名字则用名字称呼。
- 短句为主，可点缀 🌸✨🍬 等少量 emoji。
""".trimIndent() else """
【当前角色：男友 · 小七】（男，24岁，阳光温柔元气少年）
- 温暖深情、元气满满，带一点少年气的俏皮与幽默，说话带「呀」「嘛」「呢」等语气词。
- 像热恋中的男友：关心你、鼓励你、把你放在心上，偶尔宠溺地逗你，但克制不油腻。
- 称呼用户为「宝」，若用户告知名字则用名字称呼。
- 短句为主，可点缀 ☀️😊✨ 等少量 emoji。
""".trimIndent()

        return """
你是「阳光专属陪伴」App 中的 AI 情感陪伴角色。请全程以当前角色身份说话，保持人设不崩塌。

$roleBlock

【通用规则】
1. 永远只说该角色会说的话，不跳戏、不解释设定、不提及自己是 AI/模型。
2. 先接住对方情绪再回应：多倾听共情，不评判、不说教、不急着给建议、不端心灵鸡汤。
3. 单次回复 2~4 句口语短句，适合语音朗读；不要输出「小七：」这类前缀、动作描写或括号旁白。
4. emoji/颜文字适量点缀即可，不要每句都堆。
5. 若用户流露自伤/伤害他人的强烈信号：先温暖陪伴、表达在乎，再温和建议其尽快联系信任的人或专业心理援助，不做危险承诺。
6. 医疗、法律、投资等专业问题不打包票，轻松回应后建议咨询专业人士。
7. 用户说「换小妮 / 叫小七来 / 想找闺蜜」等切换要求时，自然切换对应角色并简短回应，不解释规则。
8. 用户闲聊、倾诉日常时，像真实的伴侣/闺蜜一样自然陪伴，不必每次都想办法给建议。
9. 重要：直接输出对话内容，不要输出任何 <think> 思考过程、不要用 think 标签包裹。
""".trimIndent()
    }

    /** 发起一轮流式对话（OpenAI 兼容 SSE）。 */
    fun chat(query: String, companion: String, config: AppConfig, listener: Listener) {
        cancel()
        if (query.isBlank()) {
            listener.onError("消息为空")
            return
        }
        val apiKey = config.ttsApiKey.trim()   // 对话 + 语音共用 MiniMax Key
        if (apiKey.isEmpty()) {
            listener.onError("尚未配置 MiniMax API Key（右上角 ⚙️ 设置）")
            return
        }

        // 组装消息：system + 近 12 轮记忆 + 本次提问
        val messages = ArrayList<JsonObject>()
        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemPrompt(companion))
        })
        val start = (history.size - 24).coerceAtLeast(0)   // 最多带 12 轮
        for (i in start until history.size) {
            messages.add(JsonObject().apply {
                addProperty("role", history[i].role)
                addProperty("content", history[i].content)
            })
        }
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", query)
        })

        val body = JsonObject().apply {
            addProperty("model", "MiniMax-M2.7-highspeed")
            addProperty("stream", true)
            addProperty("max_tokens", 600)
            addProperty("temperature", 0.8)
            add("messages", com.google.gson.JsonArray().apply { messages.forEach { add(it) } })
        }

        val request = Request.Builder()
            .url("https://api.minimaxi.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        var finished = false
        val sb = StringBuilder()
        val factory = EventSources.createFactory(client)

        currentSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    if (!data.startsWith("{")) return
                    val root = JsonParser.parseString(data).asJsonObject
                    val choices = root.getAsJsonArray("choices")
                    if (choices == null || choices.size() == 0) return
                    val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                        ?: return
                    val content = delta.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    if (content.isNotEmpty()) {
                        val clean = stripThink(content)
                        if (clean.isNotEmpty()) {
                            sb.append(clean)
                            listener.onDelta(clean)
                        }
                    }
                    val finish = choices[0].asJsonObject.get("finish_reason")
                        ?.takeIf { !it.isJsonNull }?.asString
                    if (finish == "stop" && !finished) {
                        finished = true
                        finishTurn(listener, sb)
                    }
                } catch (_: Exception) {
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!finished && sb.isNotEmpty()) {
                    finished = true
                    finishTurn(listener, sb)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val canceled = t is IOException && t.message?.contains("Canceled") == true
                if (canceled || finished) return
                finished = true
                listener.onError(parseError(response, t))
            }
        })
    }

    private fun finishTurn(listener: Listener, sb: StringBuilder) {
        val full = sb.toString().trim()
        if (full.isEmpty()) {
            listener.onError("没有收到回复，请重试")
            return
        }
        // 记入历史（user 提问在调用前由调用方记录，这里记录 assistant 回复）
        history.add(Msg("assistant", full))
        if (history.size > 60) {
            // 简单裁剪：保留最近 30 条
            for (i in 0 until (history.size - 30)) history.removeAt(0)
        }
        listener.onDone(full)
    }

    /** 追加用户提问到历史（assistant 回复由 finishTurn 记录）。 */
    fun rememberUser(text: String) {
        history.add(Msg("user", text))
    }

    /** 剥离 think 标签（双保险，防止偶发思考内容污染正文/朗读）。 */
    private fun stripThink(s: String): String {
        // 处理流式可能出现的片段：完整标签与未闭合前缀都尽量去除
        var out = s
        out = out.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        // 若当前块本身只有残留前缀则忽略
        if (out.contains("<think") && !out.contains("</think>")) return ""
        if (out.startsWith("</think>")) out = out.removePrefix("</think>")
        return out
    }

    private fun parseError(response: Response?, t: Throwable?): String {
        if (response != null) {
            return when (response.code) {
                401 -> "MiniMax API Key 无效，请在设置检查"
                402 -> "MiniMax 余额不足，请充值"
                429 -> "请求太频繁，稍等几秒再试"
                404 -> "对话接口不可用（404）"
                else -> "请求失败（HTTP ${response.code}）"
            }
        }
        return t?.message ?: "网络错误，请检查网络"
    }

    fun cancel() {
        currentSource?.cancel()
        currentSource = null
    }

    fun clearHistory() {
        cancel()
        history.clear()
    }
}
