package dev.duma.android.hal.plugins.sunmi.nfc

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sunmi.nfc.INfcListener
import com.sunmi.nfc.Nfc
import com.sunmi.peripheralsdk.NfcManager
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.PluginContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal interface NfcSdk {
    fun init(context: Context, callback: (Boolean) -> Unit)
    fun registerListener(onChanged: (List<Nfc>?) -> Unit): NfcListenerRegistration
    fun switchNfc(sn: String)
    fun setWatermarkAlpha(alpha: Int)
}

internal fun interface NfcListenerRegistration {
    fun unregister()
}

internal object SunmiNfcSdk : NfcSdk {
    override fun init(context: Context, callback: (Boolean) -> Unit) = NfcManager.init(context, callback)
    override fun registerListener(onChanged: (List<Nfc>?) -> Unit): NfcListenerRegistration {
        val listener = object : INfcListener.Stub() {
            override fun onNfcListChanged(nfcList: MutableList<Nfc>?) {
                onChanged(nfcList)
            }
        }
        NfcManager.registerNfcListener(listener)
        return NfcListenerRegistration { NfcManager.unregisterNfcListener(listener) }
    }
    override fun switchNfc(sn: String) { NfcManager.switchNfc(sn) }
    override fun setWatermarkAlpha(alpha: Int) { NfcManager.setNfcWaterMarkAlpha(alpha) }
}

internal fun interface SerialDispatcher {
    fun post(block: () -> Unit)
}

internal class MainThreadSerialDispatcher(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : SerialDispatcher {
    override fun post(block: () -> Unit) { handler.post(block) }
}

internal fun interface TrustedHostSerialResolver {
    suspend fun resolve(pluginContext: PluginContext?): String?
}

internal object AndroidTrustedHostSerialResolver : TrustedHostSerialResolver {
    override suspend fun resolve(pluginContext: PluginContext?): String? {
        if (pluginContext?.hasCapability("sunmi.tms.device") == true) {
            try {
                val result = pluginContext.execute("sunmi.tms.device.device_info.getSerialNo", "{}")
                val body = (result as? CommandResult.Success)?.body.orEmpty()
                val serial = JSONObject(body).opt("result") as? String
                validTrustedSerial(serial)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall through to the Android hardware serial.
            }
        }
        return try {
            val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
            validTrustedSerial(serial)
        } catch (_: SecurityException) {
            null
        }
    }

    private fun validTrustedSerial(value: String?): String? {
        val serial = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (serial.equals("null", ignoreCase = true)) return null
        if (serial.equals(Build.UNKNOWN, ignoreCase = true)) return null
        return serial
    }
}
