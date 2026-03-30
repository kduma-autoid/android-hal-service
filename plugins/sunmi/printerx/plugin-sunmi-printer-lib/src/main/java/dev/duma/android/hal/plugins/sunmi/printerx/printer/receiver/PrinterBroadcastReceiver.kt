package dev.duma.android.hal.plugins.sunmi.printerx.printer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.json.JSONObject

internal class PrinterBroadcastReceiver(
    private val emitEvent: (String, String) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val halEvent = BROADCAST_TO_EVENT[intent.action] ?: return
        emitEvent(halEvent, JSONObject().put("broadcast", intent.action).toString())
    }

    companion object {
        val BROADCAST_TO_EVENT = mapOf(
            "woyou.aidlservice.jiuv5.NORMAL_ACTION"              to "sunmi.printerx.printer.status.ready",
            "woyou.aidlservice.jiuv5.OUT_OF_PAPER_ACTION"        to "sunmi.printerx.printer.status.outOfPaper",
            "woyou.aidlservice.jiuv5.PAPER_ERROR_ACITON"         to "sunmi.printerx.printer.status.paperJam",
            "woyou.aidlservice.jiuv5.OVER_HEATING_ACITON"        to "sunmi.printerx.printer.status.overheating",
            "woyou.aidlservice.jiuv5.MOTOR_HEATING_ACITON"       to "sunmi.printerx.printer.status.motorOverheating",
            "woyou.aidlservice.jiuv5.COVER_OPEN_ACTION"          to "sunmi.printerx.printer.status.coverOpen",
            "woyou.aidlservice.jiuv5.COVER_ERROR_ACTION"         to "sunmi.printerx.printer.status.coverIncomplete",
            "woyou.aidlservice.jiuv5.KNIFE_ERROR_ACTION_1"       to "sunmi.printerx.printer.status.cutterError",
            "woyou.aidlservice.jiuv5.KNIFE_ERROR_ACTION_2"       to "sunmi.printerx.printer.status.cutterRepaired",
            "woyou.aidlservice.jiuv5.BLACKLABEL_NON_EXISTENT_ACITON" to "sunmi.printerx.printer.status.blackLabelNotDetected",
            "woyou.aidlservice.jiuv5.LABEL_NON_EXISTENT_ACITON"  to "sunmi.printerx.printer.status.labelNotDetected",
            "woyou.aidlservice.jiuv5.ERROR_ACTION"               to "sunmi.printerx.printer.status.unknownError",
            "woyou.aidlservice.jiuv5.PICK_PAPER_ACTION"          to "sunmi.printerx.printer.status.paperNotRemoved",
            "woyou.aidlservice.jiuv5.LESS_OF_PAPER_ACTION"       to "sunmi.printerx.printer.status.paperLow",
            "woyou.aidlservice.jiuv5.PRINTER_NON_EXISTENT_ACITON" to "sunmi.printerx.printer.status.printerNotDetected",
        )

        fun buildIntentFilter() = IntentFilter().apply {
            BROADCAST_TO_EVENT.keys.forEach { addAction(it) }
        }
    }
}
