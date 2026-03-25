package dev.duma.android.hal.service.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.room.Room
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import dev.duma.android.hal.contract.EventBus
import dev.duma.android.hal.service.auth.AuthManager
import dev.duma.android.hal.service.auth.DeveloperKeyVerifier
import dev.duma.android.hal.service.auth.GrantDecision
import dev.duma.android.hal.service.auth.GrantPermissionActivity
import dev.duma.android.hal.service.auth.TokenDatabase
import dev.duma.android.hal.service.auth.TokenManager
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
        const val PORT = 8400
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
        // TODO: Load real public key from resources — using generated key for now
        val testKey = RSAKeyGenerator(2048).generate()
        val verifier = DeveloperKeyVerifier(testKey.toPublicJWK())
        val authManager = AuthManager(tokenManager, verifier) { callerContext, request ->
            val deferred = CompletableDeferred<GrantDecision>()
            GrantPermissionActivity.pendingResult = deferred
            val intent = Intent(this, GrantPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(GrantPermissionActivity.EXTRA_CLIENT_ID, request.clientId)
                putExtra(GrantPermissionActivity.EXTRA_PACKAGE_NAME, callerContext.packageName)
                putExtra(GrantPermissionActivity.EXTRA_ORIGIN, callerContext.origin)
            }
            startActivity(intent)
            deferred.await()
        }

        // 3. EventBus
        val eventBus = EventBus()

        // 4. PluginRegistry
        pluginRegistry = PluginRegistry()

        // 5-7. Register plugins (vendor-specific via reflection, then generic)
        // Plugins will be registered in Stage 5 — currently no plugins compiled in

        // 8. Initialize all plugins
        pluginRegistry.initializeAll(applicationContext, eventBus)

        // 9. KtorServerManager
        ktorServerManager = KtorServerManager()

        // 10. TransportConfig
        val config = TransportConfig(
            port = PORT,
            context = applicationContext,
            enabledBroadcastEvents = loadBroadcastConfig(),
            ktorServerManager = ktorServerManager
        )

        // 11. TransportBootstrap -> TransportRegistry
        transportRegistry = TransportRegistry()
        val bootstrap = TransportBootstrap()
        bootstrap.registerTransports(transportRegistry)

        // 12. ServiceCommandHandler
        val commandHandler = ServiceCommandHandler(
            authManager = authManager,
            tokenManager = tokenManager,
            pluginRegistry = pluginRegistry,
            transportRegistry = transportRegistry
        )

        // 13. Start all transports — each registers modules in KtorServerManager
        transportRegistry.startAll(commandHandler, config)

        // 14. Start Ktor server if any Ktor transports registered
        if (ktorServerManager.hasModules) {
            ktorServerManager.start(PORT)
            Log.i(TAG, "Ktor server started on port $PORT")
        } else {
            Log.i(TAG, "No Ktor transports registered, skipping server start")
        }

        // 15. Bridge: EventBus.events -> TransportRegistry.pushEvent
        scope.launch {
            eventBus.events.collect { envelope ->
                transportRegistry.pushEvent(envelope.eventName, envelope.jsonData)
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

        // 18. Foreground notification
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

    private fun loadBroadcastConfig(): Set<String> {
        val prefs = getSharedPreferences("broadcast_config", MODE_PRIVATE)
        return prefs.getStringSet("enabled_events", emptySet()) ?: emptySet()
    }
}
