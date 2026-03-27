package dev.duma.android.hal.transport.aidl

import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import dev.duma.android.hal.contract.EventBus
import dev.duma.android.hal.transport.core.CallerContext
import dev.duma.android.hal.transport.core.CommandHandler
import dev.duma.android.hal.transport.core.CommandTransport
import dev.duma.android.hal.transport.core.EventTransport
import dev.duma.android.hal.transport.core.TransportConfig
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * AIDL transport implementing both command and event channels for native Android clients.
 * Uses Binder IPC with per-uid session tracking and RemoteCallbackList for event delivery.
 * Resolves caller identity (packageName, certHash) from Binder.getCallingUid().
 */
class AidlTransport : CommandTransport, EventTransport {

    override val transportId = "aidl"
    override val displayName = "AIDL"
    override val isToggleable = false
    override var isEnabled = true
    override val isRunning: Boolean get() = running

    private var running = false
    private var handler: CommandHandler? = null
    private var config: TransportConfig? = null

    private val sessionTokens = ConcurrentHashMap<Int, String>()
    private val sessionSubscriptions = ConcurrentHashMap<Int, CopyOnWriteArraySet<String>>()
    private val callbackList = RemoteCallbackList<IHalCallback>()
    private val callbackUids = ConcurrentHashMap<IBinder, Int>()

    val binder: IBinder = object : IHalService.Stub() {
        override fun requestToken(jsonRequest: String): String {
            val callerContext = buildCallerContext()
            return runBlocking {
                handler?.requestToken(jsonRequest, callerContext) ?: errorJson("service_unavailable")
            }
        }

        override fun authenticate(token: String): Boolean {
            val uid = Binder.getCallingUid()
            sessionTokens[uid] = token
            return true
        }

        override fun execute(method: String, jsonParams: String): String {
            val uid = Binder.getCallingUid()
            val token = sessionTokens[uid] ?: return errorJson("unauthorized")
            val callerContext = buildCallerContext()
            return runBlocking {
                handler?.execute(token, method, jsonParams, callerContext) ?: errorJson("service_unavailable")
            }
        }

        override fun getStatus(): String {
            return handler?.getStatus() ?: errorJson("service_unavailable")
        }

        override fun registerCallback(callback: IHalCallback) {
            val uid = Binder.getCallingUid()
            callbackList.register(callback)
            callbackUids[callback.asBinder()] = uid
        }

        override fun unregisterCallback(callback: IHalCallback) {
            callbackList.unregister(callback)
            callbackUids.remove(callback.asBinder())
        }

        override fun subscribe(jsonEvents: String): String {
            val uid = Binder.getCallingUid()
            val token = sessionTokens[uid] ?: return errorJson("unauthorized")
            val events = jsonEvents.trim('[', ']', '"').split("\",\"").map { it.trim() }
            sessionSubscriptions.getOrPut(uid) { CopyOnWriteArraySet() }.addAll(events)
            val callerContext = buildCallerContext()
            return runBlocking {
                handler?.subscribe(token, jsonEvents, callerContext) ?: errorJson("service_unavailable")
            }
        }

        override fun unsubscribe(jsonEvents: String): String {
            val uid = Binder.getCallingUid()
            val token = sessionTokens[uid] ?: return errorJson("unauthorized")
            val events = jsonEvents.trim('[', ']', '"').split("\",\"").map { it.trim() }
            sessionSubscriptions[uid]?.removeAll(events.toSet())
            val callerContext = buildCallerContext()
            return runBlocking {
                handler?.unsubscribe(token, jsonEvents, callerContext) ?: errorJson("service_unavailable")
            }
        }
    }

    override fun start(handler: CommandHandler, config: TransportConfig) {
        this.handler = handler
        this.config = config
        running = true
    }

    override fun start(config: TransportConfig) {
        // Event transport start — already handled by command start
    }

    override fun stop() {
        running = false
        sessionTokens.clear()
        sessionSubscriptions.clear()
        handler = null
    }

    override fun pushEvent(eventName: String, jsonData: String) {
        val count = callbackList.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    val callback = callbackList.getBroadcastItem(i)
                    val uid = callbackUids[callback.asBinder()] ?: continue
                    val subs = sessionSubscriptions[uid] ?: continue
                    if (subs.any { EventBus.matchesPattern(it, eventName) }) {
                        callback.onEvent(eventName, jsonData)
                    }
                } catch (_: Exception) { }
            }
        } finally {
            callbackList.finishBroadcast()
        }
    }

    private fun buildCallerContext(): CallerContext {
        val uid = Binder.getCallingUid()
        val pm = config?.context?.packageManager
        val packageName = pm?.getPackagesForUid(uid)?.firstOrNull()
        val certHash = if (packageName != null && pm != null) {
            try {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val sig = info.signingInfo?.apkContentsSigners?.firstOrNull()
                if (sig != null) {
                    MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                        .joinToString(":") { "%02X".format(it) }
                } else null
            } catch (_: Exception) { null }
        } else null

        return CallerContext(
            transport = "aidl",
            packageName = packageName,
            certHash = certHash,
            callingUid = uid
        )
    }

    private fun errorJson(code: String): String {
        return """{"error":"$code","message":"$code"}"""
    }
}
