package dev.duma.android.hal.service.config

import android.content.Context

/**
 * Server network configuration, stored in SharedPreferences (mirrors the
 * [ExperimentalConfig]/[BroadcastConfig] pattern).
 *
 * Two independent, opt-in toggles:
 * - **Custom port** — when enabled, the server listens on [getPort] instead of [DEFAULT_PORT].
 * - **LAN access as local** — when enabled, the server binds all interfaces ([ALL_INTERFACES])
 *   instead of localhost ([LOCALHOST]), i.e. remote/LAN callers are (for now) treated like local
 *   ones. This is a stopgap until a proper remote-access mode exists (token local/remote flag,
 *   remote-only/local-only/any-source methods).
 *
 * Both default to off, so the resolved config is localhost:8400 — identical to a production build.
 * Currently consulted only in development builds (`BuildConfig.DEVELOPMENT`); the resolved
 * getters are written so the same behaviour can later be extended to stable builds.
 *
 * Values are read once at service start, so changes take effect on the next service (re)start.
 */
class ServerConfig(context: Context) {

    companion object {
        private const val PREFS_NAME = "hal_server"
        private const val KEY_CUSTOM_PORT_ENABLED = "custom_port_enabled"
        private const val KEY_PORT = "port"
        private const val KEY_LAN_ACCESS_AS_LOCAL = "lan_access_as_local"

        const val DEFAULT_PORT = 8400
        const val LOCALHOST = "127.0.0.1"
        const val ALL_INTERFACES = "0.0.0.0"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCustomPortEnabled(): Boolean = prefs.getBoolean(KEY_CUSTOM_PORT_ENABLED, false)

    fun setCustomPortEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CUSTOM_PORT_ENABLED, enabled).apply()
    }

    fun getPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(port: Int) {
        prefs.edit().putInt(KEY_PORT, port).apply()
    }

    fun isLanAccessAsLocal(): Boolean = prefs.getBoolean(KEY_LAN_ACCESS_AS_LOCAL, false)

    fun setLanAccessAsLocal(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LAN_ACCESS_AS_LOCAL, enabled).apply()
    }

    /** Bind address: all interfaces when "LAN access as local" is on, otherwise localhost. */
    fun resolvedBindAddress(): String = if (isLanAccessAsLocal()) ALL_INTERFACES else LOCALHOST

    /** Listen port: the custom port when enabled, otherwise [DEFAULT_PORT]. */
    fun resolvedPort(): Int = if (isCustomPortEnabled()) getPort() else DEFAULT_PORT
}
