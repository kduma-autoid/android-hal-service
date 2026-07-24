package dev.duma.android.hal.service.config

import android.content.Context

/**
 * Development-only server network configuration: the listen (bind) address and port, stored in
 * SharedPreferences. Mirrors the [ExperimentalConfig]/[BroadcastConfig] pattern.
 *
 * Only consulted in development builds (`BuildConfig.DEVELOPMENT`); production builds bind localhost
 * on the fixed [DEFAULT_PORT]. Values are read once at service start, so changes take effect on the
 * next service (re)start.
 */
class ServerConfig(context: Context) {

    companion object {
        private const val PREFS_NAME = "hal_server"
        private const val KEY_BIND_ADDRESS = "bind_address"
        private const val KEY_PORT = "port"

        /** Default development bind address: all interfaces, so the service is reachable over LAN. */
        const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        const val DEFAULT_PORT = 8400
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBindAddress(): String =
        prefs.getString(KEY_BIND_ADDRESS, DEFAULT_BIND_ADDRESS) ?: DEFAULT_BIND_ADDRESS

    fun setBindAddress(address: String) {
        prefs.edit().putString(KEY_BIND_ADDRESS, address).apply()
    }

    fun getPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(port: Int) {
        prefs.edit().putInt(KEY_PORT, port).apply()
    }
}
