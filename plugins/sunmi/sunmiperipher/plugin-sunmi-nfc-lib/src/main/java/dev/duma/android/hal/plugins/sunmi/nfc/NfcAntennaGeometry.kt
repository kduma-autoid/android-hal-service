package dev.duma.android.hal.plugins.sunmi.nfc

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface LocationResolution {
    data class Resolved(val location: AntennaLocation) : LocationResolution
    data class Unavailable(val reason: LocationUnavailableReason) : LocationResolution
}

internal object NfcAntennaGeometry {
    fun resolve(locationJson: String?, display: DisplaySnapshot): LocationResolution {
        val raw = parseRect(locationJson) ?: return if (locationJson.isNullOrBlank() || isEmptyArray(locationJson)) {
            LocationResolution.Unavailable(LocationUnavailableReason.MISSING_LOCATION)
        } else {
            LocationResolution.Unavailable(LocationUnavailableReason.INVALID_LOCATION)
        }

        val metrics = when (display) {
            DisplaySnapshot.Unavailable -> return LocationResolution.Unavailable(
                LocationUnavailableReason.DISPLAY_UNAVAILABLE
            )
            DisplaySnapshot.UnsupportedMetrics -> return LocationResolution.Unavailable(
                LocationUnavailableReason.UNSUPPORTED_DISPLAY_METRICS
            )
            is DisplaySnapshot.Available -> display.metrics
        }

        val right = raw.x + raw.width
        val bottom = raw.y + raw.height
        val naturalWidth = metrics.naturalWidthPx.toLong()
        val naturalHeight = metrics.naturalHeightPx.toLong()
        val fitsNatural = right <= naturalWidth && bottom <= naturalHeight
        val fitsRotated = right <= naturalHeight && bottom <= naturalWidth
        if (!fitsNatural && !fitsRotated) {
            return LocationResolution.Unavailable(LocationUnavailableReason.INVALID_LOCATION)
        }

        val estimated = fitsNatural && fitsRotated
        val naturalRect = if (!fitsNatural && fitsRotated) {
            LongRect(
                x = naturalWidth - bottom,
                y = raw.x,
                width = raw.height,
                height = raw.width
            )
        } else {
            raw
        }

        val naturalRight = naturalRect.x + naturalRect.width
        val naturalBottom = naturalRect.y + naturalRect.height
        val rotated = when (metrics.rotationDegrees) {
            0 -> naturalRect
            90 -> LongRect(
                x = naturalRect.y,
                y = naturalWidth - naturalRight,
                width = naturalRect.height,
                height = naturalRect.width
            )
            180 -> LongRect(
                x = naturalWidth - naturalRight,
                y = naturalHeight - naturalBottom,
                width = naturalRect.width,
                height = naturalRect.height
            )
            270 -> LongRect(
                x = naturalHeight - naturalBottom,
                y = naturalRect.x,
                width = naturalRect.height,
                height = naturalRect.width
            )
            else -> return LocationResolution.Unavailable(
                LocationUnavailableReason.UNSUPPORTED_DISPLAY_METRICS
            )
        }

        if (!rotated.fits(metrics.displayWidthPx, metrics.displayHeightPx)) {
            return LocationResolution.Unavailable(LocationUnavailableReason.UNSUPPORTED_DISPLAY_METRICS)
        }

        return LocationResolution.Resolved(
            AntennaLocation(
                displayId = metrics.displayId,
                displayWidthPx = metrics.displayWidthPx,
                displayHeightPx = metrics.displayHeightPx,
                rotationDegrees = metrics.rotationDegrees,
                rect = PixelRect(
                    x = rotated.x.toInt(),
                    y = rotated.y.toInt(),
                    width = rotated.width.toInt(),
                    height = rotated.height.toInt()
                ),
                estimated = estimated
            )
        )
    }

    private fun parseRect(rawJson: String?): LongRect? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val array = JSONArray(rawJson)
            if (array.length() == 0) return null
            val value = array.opt(0) as? JSONObject ?: return null
            val x = value.strictLong("posX") ?: return null
            val y = value.strictLong("posY") ?: return null
            val width = value.strictLong("width") ?: return null
            val height = value.strictLong("height") ?: return null
            if (x < 0L || y < 0L || width <= 0L || height <= 0L) return null
            if (x > Int.MAX_VALUE || y > Int.MAX_VALUE || width > Int.MAX_VALUE || height > Int.MAX_VALUE) {
                return null
            }
            if (x > Long.MAX_VALUE - width || y > Long.MAX_VALUE - height) return null
            LongRect(x, y, width, height)
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.strictLong(key: String): Long? {
        val value = opt(key)
        return when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun isEmptyArray(rawJson: String): Boolean = try {
        JSONArray(rawJson).length() == 0
    } catch (_: Exception) {
        false
    }

    private data class LongRect(
        val x: Long,
        val y: Long,
        val width: Long,
        val height: Long
    ) {
        fun fits(displayWidth: Int, displayHeight: Int): Boolean {
            if (x < 0L || y < 0L || width <= 0L || height <= 0L) return false
            val right = x + width
            val bottom = y + height
            return right <= displayWidth.toLong() && bottom <= displayHeight.toLong() &&
                x <= Int.MAX_VALUE && y <= Int.MAX_VALUE &&
                width <= Int.MAX_VALUE && height <= Int.MAX_VALUE
        }
    }
}
