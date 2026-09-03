package com.sunnycompanion.app

import android.content.Context
import com.google.gson.Gson

/**
 * App 全局配置（存储在本地 SharedPreferences）。
 * demo 阶段密钥直接保存在本机；公开分发前请改为服务端代理。
 */
data class AppConfig(
    val difyEndpoint: String = "https://api.dify.ai/v1",
    val difyApiKey: String = "",
    val ttsProvider: String = "minimax",        // minimax | siliconflow
    val ttsApiKey: String = "",
    val voiceBoy: String = "male-qn-qingse",    // 阳光男声音色 ID（可在控制台试听替换）
    val voiceGirl: String = "female-shaonv",    // 可爱女声音色 ID
    val userName: String = "",
    val autoSpeak: Boolean = true               // 收到回复后自动朗读
)

class SettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("sunny_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): AppConfig {
        val json = sp.getString("config", null) ?: return AppConfig()
        return try {
            gson.fromJson(json, AppConfig::class.java)
        } catch (e: Exception) {
            AppConfig()
        }
    }

    fun save(config: AppConfig) {
        sp.edit().putString("config", gson.toJson(config)).apply()
    }
}
