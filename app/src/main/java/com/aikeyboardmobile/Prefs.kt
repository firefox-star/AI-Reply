package com.aikeyboardmobile

import android.content.Context

/**
 * Tiny SharedPreferences wrapper. The API key is stored ONLY on the user's phone —
 * nothing is ever hardcoded and nothing is sent anywhere except to the chosen AI provider.
 */
object Prefs {
    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    private const val NAME = "aireply"

    fun load(ctx: Context): Config {
        val sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val d = Presets.all[0]
        return Config(
            baseUrl = sp.getString("baseUrl", d.url) ?: d.url,
            apiKey = sp.getString("apiKey", "") ?: "",
            model = sp.getString("model", d.model) ?: d.model
        )
    }

    fun save(ctx: Context, c: Config) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("baseUrl", c.baseUrl)
            .putString("apiKey", c.apiKey)
            .putString("model", c.model)
            .apply()
    }
}
