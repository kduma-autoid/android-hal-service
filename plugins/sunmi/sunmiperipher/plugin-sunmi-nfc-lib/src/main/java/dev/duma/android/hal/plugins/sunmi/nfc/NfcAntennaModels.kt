package dev.duma.android.hal.plugins.sunmi.nfc

import org.json.JSONArray
import org.json.JSONObject

internal enum class AntennaState(val wireValue: String) {
    INITIALIZING("initializing"),
    READY("ready"),
    UNAVAILABLE("unavailable")
}

internal enum class NfcController(val wireValue: String) {
    BUILTIN("builtin"),
    EXTERNAL("external"),
    UNKNOWN("unknown")
}

internal enum class NfcReader(val wireValue: String) {
    BUILTIN("builtin"),
    EXTERNAL("external"),
    UNKNOWN("unknown")
}

internal enum class LocationUnavailableReason(val wireValue: String) {
    EXTERNAL_LOCATION_UNKNOWN("external_location_unknown"),
    READER_IDENTITY_UNKNOWN("reader_identity_unknown"),
    MISSING_LOCATION("missing_location"),
    INVALID_LOCATION("invalid_location"),
    SETTINGS_UNREADABLE("settings_unreadable"),
    DISPLAY_UNAVAILABLE("display_unavailable"),
    UNSUPPORTED_DISPLAY_METRICS("unsupported_display_metrics")
}

internal data class CachedNfcModule(
    val sn: String,
    val sdkType: Int,
    val selected: Boolean,
    val watermarkAlpha: Int
)

internal data class PixelRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

internal data class AntennaLocation(
    val displayId: Int,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val rotationDegrees: Int,
    val rect: PixelRect,
    val estimated: Boolean
)

internal data class NfcModuleSnapshot(
    val sn: String,
    val sdkType: Int,
    val selected: Boolean,
    val reader: NfcReader,
    val location: AntennaLocation?,
    val locationUnavailableReason: LocationUnavailableReason?
)

internal data class NfcAntennaSnapshot(
    val state: AntennaState,
    val controller: NfcController,
    val modules: List<NfcModuleSnapshot>
) {
    fun toJson(): String = JSONObject().apply {
        put("state", state.wireValue)
        put("controller", controller.wireValue)
        put("modules", JSONArray().apply {
            modules.forEach { module ->
                put(JSONObject().apply {
                    put("sn", module.sn)
                    put("sdkType", module.sdkType)
                    put("selected", module.selected)
                    put("reader", module.reader.wireValue)
                    put("location", module.location?.toJsonObject() ?: JSONObject.NULL)
                    put(
                        "locationUnavailableReason",
                        module.locationUnavailableReason?.wireValue ?: JSONObject.NULL
                    )
                })
            }
        })
    }.toString()
}

private fun AntennaLocation.toJsonObject(): JSONObject = JSONObject().apply {
    put("displayId", displayId)
    put("displayWidthPx", displayWidthPx)
    put("displayHeightPx", displayHeightPx)
    put("rotationDegrees", rotationDegrees)
    put("rect", JSONObject().apply {
        put("x", rect.x)
        put("y", rect.y)
        put("width", rect.width)
        put("height", rect.height)
    })
    put("estimated", estimated)
}

internal data class SettingsSnapshot(
    val readable: Boolean,
    val locationJson: String?,
    val controller: NfcController,
    val externalDisplayInfoJson: String?
) {
    companion object {
        val UNREADABLE = SettingsSnapshot(
            readable = false,
            locationJson = null,
            controller = NfcController.UNKNOWN,
            externalDisplayInfoJson = null
        )
    }
}

internal data class DisplayMetricsSnapshot(
    val displayId: Int,
    val naturalWidthPx: Int,
    val naturalHeightPx: Int,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val rotationDegrees: Int
)

internal sealed interface DisplaySnapshot {
    data class Available(val metrics: DisplayMetricsSnapshot) : DisplaySnapshot
    data object Unavailable : DisplaySnapshot
    data object UnsupportedMetrics : DisplaySnapshot
}
