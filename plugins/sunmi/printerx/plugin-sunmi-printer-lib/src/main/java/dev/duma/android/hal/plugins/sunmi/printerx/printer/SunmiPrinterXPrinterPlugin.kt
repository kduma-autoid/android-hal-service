package dev.duma.android.hal.plugins.sunmi.printerx.printer

import android.content.Context
import androidx.core.content.ContextCompat
import dev.duma.android.hal.contract.DescriptorGroup
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.PluginDescriptor
import dev.duma.android.hal.contract.stripExperimental
import dev.duma.android.hal.plugins.sunmi.printerx.printer.handler.*
import dev.duma.android.hal.plugins.sunmi.printerx.printer.receiver.PrinterBroadcastReceiver
import dev.duma.android.hal.plugins.sunmi.printerx.sdk.BasePrinterXPlugin
import org.json.JSONObject

class SunmiPrinterXPrinterPlugin(context: Context? = null) : BasePrinterXPlugin(context) {

    override val pluginId = "sunmi.printerx.printer"
    override val version = 1

    private val queryHandler by lazy { QueryApiHandler() }
    private val commandHandler by lazy { CommandApiHandler() }
    private val lineHandler by lazy { LineApiHandler() }
    private val canvasHandler by lazy { CanvasApiHandler() }
    private val fileHandler by lazy { FileApiHandler() }
    private val broadcastReceiver by lazy { PrinterBroadcastReceiver(::emitEvent) }

    override fun initialize(pluginContext: PluginContext) {
        super.initialize(pluginContext)
        this.context?.let { ctx ->
            ContextCompat.registerReceiver(ctx, broadcastReceiver, PrinterBroadcastReceiver.buildIntentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    override fun getCapabilities() = listOf("sunmi.printerx.printer")

    override fun getDescriptor() = fullDescriptor().let {
        if (BuildConfig.WITH_EXPERIMENTAL) it else it.stripExperimental()
    }

    private fun fullDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi: Printer",
        version = version,
        capabilities = getCapabilities(),
        groups = buildGroups()
    )

    override suspend fun handleExecute(method: String, params: String, json: JSONObject): CommandResult {
        val module = method.removePrefix("sunmi.printerx.printer.").substringBefore(".")
        return when (module) {
            "query" -> guardedExecute { queryHandler.handle(method, json) }
            "command" -> guardedExecute { commandHandler.handle(method, json) }
            "line" -> {
                // printTrans skips mutex (long-running async)
                if (method.endsWith(".printTrans")) lineHandler.handle(method, json)
                else guardedExecute { lineHandler.handle(method, json) }
            }
            "canvas" -> {
                // printCanvas skips mutex
                if (method.endsWith(".printCanvas")) canvasHandler.handle(method, json)
                else guardedExecute { canvasHandler.handle(method, json) }
            }
            "file" -> fileHandler.handle(method, json) // always async, skip mutex
            else -> CommandResult.unsupportedMethod(method)
        }
    }

    private fun buildGroups() = listOf(
        // Query API
        DescriptorGroup(
            "Query API",
            methods = listOf(
                MethodDescriptor(
                    "sunmi.printerx.printer.query.getStatus",
                    "Gets real-time printer status. Blocking — runs on IO thread.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"printerId":""}""",
                    exampleOutput = """{"result":"NORMAL"}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.query.getInfo",
                    "Gets printer info.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"info":"NAME"}""",
                    exampleOutput = """{"result":"Sunmi T2"}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.query.getParam",
                    "Gets printer runtime parameter.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"param":"RUNTIME_ADC"}""",
                    exampleOutput = """{"result":"1024"}"""
                ),
                // getAccessoryInfo not available in printerx:1.0.17 classes.jar
            )
        ),
        // Command API
        DescriptorGroup(
            "Command API",
            methods = listOf(
                MethodDescriptor(
                    "sunmi.printerx.printer.command.sendEscCommand",
                    "Sends raw ESC/POS bytes (base64).",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"data":"G0A="}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.command.sendTsplCommand",
                    "Sends raw TSPL bytes (base64).",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"data":"U0laRSA0LDM="}""",
                    exampleOutput = """{}"""
                ),
            )
        ),
        // Line API
        DescriptorGroup(
            "Line API",
            methods = listOf(
                MethodDescriptor(
                    "sunmi.printerx.printer.line.initLine",
                    "Initializes line style.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"align":"CENTER","width":0,"height":30,"posX":0}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.line.addText",
                    "Adds text to buffer (no immediate print).",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"text":"Hello World","style":{"textSize":24,"bold":true}}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.line.printText",
                    "Prints text immediately.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"text":"Hello World","style":{"textSize":24}}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.line.printTexts",
                    "Prints columnar text.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"texts":["Item","12.99"],"colsWidth":[1,1],"styles":[{},{}]}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.line.printBarCode",
                    "Prints barcode.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"code":"1234567890","style":{"symbology":"CODE128"}}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.line.printQrCode",
                    "Prints QR code.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"code":"https://example.com","style":{"dot":4,"errorLevel":"L"}}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.line.printBitmap",
                    "Prints image.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"bitmap":"iVBOR...","style":{"algorithm":"BINARIZATION","value":200}}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.line.printDividingLine",
                    "Prints dividing line.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"style":"SOLID","offset":30}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.line.autoOut",
                    "Feeds paper and cuts (if cutter present).",
                    "sunmi.printerx.printer",
                    exampleParameters = """{}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.line.enableTransMode",
                    "Enables/disables transaction mode.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"enable":true}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.line.printTrans",
                    "Submits and prints transaction. Synchronous — waits for print result.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{}""",
                    exampleOutput = """{"resultCode":0,"message":""}"""
                ),
            )
        ),
        // Canvas API
        DescriptorGroup(
            "Canvas API",
            methods = listOf(
                MethodDescriptor(
                    "sunmi.printerx.printer.canvas.initCanvas",
                    "Initializes canvas.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"width":330,"height":330,"posX":0,"posY":0,"label":false}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.canvas.renderText",
                    "Draws text on canvas.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"text":"Hello","style":{"posX":0,"posY":0,"textSize":24}}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.canvas.renderBarCode",
                    "Draws barcode on canvas.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"code":"1234567890","style":{"posX":0,"posY":0,"symbology":"CODE128"}}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.canvas.renderQrCode",
                    "Draws QR code on canvas.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"code":"https://example.com","style":{"posX":0,"posY":0,"dot":4}}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.canvas.renderBitmap",
                    "Draws image on canvas.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"bitmap":"iVBOR...","style":{"posX":0,"posY":0}}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.canvas.renderArea",
                    "Draws shape on canvas.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"style":{"shape":"BOX","posX":0,"posY":0,"width":50,"height":50,"thick":1}}""",
                    exampleOutput = """{}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.canvas.printCanvas",
                    "Prints canvas content. Synchronous — waits for print result.",
                    "sunmi.printerx.printer",
                    exampleParameters = """{"count":1}""",
                    exampleOutput = """{"resultCode":0,"message":""}"""
                ),
            )
        ),
        // File API
        DescriptorGroup(
            "File API",
            methods = listOf(
                MethodDescriptor(
                    "sunmi.printerx.printer.file.printFile",
                    "Prints file with style options. Synchronous.",
                    "sunmi.printerx.printer",
                    experimental = true,
                    exampleParameters = """{"path":"/sdcard/file.pdf","copies":1,"duplex":"SINGLE","rotate":"ROTATE_0","collate":true,"pageStart":0,"pageEnd":0}""",
                    exampleOutput = """{"resultCode":0,"message":""}"""
                ),
                MethodDescriptor(
                    "sunmi.printerx.printer.file.printFileSimple",
                    "Prints file without style options. Synchronous.",
                    "sunmi.printerx.printer",
                    experimental = true,
                    exampleParameters = """{"path":"/sdcard/file.pdf"}""",
                    exampleOutput = """{"resultCode":0,"message":""}"""
                ),
            )
        ),
        // Status events
        DescriptorGroup(
            "Status",
            events = listOf(
                EventDescriptor("sunmi.printerx.printer.status.ready", "Printer ready.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.outOfPaper", "Printer out of paper.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.paperJam", "Printer paper jam.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.overheating", "Printhead overheated.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.motorOverheating", "Motor overheated.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.coverOpen", "Paper bin cover open.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.coverIncomplete", "Paper bin cover not fully closed.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.cutterError", "Cutter error.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.cutterRepaired", "Cutter repaired.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.blackLabelNotDetected", "Black mark paper not detected.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.labelNotDetected", "Label paper not detected.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.unknownError", "Unknown printer error.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.paperNotRemoved", "Printed paper not removed.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.paperLow", "Paper running low.", "sunmi.printerx.printer", exampleEvent = """{}"""),
                EventDescriptor("sunmi.printerx.printer.status.printerNotDetected", "Printer not detected.", "sunmi.printerx.printer", exampleEvent = """{}"""),
            )
        ),
    )
}
