package dev.duma.android.hal.plugins.sunmi.printerx.sdk

import android.content.Context
import com.sunmi.printerx.PrinterSdk
import com.sunmi.printerx.PrinterSdk.PrinterListen
import dev.duma.android.hal.contract.CommandResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Singleton managing PrinterSdk lifecycle with reference counting.
 * All PrinterX plugins in the same process share one connection.
 *
 * Usage:
 * - Call [acquire] in plugin initialize(), [release] on shutdown.
 * - Only the manager plugin should register a discovery listener via [addDiscoveryListener].
 * - All plugins can call [getPrinter], [getDefaultPrinter], [getAllPrinterIds].
 */
object SharedPrinterManager {

    private var defaultPrinter: PrinterSdk.Printer? = null
    private val printers = mutableMapOf<String, PrinterSdk.Printer>()
    private var refCount = 0
    private val discoveryListeners = mutableListOf<DiscoveryListener>()

    fun interface DiscoveryListener {
        fun onPrintersChanged(defaultPrinterId: String?, allPrinterIds: List<String>)
    }

    private val printerListen = object : PrinterListen {
        override fun onDefPrinter(printer: PrinterSdk.Printer) {
            defaultPrinter = printer
            printers[printer.toString()] = printer
            notifyListeners()
        }

        override fun onPrinters(printerList: List<PrinterSdk.Printer>) {
            printers.clear()
            printerList.forEach { printers[it.toString()] = it }
            notifyListeners()
        }
    }

    @Synchronized
    fun acquire(context: Context) {
        refCount++
        if (refCount == 1) {
            PrinterSdk.getInstance().getPrinter(context, printerListen)
        }
    }

    @Synchronized
    fun release() {
        refCount--
        if (refCount <= 0) {
            refCount = 0
            PrinterSdk.getInstance().destroy()
            defaultPrinter = null
            printers.clear()
        }
    }

    fun addDiscoveryListener(listener: DiscoveryListener) {
        synchronized(discoveryListeners) {
            discoveryListeners.add(listener)
        }
    }

    fun removeDiscoveryListener(listener: DiscoveryListener) {
        synchronized(discoveryListeners) {
            discoveryListeners.remove(listener)
        }
    }

    fun getPrinter(printerId: String?): PrinterSdk.Printer? {
        return if (printerId.isNullOrBlank()) defaultPrinter
        else printers[printerId]
    }

    fun getDefaultPrinter(): PrinterSdk.Printer? = defaultPrinter

    fun getAllPrinterIds(): List<String> = printers.keys.toList()

    fun buildGetPrintersResponse(): CommandResult {
        val arr = JSONArray().apply {
            getAllPrinterIds().forEach { put(it) }
        }
        return CommandResult.Success(
            JSONObject()
                .put("printers", arr)
                .put("defaultPrinter", getDefaultPrinter()?.toString())
                .toString()
        )
    }

    private fun notifyListeners() {
        val defId = defaultPrinter?.toString()
        val allIds = getAllPrinterIds()
        synchronized(discoveryListeners) {
            discoveryListeners.forEach { it.onPrintersChanged(defId, allIds) }
        }
    }
}
