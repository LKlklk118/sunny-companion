package com.sunnycompanion.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Dify Chatflow 流式对话客户端。
 * 协议：POST {base}/chat-messages，response_mode=streaming，SSE 返回增量 answer。
 */
class DifyClient {

    interface Listener {
        fun onDelta(delta: String)
        fun onDone(fullText: String)
        fun onError(message: String)
    }

    @Volatile
    private var conversationId: String = ""

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var currentSource: EventSource? = null

    /** 发起一轮流式对话。 */
    fun chat(query: String, companion: String, config: AppConfig, listener: Listener) {
        cancel()

        val base = config.difyEndpoint.trim().trimEnd('/')
        val body = JsonObject().apply {
            addProperty("query", query)
            addProperty("response_mode", "streaming")
            addProperty("user", "sunny-android")
            addProperty("conversation_id", conversationId)
            addProperty("auto_generate_name", false)
            add("inputs", JsonObject().apply {
                addProperty("companion", companion)
                if (config.userName.isNotBlank()) addProperty("user_name", config.userName)
            })
        }

        val request = Request.Builder()
            .url("$base/chat-messages")
            .addHeader("Authorization", "Bearer ${config.difyApiKey}")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val sb = StringBuilder()
        var finished = false
        val factory = EventSources.createFactory(client)

        currentSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "message", "agent_message" -> {
                        try {
                            val obj = JsonParser.parseString(data).asJsonObject
                            val delta = obj.get("answer")?.takeIf { !it.isJsonNull }?.asString
                            if (!delta.isNullOrEmpty()) {
                                sb.append(delta)
                                listener.onDelta(delta)
                            }
                            obj.get("conversation_id")?.takeIf { !it.isJsonNull }?.asString
                                ?.let { if (it.isNotBlank()) conversationId = it }
                        } catch (_: Exception) {}
                    }
                    "message_end", "workflow_finished" -> {
                        try {
                            val obj = JsonParser.parseString(data).asJsonObject
                            obj.get("conversation_id")?.takeIf { !it.isJsonNull }?.asString
                                ?.let { if (it.isNotBlank()) conversationId = it }
                        } catch (_: Exception) {}
                        if (!finished) {
                            finished = true
                            listener.onDone(sb.toString())
                        }
                    }
                    "error" -> {
                        val msg = try {
                            JsonParser.parseString(data).asJsonObject.get("message")?.asString
                                ?: "服务端返回错误"
                        } catch (_: Exception) {
                            "服务端返回错误"
                        }
                        if (!finished) {
                            finished = true
                            listener.onError(msg)
                        }
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                // 流正常关闭但未收到 message_end 时的兜底
                if (!finished && sb.isNotEmpty()) {
                    finished = true
                    listener.onDone(sb.toString())
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

    private fun parseError(response: Response?, t: Throwable?): String {
        if (response != null) {
            return when (response.code) {
                401 -> "API Key 无效或已过期，请检查设置"
                404 -> "接口地址不对，请检查 Dify 端点（应形如 https://api.dify.ai/v1）"
                429 -> "请求太频繁，稍等几秒再试"
                else -> "请求失败（HTTP ${response.code}）"
            }
        }
        return t?.message ?: "网络错误，请检查网络连接"
    }

    /** 取消当前回复 / 清空会话记忆。 */
    fun cancel() {
        currentSource?.cancel()
        currentSource = null
    }

    fun resetConversation() {
        cancel()
        conversationId = ""
    }
}
