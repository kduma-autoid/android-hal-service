package dev.duma.android.hal.service.plugin

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.contract.PluginServiceWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * External plugin discovery while the service runs: bundle APKs that are installed, disabled,
 * re-enabled or updated after start-up take effect without a restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginRegistryDiscoveryTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val registry = PluginRegistry()
    private val pkg = "com.vendor.bundle"
    private val component = ComponentName(pkg, "$pkg.PluginService")

    private class BundlePlugin(override val version: Int = 1) : HalPlugin {
        override val pluginId = "vendor.thing"
        override fun isSupported() = true
        override fun getCapabilities() = listOf(pluginId)
        override fun getDescriptor() = PluginDescriptor.withFlatLists(
            pluginId = pluginId, name = pluginId, version = version,
            capabilities = getCapabilities(),
            methods = listOf(MethodDescriptor("vendor.thing.ping", "ping", pluginId, exampleParameters = "{}", exampleOutput = "{}"))
        )
        override fun initialize(pluginContext: PluginContext) {}
        override suspend fun execute(method: String, params: String) = CommandResult.Success("""{"v":$version}""")
        override fun setEventCallback(callback: HalPluginEventCallback?) {}
    }

    @Before
    fun setUp() {
        registry.watchPackages(app)
    }

    @After
    fun tearDown() {
        registry.disconnectAll(app)
    }

    private fun install(version: Int = 1) {
        val pm = shadowOf(app.packageManager)
        pm.addOrUpdateService(ServiceInfo().apply {
            packageName = pkg
            name = component.className
            exported = true
            metaData = Bundle().apply { putString("plugin.id", "vendor.thing") }
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        })
        pm.addIntentFilterForService(component, IntentFilter("dev.duma.android.hal.HARDWARE_PLUGIN"))
        shadowOf(app).setComponentNameAndServiceForBindService(component, PluginServiceWrapper(BundlePlugin(version)))
    }

    private fun uninstall() {
        shadowOf(app.packageManager).removeService(component)
    }

    private fun broadcast(action: String, replacing: Boolean = false) {
        app.sendBroadcast(Intent(action, Uri.parse("package:$pkg")).putExtra(Intent.EXTRA_REPLACING, replacing))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun versionOfRegistered(): Int? = registry.getPlugin("vendor.thing")?.version

    @Test
    fun `a bundle installed after start-up is picked up`() {
        registry.discoverExternal(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(versionOfRegistered())

        install()
        broadcast(Intent.ACTION_PACKAGE_ADDED)

        assertEquals(1, versionOfRegistered())
        assertEquals(PluginRegistry.PluginSource.EXTERNAL, registry.getPluginInfo("vendor.thing")?.source)
    }

    @Test
    fun `disabling and re-enabling a bundle drops and restores its plugin`() {
        install()
        registry.discoverExternal(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, versionOfRegistered())

        uninstall() // as `pm disable-user` makes it: the service no longer resolves
        broadcast(Intent.ACTION_PACKAGE_CHANGED)
        assertNull(versionOfRegistered())
        assertEquals(1, shadowOf(app).unboundServiceConnections.size) // let go, not leaked

        install()
        broadcast(Intent.ACTION_PACKAGE_CHANGED)
        assertEquals(1, versionOfRegistered())
    }

    @Test
    fun `an update rebinds to the new version`() {
        install(version = 1)
        registry.discoverExternal(app)
        shadowOf(Looper.getMainLooper()).idle()

        install(version = 2)
        broadcast(Intent.ACTION_PACKAGE_REMOVED, replacing = true)
        broadcast(Intent.ACTION_PACKAGE_ADDED, replacing = true)
        broadcast(Intent.ACTION_PACKAGE_REPLACED, replacing = true)

        assertEquals(2, versionOfRegistered())
    }

    @Test
    fun `an uninstalled bundle is removed`() {
        install()
        registry.discoverExternal(app)
        shadowOf(Looper.getMainLooper()).idle()

        uninstall()
        broadcast(Intent.ACTION_PACKAGE_REMOVED)

        assertNull(versionOfRegistered())
        assertNull(registry.getPluginInfo("vendor.thing"))
    }

    @Test
    fun `rediscovery does not bind a service twice`() {
        install()
        registry.discoverExternal(app)
        registry.discoverExternal(app)
        broadcast(Intent.ACTION_PACKAGE_CHANGED)

        assertEquals(1, shadowOf(app).boundServiceConnections.size)
        assertEquals(1, versionOfRegistered())
    }
}
