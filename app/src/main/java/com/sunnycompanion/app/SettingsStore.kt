package com.sunnycompanion.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

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

/**
 * 内置"一键授权"数据（assets/builtin_config.json，由 CI 用仓库 Secrets 生成，
 * 仓库源码与本地均不含明文口令/密钥）：
 * { "passcode": "xxx", "config": { ...AppConfig 同结构字段... } }
 */
private data class BuiltinPayload(
    val passcode: String = "",
    val config: JsonObject? = null
)

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val sp = context.getSharedPreferences("sunny_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val builtin: BuiltinPayload? by lazy { readBuiltin() }

    /** 是否需要口令解锁：内置了 passcode 且尚未解锁。 */
    fun isLocked(): Boolean {
        val pass = builtin?.passcode
        return !pass.isNullOrBlank() && !sp.getBoolean("unlocked", false)
    }

    fun load(): AppConfig {
        // 1) 用户显式保存过的配置优先
        val json = sp.getString("config", null)
        if (!json.isNullOrBlank()) {
            return try {
                gson.fromJson(json, AppConfig::class.java)
            } catch (e: Exception) {
                AppConfig()
            }
        }
        // 2) 未锁定（公开版/已解锁）→ 尝试内置一键授权配置
        if (!isLocked()) {
            val cfg = builtin?.config
            if (cfg != null) return configFromJson(cfg)
        }
        return AppConfig()
    }

    private fun configFromJson(obj: JsonObject): AppConfig {
        return AppConfig(
            difyEndpoint = obj.get("difyEndpoint")?.takeIf { !it.isJsonNull }?.asString
                ?: "https://api.dify.ai/v1",
            difyApiKey = obj.get("difyApiKey")?.takeIf { !it.isJsonNull }?.asString ?: "",
            ttsProvider = obj.get("ttsProvider")?.takeIf { !it.isJsonNull }?.asString ?: "minimax",
            ttsApiKey = obj.get("ttsApiKey")?.takeIf { !it.isJsonNull }?.asString ?: "",
            voiceBoy = obj.get("voiceBoy")?.takeIf { !it.isJsonNull }?.asString ?: "male-qn-qingse",
            voiceGirl = obj.get("voiceGirl")?.takeIf { !it.isJsonNull }?.asString ?: "female-shaonv",
            userName = obj.get("userName")?.takeIf { !it.isJsonNull }?.asString ?: "",
            autoSpeak = obj.get("autoSpeak")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
        )
    }

    /**
     * 校验口令；正确则一键写入内置配置并标记已解锁。
     * @return true=解锁成功；false=口令错误/无内置
     */
    fun tryUnlock(passcode: String): Boolean {
        val expect = builtin?.passcode ?: return false
        if (expect.isBlank() || passcode.trim() != expect) return false
        val cfg = builtin?.config
        if (cfg != null) {
            sp.edit()
                .putBoolean("unlocked", true)
                .putString("config", gson.toJson(configFromJson(cfg)))
                .apply()
        } else {
            sp.edit().putBoolean("unlocked", true).apply()
        }
        return true
    }

    fun save(config: AppConfig) {
        sp.edit().putString("config", gson.toJson(config)).apply()
    }

    fun reset() {
        sp.edit().remove("config").remove("unlocked").apply()
    }

    private fun readBuiltin(): BuiltinPayload? {
        return try {
            val input = appContext.assets.open("builtin_config.json")
            val text = input.bufferedReader().use { it.readText() }
            val root = JsonParser.parseString(text).asJsonObject
            val pass = root.get("passcode")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val cfg = root.get("config")?.takeIf { !it.isJsonNull }?.asJsonObject
            BuiltinPayload(pass, cfg)
        } catch (e: Exception) {
            null // 公开版/本地构建无此文件
        }
    }
}
