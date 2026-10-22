package dev.duma.android.hal.plugins.sunmi.nfc

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal class NfcAntennaSnapshotEngine {
    private var state = AntennaState.INITIALIZING
    private var modules: List<CachedNfcModule> = emptyList()
    private var settings = SettingsSnapshot.UNREADABLE
    private var display: DisplaySnapshot = DisplaySnapshot.Unavailable
    private var trustedHostSn: String? = null

    @Synchronized
    fun beginInitialization() {
        state = AntennaState.INITIALIZING
        modules = emptyList()
        trustedHostSn = null
    }

    @Synchronized
    fun markReady(newModules: List<CachedNfcModule>) {
        modules = newModules.toList()
        state = AntennaState.READY
    }

    @Synchronized
    fun markUnavailable() {
        modules = emptyList()
        state = AntennaState.UNAVAILABLE
    }

    @Synchronized
    fun updateEnvironment(newSettings: SettingsSnapshot, newDisplay: DisplaySnapshot) {
        settings = newSettings
        display = newDisplay
    }

    @Synchronized
    fun updateTrustedHostSn(sn: String?) {
        trustedHostSn = sn?.trim()?.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun hasTrustedHostSn(): Boolean = trustedHostSn != null

    @Synchronized
    fun snapshot(): NfcAntennaSnapshot {
        val externalSns = parseExternalNfcSerials(settings.externalDisplayInfoJson)
        val hostSn = trustedHostSn
        val duplicateSerials = modules.groupingBy { it.sn }.eachCount()
            .filterValues { it > 1 }
            .keys
        val locationResolution = if (!settings.readable) {
            LocationResolution.Unavailable(LocationUnavailableReason.SETTINGS_UNREADABLE)
        } else {
            NfcAntennaGeometry.resolve(settings.locationJson, display)
        }

        return NfcAntennaSnapshot(
            state = state,
            controller = settings.controller,
            modules = modules.map { module ->
                val hostMatch = hostSn != null && module.sn == hostSn
                val externalMatch = module.sn in externalSns
                val reader = when {
                    module.sn in duplicateSerials -> NfcReader.UNKNOWN
                    hostMatch && !externalMatch -> NfcReader.BUILTIN
                    externalMatch && !hostMatch -> NfcReader.EXTERNAL
                    else -> NfcReader.UNKNOWN
                }

                when (reader) {
                    NfcReader.BUILTIN -> when (locationResolution) {
                        is LocationResolution.Resolved -> module.toSnapshot(
                            reader = reader,
                            location = locationResolution.location,
                            reason = null
                        )
                        is LocationResolution.Unavailable -> module.toSnapshot(
                            reader = reader,
                            location = null,
                            reason = locationResolution.reason
                        )
                    }
                    NfcReader.EXTERNAL -> module.toSnapshot(
                        reader = reader,
                        location = null,
                        reason = LocationUnavailableReason.EXTERNAL_LOCATION_UNKNOWN
                    )
                    NfcReader.UNKNOWN -> module.toSnapshot(
                        reader = reader,
                        location = null,
                        reason = LocationUnavailableReason.READER_IDENTITY_UNKNOWN
                    )
                }
            }
        )
    }

    private fun CachedNfcModule.toSnapshot(
        reader: NfcReader,
        location: AntennaLocation?,
        reason: LocationUnavailableReason?
    ) = NfcModuleSnapshot(
        sn = sn,
        sdkType = sdkType,
        selected = selected,
        reader = reader,
        location = location,
        locationUnavailableReason = reason
    )

    private fun parseExternalNfcSerials(rawJson: String?): Set<String> {
        if (rawJson.isNullOrBlank()) return emptySet()
        return try {
            buildSet { collectExternalNfcSerials(JSONTokener(rawJson).nextValue(), this) }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun collectExternalNfcSerials(value: Any?, output: MutableSet<String>) {
        when (value) {
            is JSONObject -> {
                val sn = value.optString("sn", "").trim()
                val nfcType = value.strictInt("nfc_type")
                if (sn.isNotEmpty() && nfcType != null && nfcType != -1) output += sn
                val keys = value.keys()
                while (keys.hasNext()) collectExternalNfcSerials(value.opt(keys.next()), output)
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectExternalNfcSerials(value.opt(index), output)
                }
            }
        }
    }

    private fun JSONObject.strictInt(key: String): Int? = when (val value = opt(key)) {
        is Byte, is Short, is Int -> (value as Number).toInt()
        is Long -> value.toInt().takeIf { it.toLong() == value }
        is String -> value.toIntOrNull()
        else -> null
    }
}
