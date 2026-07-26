package dev.duma.android.hal.service.service

import android.app.Notification
import android.app.NotificationChannel
import dev.duma.android.hal.service.R
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.room.Room
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import dev.duma.android.hal.contract.EventBus
import dev.duma.android.hal.service.auth.AuthManager
import dev.duma.android.hal.service.auth.ServiceKeyVerifier
import dev.duma.android.hal.service.auth.DeviceKeyManager
import dev.duma.android.hal.service.auth.GrantDecision
import dev.duma.android.hal.service.auth.GrantOverlayDialog
import dev.duma.android.hal.service.auth.GrantPermissionActivity
import dev.duma.android.hal.service.auth.TokenDatabase
import dev.duma.android.hal.service.auth.TokenDao
import dev.duma.android.hal.service.auth.TokenManager
import dev.duma.android.hal.service.BuildConfig
import dev.duma.android.hal.service.config.BroadcastConfig
import dev.duma.android.hal.service.config.ExperimentalConfig
import dev.duma.android.hal.service.config.InterfacePreferenceConfig
import dev.duma.android.hal.service.config.ServerConfig
import dev.duma.android.hal.service.core.ServiceCommandHandler
import dev.duma.android.hal.service.core.TransportBootstrap
import dev.duma.android.hal.service.plugin.PluginRegistry
import dev.duma.android.hal.transport.core.TransportConfig
import dev.duma.android.hal.transport.core.TransportRegistry
import dev.duma.android.hal.transport.ktor.core.KtorServerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.provider.Settings

/**
 * Main foreground service that bootstraps and manages the HAL system.
 * Initializes auth, plugins, transports, and the Ktor server in the correct order
 * per spec/ktor-coordination.md. Bridges EventBus to TransportRegistry for event delivery.
 */
class HalService : Service() {

    companion object {
        private const val TAG = "HalService"
        private const val CHANNEL_ID = "hal_service"
        private const val NOTIFICATION_ID = 1
        private const val GRANT_CHANNEL_ID = "hal_grant_requests"
        private const val GRANT_NOTIFICATION_ID = 2
        const val PORT = 8400

        var isServiceRunning: Boolean = false
            private set
        var transportRegistry: TransportRegistry? = null
            private set
        var pluginRegistry: PluginRegistry? = null
            private set
        var tokenDao: TokenDao? = null
            private set
        var broadcastConfig: BroadcastConfig? = null
            private set
        var experimentalConfig: ExperimentalConfig? = null
            private set
        var interfacePreferenceConfig: InterfacePreferenceConfig? = null
            private set
        var serverConfig: ServerConfig? = null
            private set

        /** The address/port the server was actually bound to at startup (for the dashboard). */
        var boundHost: String = "127.0.0.1"
            private set
        var boundPort: Int = PORT
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var transportRegistry: TransportRegistry
    private lateinit var pluginRegistry: PluginRegistry
    private lateinit var ktorServerManager: KtorServerManager
    private var aidlBinder: IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HAL Service starting...")

        // 1. Room database
        val db = Room.databaseBuilder(applicationContext, TokenDatabase::class.java, "hal-tokens")
            .build()

        // 2. Auth
        val tokenManager = TokenManager(db.tokenDao())
        val jwkJson = resources.openRawResource(R.raw.developer_portal_public_key)
            .bufferedReader().use { it.readText() }
        val verifier = ServiceKeyVerifier(RSASSAVerifier(RSAKey.parse(jwkJson)))
        val deviceKeyManager = DeviceKeyManager(
            getSharedPreferences("hal_device_key", MODE_PRIVATE)
        )
        deviceKeyManager.getOrCreateSecret()
        val serviceContext = this
        val superPrefs = getSharedPreferences("hal_super", MODE_PRIVATE)
        val authManager = AuthManager(
            tokenManager, verifier,
            deviceKeyVerifier = {
                if (!deviceKeyManager.isEnabled()) null
                else ServiceKeyVerifier(MACVerifier(deviceKeyManager.getOrCreateSecret()), requiredIssuer = "device-key")
            },
            showGrantDialog = { callerContext, request ->
            Log.i(TAG, "Grant dialog requested for clientId=${request.clientId}")
            val deferred = CompletableDeferred<GrantDecision>()

            if (Settings.canDrawOverlays(serviceContext)) {
                // Primary: overlay dialog over any app
                Log.i(TAG, "Showing overlay dialog")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    GrantOverlayDialog.show(
                        serviceContext, request.clientId,
                        callerContext.packageName, callerContext.origin,
                        request.requestedPermissions, deferred
                    )
                }
            } else {
                // Fallback: notification with PendingIntent to Activity
                Log.i(TAG, "No overlay permission, using notification fallback")
                GrantPermissionActivity.pendingResult = deferred
                val activityIntent = Intent(serviceContext, GrantPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    putExtra(GrantPermissionActivity.EXTRA_CLIENT_ID, request.clientId)
                    putExtra(GrantPermissionActivity.EXTRA_PACKAGE_NAME, callerContext.packageName)
                    putExtra(GrantPermissionActivity.EXTRA_ORIGIN, callerContext.origin)
                    putExtra(GrantPermissionActivity.EXTRA_REQUESTED_PERMISSIONS, request.requestedPermissions?.toTypedArray())
                }
                showGrantNotification(activityIntent, request.clientId)
            }

            kotlinx.coroutines.withTimeout(60_000) { deferred.await() }
        },
            isSuperViaDialogAllowed = { superPrefs.getBoolean("allow_super_via_dialog", false) }
        )

        // 3. EventBus
        val eventBus = EventBus()

        // 4. PluginRegistry
        pluginRegistry = PluginRegistry()

        // 5. Register vendor-specific plugins (available only in sunmi flavor)
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.printer.SunmiPrinterPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.scanner.SunmiScannerPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.statuslight.SunmiStatusLightPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.nfc.SunmiNfcPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.card.SunmiCardPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.subscreen.SunmiSubScreenPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.docker.SunmiDockerPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.tms.device.SunmiTmsDevicePlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.tms.led.SunmiTmsLedPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.tms.software.SunmiTmsSoftwarePlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.tms.system.SunmiTmsSystemPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.tms.network.SunmiTmsNetworkPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.tms.kiosk.SunmiTmsKioskPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.printerx.manager.SunmiPrinterXManagerPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.printerx.printer.SunmiPrinterXPrinterPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.printerx.drawer.SunmiPrinterXDrawerPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.printerx.lcd.SunmiPrinterXLcdPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.rfid.SunmiRfidPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.scanner.inner.SunmiInnerScannerPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.scanner.camera.SunmiCameraScannerPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.sunmi.scanner.external.SunmiExternalScannerPlugin")

        // 6. Discover external plugins (standalone bundle APKs)
        pluginRegistry.discoverExternal(this)

        // 7. Register generic plugins (always available)
        // Interface definers (register interface contracts; providers opt in via bindings).
        // `printer`/`scanner` replace the former GenericPrinterPlugin/GenericScannerPlugin wrappers.
        tryRegisterPlugin("dev.duma.android.hal.plugins.generic.LightInterface")
        tryRegisterPlugin("dev.duma.android.hal.plugins.generic.PrinterInterface")
        tryRegisterPlugin("dev.duma.android.hal.plugins.generic.BarcodeScannerInterface")
        // Hardware-free demo interface + two providers (always available, for testing the interface layer).
        tryRegisterPlugin("dev.duma.android.hal.plugins.generic.DemoInterface")
        tryRegisterPlugin("dev.duma.android.hal.plugins.generic.DemoAlphaPlugin")
        tryRegisterPlugin("dev.duma.android.hal.plugins.generic.DemoBetaPlugin")

        // 8. Initialize all plugins (PluginContext per plugin)
        pluginRegistry.initializeAll(applicationContext, eventBus)

        // 9. KtorServerManager
        ktorServerManager = KtorServerManager()

        // 10. ExperimentalConfig + ServerConfig + TransportConfig
        val experimentalConfig = ExperimentalConfig(applicationContext)
        val broadcastConfig = BroadcastConfig(applicationContext)
        val interfacePreferenceConfig = InterfacePreferenceConfig(applicationContext)
        pluginRegistry.interfacePreferenceConfig = interfacePreferenceConfig
        val serverConfig = ServerConfig(applicationContext)

        // Production binds localhost on the fixed port. Development builds default to the same
        // (localhost:8400) but honour the opt-in ServerConfig toggles (custom port, LAN access).
        val bindHost = if (BuildConfig.DEVELOPMENT) serverConfig.resolvedBindAddress() else "127.0.0.1"
        val bindPort = if (BuildConfig.DEVELOPMENT) serverConfig.resolvedPort() else PORT

        val config = TransportConfig(
            port = bindPort,
            host = bindHost,
            context = applicationContext,
            enabledBroadcastEvents = broadcastConfig.getEnabledEvents(),
            broadcastEventFilter = { broadcastConfig.isEventEnabled(it) },
            ktorServerManager = ktorServerManager
        )

        // 11. TransportBootstrap -> TransportRegistry
        transportRegistry = TransportRegistry()
        val bootstrap = TransportBootstrap()
        bootstrap.registerTransports(transportRegistry)

        // 12. ServiceCommandHandler
        val pkgInfo = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val commandHandler = ServiceCommandHandler(
            authManager = authManager,
            tokenManager = tokenManager,
            pluginRegistry = pluginRegistry,
            transportRegistry = transportRegistry,
            experimentalConfig = experimentalConfig,
            interfacePreferenceConfig = interfacePreferenceConfig,
            versionName = pkgInfo?.versionName,
            versionCode = pkgInfo?.let { PackageInfoCompat.getLongVersionCode(it).toInt() }
        )

        // 13. Start all transports — each registers modules in KtorServerManager
        transportRegistry.startAll(commandHandler, config)

        // 14. Start Ktor server if any Ktor transports registered
        if (ktorServerManager.hasModules) {
            ktorServerManager.start(bindPort, bindHost)
            Log.i(TAG, "Ktor server started on $bindHost:$bindPort")
        } else {
            Log.i(TAG, "No Ktor transports registered, skipping server start")
        }

        // 15. Bridge: EventBus.events -> TransportRegistry.pushEvent
        scope.launch {
            eventBus.events.collect { envelope ->
                transportRegistry.pushEvent(envelope.eventName, envelope.jsonData, envelope.sourcePluginId)
            }
        }

        // 16. Store AIDL binder for onBind
        try {
            val aidlTransportClass = Class.forName("dev.duma.android.hal.transport.aidl.AidlTransport")
            val transports = transportRegistry.getCommandTransports()
            val aidlTransport = transports.find { it.javaClass == aidlTransportClass }
            if (aidlTransport != null) {
                val binderField = aidlTransportClass.getDeclaredField("binder")
                binderField.isAccessible = true
                aidlBinder = binderField.get(aidlTransport) as? IBinder
            }
        } catch (_: Exception) {
            Log.d(TAG, "AIDL transport not available")
        }

        // 17. Clean expired tokens
        scope.launch(Dispatchers.IO) {
            tokenManager.cleanExpired()
        }

        // 18. Expose state for DashboardActivity
        Companion.transportRegistry = transportRegistry
        Companion.pluginRegistry = pluginRegistry
        Companion.tokenDao = db.tokenDao()
        Companion.broadcastConfig = broadcastConfig
        Companion.experimentalConfig = experimentalConfig
        Companion.interfacePreferenceConfig = interfacePreferenceConfig
        Companion.serverConfig = serverConfig
        Companion.boundHost = bindHost
        Companion.boundPort = bindPort
        Companion.isServiceRunning = true

        // 19. Foreground notification
        startForegroundNotification()

        Log.i(TAG, "HAL Service started successfully")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return aidlBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "HAL Service stopping...")
        Companion.isServiceRunning = false
        Companion.transportRegistry = null
        Companion.pluginRegistry = null
        Companion.tokenDao = null
        Companion.broadcastConfig = null
        Companion.experimentalConfig = null
        Companion.interfacePreferenceConfig = null
        Companion.serverConfig = null
        transportRegistry.stopAll()
        ktorServerManager.stop()
        pluginRegistry.disconnectAll(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HAL Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HAL Service is running"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("HAL Service")
            .setContentText("Hardware Abstraction Layer is running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // Notification.Builder / setPriority are used directly (pre-O compat); the channel importance
    // drives priority on O+. Suppress the framework deprecation warnings for this compat path.
    @Suppress("DEPRECATION")
    private fun showGrantNotification(activityIntent: Intent, clientId: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val grantChannel = NotificationChannel(
                GRANT_CHANNEL_ID,
                "Permission Requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for hardware access permission requests"
            }
            notificationManager.createNotificationChannel(grantChannel)
        }

        val pendingIntent = android.app.PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(),
            activityIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, GRANT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("Permission Request")
            .setContentText("\"$clientId\" is requesting hardware access. Tap to respond.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        notificationManager.notify(GRANT_NOTIFICATION_ID, notification)
    }

    private fun tryRegisterPlugin(className: String) {
        try {
            val clazz = Class.forName(className)
            val plugin = try {
                // Try Context constructor first — some plugins need it for hardware SDK access
                clazz.getDeclaredConstructor(android.content.Context::class.java)
                    .newInstance(applicationContext) as dev.duma.android.hal.contract.HalPlugin
            } catch (_: NoSuchMethodException) {
                // Fall back to no-arg constructor
                clazz.getDeclaredConstructor().newInstance() as dev.duma.android.hal.contract.HalPlugin
            }
            pluginRegistry.registerBuiltIn(plugin)
        } catch (_: ClassNotFoundException) {
            Log.d(TAG, "Plugin not available: $className")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register plugin: $className", e)
        }
    }
}
