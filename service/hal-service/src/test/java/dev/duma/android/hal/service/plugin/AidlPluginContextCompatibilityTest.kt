package dev.duma.android.hal.service.plugin

import android.app.Application
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import androidx.test.core.app.ApplicationProvider
import dev.duma.android.hal.contract.AidlPluginAdapter
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.IHardwarePlugin
import dev.duma.android.hal.contract.IPluginContext
import dev.duma.android.hal.contract.IPluginEventCallback
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.RemotePluginContext
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AidlPluginContextCompatibilityTest {

    private class FakeHardwarePlugin : IHardwarePlugin.Stub() {
        var registered: IPluginEventCallback? = null
        var unregistered = 0
        var context: IPluginContext? = null

        override fun getPluginId() = "test.plugin"
        override fun getVersion() = 1
        override fun isSupported() = true
        override fun getCapabilities() = listOf("test.plugin")
        override fun execute(method: String, jsonParams: String) = CommandResult.Success("{}")
        override fun getDescriptorJson() = """{"pluginId":"test.plugin","name":"test","version":1,"capabilities":["test.plugin"],"groups":[]}"""
        override fun registerEventCallback(callback: IPluginEventCallback) { registered = callback }
        override fun unregisterEventCallback(callback: IPluginEventCallback) {
            if (registered === callback) registered = null
            unregistered++
        }
        override fun initialize(context: IPluginContext) { this.context = context }
    }

    @Test
    fun `adapter forwards availability and unregisters late event callbacks`() {
        val hardware = FakeHardwarePlugin()
        val adapter = AidlPluginAdapter(hardware)
        var events = 0
        val callback = object : HalPluginEventCallback {
            override fun onEvent(eventName: String, jsonData: String) { events++ }
            override fun onError(deviceType: String, code: Int, message: String) = Unit
        }

        adapter.setEventCallback(callback)
        val oldCallback = hardware.registered
        assertTrue(oldCallback != null)
        oldCallback!!.onEvent("before", "{}")
        assertEquals(1, events)

        adapter.setEventCallback(null)
        assertEquals(1, hardware.unregistered)
        oldCallback.onEvent("late", "{}")
        assertEquals(1, events)

        var available: Boolean? = null
        val context = object : PluginContext {
            override val applicationContext: Application = ApplicationProvider.getApplicationContext()
            override suspend fun execute(method: String, params: String) = CommandResult.Success("{}")
            override fun getAvailableCapabilities() = emptyList<String>()
            override fun hasCapability(capability: String) = false
            override fun emitEvent(eventName: String, jsonData: String) = Unit
            override fun onEvent(pattern: String, callback: (String, String) -> Unit) = Unit
            override fun setPluginAvailable(value: Boolean) { available = value }
        }
        adapter.initialize(context)
        hardware.context!!.setPluginAvailable(false)
        assertEquals(false, available)

        adapter.setEventCallback(callback)
        adapter.dispose()
        assertEquals(2, hardware.unregistered)
    }

    /** Binder behaving like a pre-setPluginAvailable host: old transactions work elsewhere, new one is absent. */
    private class LegacyContextBinder : Binder() {
        val transactionCodes = mutableListOf<Int>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            transactionCodes += code
            val response = reply ?: return false
            when (code) {
                // IPluginContext's pre-availability methods. These deliberately implement the
                // old wire contract instead of relying on RemoteException from a dead fake.
                1 -> {
                    response.writeNoException()
                    response.writeInt(1) // non-null CommandResult
                    CommandResult.Success("legacy").writeToParcel(response, 0)
                    return true
                }
                2 -> {
                    response.writeNoException()
                    response.writeStringList(listOf("legacy.capability"))
                    return true
                }
                3 -> {
                    response.writeNoException()
                    response.writeInt(1)
                    return true
                }
                // New transaction: an old host has no implementation and returns false.
                4 -> return false
                else -> return false
            }
        }
    }

    @Test
    fun `remote context tolerates a real old Binder proxy and availability is appended`() = runBlocking {
        val oldBinder = LegacyContextBinder()
        val proxy = IPluginContext.Stub.asInterface(oldBinder as IBinder)
        val remote = RemotePluginContext(proxy, ApplicationProvider.getApplicationContext())

        val result = remote.execute("test.ping", "{}")
        assertEquals(CommandResult.Success("legacy"), result)
        assertEquals(listOf("legacy.capability"), remote.getAvailableCapabilities())
        assertTrue(remote.hasCapability("test.ping"))
        remote.setPluginAvailable(true)

        // Generated AIDL transaction ids are allocated in declaration order. The new method is
        // last, preserving execute/getAvailableCapabilities/hasCapability for old peers.
        assertEquals(listOf(1, 2, 3, 4), oldBinder.transactionCodes)
    }

    @Test
    fun `dead availability transaction is a no-op`() {
        val deadHost = object : IPluginContext.Stub() {
            override fun execute(method: String, jsonParams: String) = throw RemoteException("dead")
            override fun getAvailableCapabilities() = throw RemoteException("dead")
            override fun hasCapability(capability: String) = throw RemoteException("dead")
            override fun setPluginAvailable(available: Boolean): Unit = throw RemoteException("dead")
        }
        val remote = RemotePluginContext(deadHost, ApplicationProvider.getApplicationContext())
        remote.setPluginAvailable(true)
        assertFalse(remote.hasCapability("anything"))
    }
}
