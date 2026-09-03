package com.sunnycompanion.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 阳光陪伴 · 主界面
 * 架构：WebView 承载 assets/chat 的阳光可爱 H5 UI；
 *       原生层通过 addJavascriptInterface 暴露 SunnyBridge：
 *         - Dify Chatflow 流式对话（SSE）
 *         - 男/女声 TTS 朗读（MiniMax / SiliconFlow 可插拔）
 *         - 可选系统语音输入
 *         - 本地配置存取（SharedPreferences）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var dify: DifyClient
    private lateinit var tts: TtsManager
    private lateinit var voice: VoiceInputHelper
    private lateinit var webView: WebView
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        dify = DifyClient()
        tts = TtsManager(this)
        voice = VoiceInputHelper(this)
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        webView = WebView(this)
        setContentView(webView)

        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.allowFileAccess = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.textZoom = 100

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        // 便于调试；正式发布可移除
        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(SunnyBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/chat/index.html")
    }

    override fun onResume() {
        super.onResume()
        // 从本地重读最新配置（可能在设置页之外被改）
        settings.load()
    }

    override fun onDestroy() {
        dify.resetConversation()
        tts.stop()
        voice.stop()
        super.onDestroy()
    }

    // ---------- JS 回调 ----------
    private fun jsEval(script: String) {
        runOnUiThread {
            try {
                webView.evaluateJavascript(script, null)
            } catch (_: Exception) {
            }
        }
    }

    private fun jsString(s: String): String = gson.toJson(s) // JSON 字符串字面量，天然转义

    private fun jsCall(fn: String, arg: String) {
        jsEval("window.__sunnyCallbacks.$fn(${jsString(arg)})")
    }

    // ---------- 对话 ----------
    private fun doChat(json: String) {
        val cfg = settings.load()
        if (cfg.difyApiKey.isBlank()) {
            jsCall("onError", "请先到右上角 ⚙️ 设置里填写 Dify API Key")
            return
        }
        val obj = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            null
        } ?: run {
            jsCall("onError", "参数解析失败")
            return
        }
        val query = obj.get("query")?.asString ?: ""
        val companion = obj.get("companion")?.asString ?: "boy"
        if (query.isBlank()) {
            jsCall("onError", "消息为空")
            return
        }
        dify.chat(query, companion, cfg, object : DifyClient.Listener {
            override fun onDelta(delta: String) {
                jsCall("onDelta", delta)
            }

            override fun onDone(fullText: String) {
                jsCall("onDone", fullText)
            }

            override fun onError(message: String) {
                jsCall("onError", message)
            }
        })
    }

    // ---------- 语音合成 ----------
    private fun speak(text: String, role: String) {
        tts.speak(text, role, settings.load(), object : TtsManager.Listener {
            override fun onState(state: String, message: String) {
                jsCall("onTtsState", "$state|$message")
            }
        })
    }

    // ---------- 语音输入 ----------
    private fun startVoice() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        voice.start(object : VoiceInputHelper.Listener {
            override fun onResult(text: String) {
                jsCall("onVoiceResult", text)
            }

            override fun onError(message: String) {
                jsCall("onTtsState", "error|$message")
            }
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoice()
        } else {
            Toast.makeText(this, "需要录音权限才能语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 原生桥（暴露给 H5） ----------
    inner class SunnyBridge {

        @JavascriptInterface
        fun getConfigJson(): String = gson.toJson(settings.load())

        /** 是否被口令锁定（内置了授权口令但尚未解锁）。 */
        @JavascriptInterface
        fun getLockState(): String {
            val locked = settings.isLocked()
            val obj = JsonObject().apply { addProperty("locked", locked) }
            return obj.toString()
        }

        /** 校验口令并一键授权：正确则写入全部内置配置。 */
        @JavascriptInterface
        fun authorize(passcode: String): String {
            val ok = settings.tryUnlock(passcode ?: "")
            val obj = JsonObject().apply {
                addProperty("ok", ok)
                addProperty("msg", if (ok) "解锁成功" else "口令错误，请重试")
            }
            return obj.toString()
        }

        @JavascriptInterface
        fun notify(fn: String, json: String) {
            when (fn) {
                "chat" -> doChat(json)
                "saveConfig" -> {
                    try {
                        val cfg = gson.fromJson(json, AppConfig::class.java)
                        settings.save(cfg)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "配置保存失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun speak(text: String, role: String) = this@MainActivity.speak(text, role)

        @JavascriptInterface
        fun stopSpeak() {
            tts.stop()
        }

        @JavascriptInterface
        fun startVoice() = this@MainActivity.startVoice()

        @JavascriptInterface
        fun voiceAvailable(): Boolean = voice.isAvailable()
    }
}
