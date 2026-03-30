package dev.duma.android.hal.plugins.sunmi.printerx.printer

import android.content.Context
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor
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
            ctx.registerReceiver(broadcastReceiver, PrinterBroadcastReceiver.buildIntentFilter())
        }
    }

    override fun getCapabilities() = listOf("sunmi.printerx.printer")

    override fun getDescriptor() = PluginDescriptor(
        pluginId = pluginId,
        name = "Sunmi PrinterX Printer",
        version = version,
        capabilities = getCapabilities(),
        methods = buildMethodList(),
        events = buildEventList()
    )

    override suspend fun handleExecute(method: String, params: String, json: JSONObject): String {
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
            else -> unsupportedMethod(method)
        }
    }

    private fun buildMethodList() = listOf(
        // Query API
        MethodDescriptor("sunmi.printerx.printer.query.getStatus", "Gets real-time printer status. Blocking — runs on IO thread. Params: {\"printerId\":\"opt\"}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.query.getInfo", "Gets printer info. Params: {\"printerId\":\"opt\",\"info\":\"ID|NAME|VERSION|DISTANCE|CUTTER|HOT|DENSITY|TYPE|PAPER|GRAY\"}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.query.getParam", "Gets printer runtime parameter. Params: {\"printerId\":\"opt\",\"param\":\"RUNTIME_ADC|HIGH_ADC|LOW_ADC|PWM|PWM_ADC|GPIO_PIN|GPIO_STATE\"}", "sunmi.printerx.printer"),
        // getAccessoryInfo not available in printerx:1.0.17 classes.jar
        // Command API
        MethodDescriptor("sunmi.printerx.printer.command.sendEscCommand", "Sends raw ESC/POS bytes (base64). Params: {\"printerId\":\"opt\",\"data\":\"<base64>\"}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.command.sendTsplCommand", "Sends raw TSPL bytes (base64). Params: {\"printerId\":\"opt\",\"data\":\"<base64>\"}", "sunmi.printerx.printer"),
        // Line API
        MethodDescriptor("sunmi.printerx.printer.line.initLine", "Initializes line style. Params: {\"printerId\":\"opt\",\"align\":\"LEFT|CENTER|RIGHT\",\"width\":0,\"height\":30,\"posX\":0}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.line.addText", "Adds text to buffer (no immediate print). Params: {\"printerId\":\"opt\",\"text\":\"...\",\"style\":{...}}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.line.printText", "Prints text immediately. Params: {\"printerId\":\"opt\",\"text\":\"...\",\"style\":{...}}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.line.printTexts", "Prints columnar text. Params: {\"printerId\":\"opt\",\"texts\":[\"col1\",\"col2\"],\"colsWidth\":[1,2],\"styles\":[{...},...]}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.line.printBarCode", "Prints barcode. Params: {\"printerId\":\"opt\",\"code\":\"123\",\"style\":{\"symbology\":\"CODE128\",...}}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.line.printQrCode", "Prints QR code. Params: {\"printerId\":\"opt\",\"code\":\"...\",\"style\":{\"dot\":4,\"errorLevel\":\"L\",...}}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.line.printBitmap", "Prints image. Params: {\"printerId\":\"opt\",\"bitmap\":\"<base64>\",\"style\":{\"algorithm\":\"BINARIZATION\",\"value\":200,...}}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.line.printDividingLine", "Prints dividing line. Params: {\"printerId\":\"opt\",\"style\":\"EMPTY|SOLID|DOTTED\",\"offset\":30}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.line.autoOut", "Feeds paper and cuts (if cutter present). Params: {\"printerId\":\"opt\"}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.line.enableTransMode", "Enables/disables transaction mode. Params: {\"printerId\":\"opt\",\"enable\":true}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.line.printTrans", "Submits and prints transaction. Synchronous — waits for print result. Params: {\"printerId\":\"opt\"}", "sunmi.printerx.printer"),
        // Canvas API
        MethodDescriptor("sunmi.printerx.printer.canvas.initCanvas", "Initializes canvas. Params: {\"printerId\":\"opt\",\"width\":330,\"height\":330,\"posX\":0,\"posY\":0,\"label\":false}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.canvas.renderText", "Draws text on canvas. Params: {\"printerId\":\"opt\",\"text\":\"...\",\"style\":{\"posX\":0,\"posY\":0,\"textSize\":24,...}}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.canvas.renderBarCode", "Draws barcode on canvas. Params: {\"printerId\":\"opt\",\"code\":\"123\",\"style\":{\"posX\":0,\"posY\":0,...}}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.canvas.renderQrCode", "Draws QR code on canvas. Params: {\"printerId\":\"opt\",\"code\":\"...\",\"style\":{\"posX\":0,\"posY\":0,\"dot\":4,...}}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.canvas.renderBitmap", "Draws image on canvas. Params: {\"printerId\":\"opt\",\"bitmap\":\"<base64>\",\"style\":{\"posX\":0,\"posY\":0,...}}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.canvas.renderArea", "Draws shape on canvas. Params: {\"printerId\":\"opt\",\"style\":{\"shape\":\"BOX|RECT_FILL|...\",\"posX\":0,\"posY\":0,\"width\":50,\"height\":50,\"thick\":1}}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.canvas.printCanvas", "Prints canvas content. Synchronous — waits for print result. Params: {\"printerId\":\"opt\",\"count\":1}", "sunmi.printerx.printer"),
        // File API
        MethodDescriptor("sunmi.printerx.printer.file.printFile", "Prints file with style options. Synchronous. Params: {\"printerId\":\"opt\",\"path\":\"/sdcard/file.pdf\",\"copies\":1,\"duplex\":\"SINGLE\",\"rotate\":\"ROTATE_0\",\"collate\":true,\"pageStart\":0,\"pageEnd\":0}", "sunmi.printerx.printer"),
        MethodDescriptor("sunmi.printerx.printer.file.printFileSimple", "Prints file without style options. Synchronous. Params: {\"printerId\":\"opt\",\"path\":\"/sdcard/file.pdf\"}", "sunmi.printerx.printer"),
    )

    private fun buildEventList() = listOf(
        // Printer status broadcasts
        EventDescriptor("sunmi.printerx.printer.status.ready",               "Printer ready.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.outOfPaper",          "Printer out of paper.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.paperJam",            "Printer paper jam.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.overheating",         "Printhead overheated.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.motorOverheating",    "Motor overheated.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.coverOpen",           "Paper bin cover open.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.coverIncomplete",     "Paper bin cover not fully closed.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.cutterError",         "Cutter error.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.cutterRepaired",      "Cutter repaired.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.blackLabelNotDetected","Black mark paper not detected.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.labelNotDetected",    "Label paper not detected.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.unknownError",        "Unknown printer error.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.paperNotRemoved",     "Printed paper not removed.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.paperLow",            "Paper running low.", "sunmi.printerx.printer"),
        EventDescriptor("sunmi.printerx.printer.status.printerNotDetected",  "Printer not detected.", "sunmi.printerx.printer"),
    )
}
