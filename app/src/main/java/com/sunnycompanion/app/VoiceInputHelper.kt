package com.sunnycompanion.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 「按住说话」语音输入（依赖系统语音识别服务，如 Google / 厂商语音引擎）。
 * 若设备不支持，UI 会隐藏麦克风按钮；功能是否可用由 isAvailable() 判断。
 */
class VoiceInputHelper(private val context: Context) {

    interface Listener {
        fun onResult(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** 必须在主线程调用。 */
    fun start(listener: Listener) {
        stop()
        this.listener = listener
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    this@VoiceInputHelper.listener?.onResult(text)
                } else {
                    this@VoiceInputHelper.listener?.onError("没有听清，请再说一次")
                }
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再说一次"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要录音权限"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，稍后再试"
                    else -> "语音识别暂不可用（$error）"
                }
                this@VoiceInputHelper.listener?.onError(msg)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr.startListening(intent)
        recognizer = sr
    }

    fun stop() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        listener = null
    }
}
