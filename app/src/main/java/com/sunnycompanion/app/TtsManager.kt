package com.sunnycompanion.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 文本朗读（TTS）。驱动可插拔：
 *  - minimax    ：MiniMax speech-2.5-hd-preview（音色自然、适合陪伴）
 *  - siliconflow：硅基流动 CosyVoice2-0.5B（开源模型聚合平台）
 * 新平台接入：在 speak() 里新增一个 buildXxxCall() 分支即可。
 */
class TtsManager(private val context: Context) {

    interface Listener {
        /** state: started | done | error | stopped */
        fun onState(state: String, message: String)
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private var player: MediaPlayer? = null
    private var activeCall: Call? = null
    @Volatile private var cancelled = false

    val isPlaying: Boolean get() = player?.isPlaying == true

    fun speak(text: String, role: String, config: AppConfig, listener: Listener?) {
        stop()
        cancelled = false

        val voice = if (role == "girl") config.voiceGirl else config.voiceBoy
        if (voice.isBlank()) {
            listener?.onState("error", "尚未设置该角色的音色 ID，请到右上角设置里填写")
            return
        }
        if (config.ttsApiKey.isBlank()) {
            listener?.onState("error", "尚未填写语音服务 API Key，请到设置里填写")
            return
        }

        val call: Call = when (config.ttsProvider) {
            "siliconflow" -> buildSiliconFlowCall(text, voice, config.ttsApiKey)
            else -> buildMiniMaxCall(text, voice, config.ttsApiKey)
        }
        activeCall = call
        listener?.onState("started", "")

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cancelled) return
                listener?.onState("error", "语音请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (cancelled) return
                try {
                    if (!response.isSuccessful) {
                        val msg = response.body?.string().orEmpty().take(200)
                        listener?.onState("error", "语音服务错误（HTTP ${response.code}）$msg")
                        return
                    }
                    val audio: ByteArray = if (config.ttsProvider == "minimax") {
                        val root = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
                        val hex = root.getAsJsonObject("data")?.get("audio")?.takeIf { !it.isJsonNull }?.asString
                        if (hex.isNullOrEmpty()) {
                            listener?.onState("error", "语音接口返回内容异常")
                            return
                        }
                        hexToBytes(hex)
                    } else {
                        response.body?.bytes() ?: return
                    }
                    playBytes(audio, listener)
                } catch (e: Exception) {
                    listener?.onState("error", "语音处理失败：${e.message}")
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun buildMiniMaxCall(text: String, voice: String, apiKey: String): Call {
        val body = JsonObject().apply {
            // MiniMax T2A v2：模型名在请求体；响应 audio 为 hex 编码
            addProperty("model", "speech-2.5-hd-preview")
            addProperty("text", text)
            addProperty("stream", false)
            add("voice_setting", JsonObject().apply {
                addProperty("voice_id", voice)
                addProperty("speed", 1.0)
                addProperty("vol", 1.0)
                addProperty("pitch", 0)
            })
            add("audio_setting", JsonObject().apply {
                addProperty("sample_rate", 32000)
                addProperty("bitrate", 128000)
                addProperty("format", "mp3")
                addProperty("channel", 1)
            })
        }
        return client.newCall(
            Request.Builder()
                .url("https://api.minimaxi.com/v1/t2a_v2")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
    }

    /** MiniMax 返回的音频是 hex 字符串，转为字节数组。 */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        val out = ByteArray(len)
        for (i in 0 until len) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            out[i] = ((hi shl 4) + lo).toByte()
        }
        return out
    }

    private fun buildSiliconFlowCall(text: String, voice: String, apiKey: String): Call {
        val body = JsonObject().apply {
            addProperty("model", "FunAudioLLM/CosyVoice2-0.5B")
            addProperty("input", text)
            addProperty("voice", voice)
            addProperty("response_format", "mp3")
            addProperty("speed", 1.0)
        }
        return client.newCall(
            Request.Builder()
                .url("https://api.siliconflow.cn/v1/audio/speech")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
    }

    private fun playBytes(bytes: ByteArray, listener: Listener?) {
        try {
            val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            file.writeBytes(bytes)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                cleanupPlayer()
                listener?.onState("done", "")
            }
            mp.setOnErrorListener { _, _, _ ->
                cleanupPlayer()
                listener?.onState("error", "播放失败")
                true
            }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            listener?.onState("error", "播放失败：${e.message}")
        }
    }

    /** 停止播放并取消进行中的请求。 */
    fun stop() {
        cancelled = true
        activeCall?.cancel()
        cleanupPlayer()
    }

    private fun cleanupPlayer() {
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }
        player = null
    }
}
