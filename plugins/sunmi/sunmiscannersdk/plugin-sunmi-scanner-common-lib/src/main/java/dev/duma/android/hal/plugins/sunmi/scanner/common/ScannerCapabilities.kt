package dev.duma.android.hal.plugins.sunmi.scanner.common

import com.sunmi.scanner.entity.CodeEnable
import com.sunmi.scanner.entity.Entity
import com.sunmi.scanner.io.QueryCallback
import com.sunmi.scanner.sdk.InnerScanner
import dev.duma.android.hal.plugins.sunmi.scanner.common.compat.CodeConstants
import dev.duma.android.hal.plugins.sunmi.scanner.common.compat.SunmiHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Runtime "does this scanner model support this feature" oracle for the built-in Sunmi scanner.
 *
 * The `com.sunmi.scanner` service silently ignores `scanXXXX=..;` commands that target a
 * symbology the connected engine does not implement, which makes blind command-sending
 * unreliable. Instead of guessing from the model id, this queries the service's own
 * `QUERY_ALL_ENABLE_CODE` (`scan0001000`) endpoint, which returns the authoritative list of
 * symbologies the *currently connected* engine supports (see [CodeEnable]). That list is the
 * runtime projection of the per-engine `*Config.json` assets described in [ScannerModelInfo].
 *
 * The result is cached until [invalidate] (call it whenever the active engine may have changed,
 * e.g. on (re)connect or after `setScannerModel`). When the service does not answer — not
 * connected, query failed, or an empty set (typically "no engine") — the capability set is
 * reported as *unknown* (`null`) and callers should fall back to permissive behaviour so a
 * transient failure never blocks an otherwise-working configuration.
 */
class ScannerCapabilities(
    private val queryTimeoutMs: Long = DEFAULT_QUERY_TIMEOUT_MS,
) {
    @Volatile
    private var cachedCodes: Set<String>? = null

    /** Drop any cached capability set. Call whenever the active engine may have changed. */
    fun invalidate() {
        cachedCodes = null
    }

    /**
     * Symbology display-names (e.g. "QR Code", "Code 128") the currently connected engine
     * supports, or `null` if that cannot be determined right now. Cached until [invalidate].
     */
    suspend fun supportedBarcodes(scanner: InnerScanner): Set<String>? {
        cachedCodes?.let { return it }
        val fetched = fetch(scanner) ?: return null
        cachedCodes = fetched
        return fetched
    }

    private suspend fun fetch(scanner: InnerScanner): Set<String>? {
        val deferred = CompletableDeferred<Set<String>?>()
        scanner.sendQuery(SunmiHelper.QUERY_ALL_ENABLE_CODE, object : QueryCallback() {
            override fun onSuccess(entity: Entity<*>?) {
                val bean = entity?.bean
                if (bean is CodeEnable) {
                    val codes = bean.codes?.filterNotNull()?.toSet().orEmpty()
                    // An empty set means "no engine / not answered meaningfully" — treat as unknown
                    // so we stay permissive rather than blocking every symbology.
                    deferred.complete(if (codes.isEmpty()) null else codes)
                } else {
                    deferred.complete(null)
                }
            }

            override fun onFiled(errorCode: Int) {
                deferred.complete(null)
            }
        })
        return try {
            withTimeout(queryTimeoutMs) { deferred.await() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_QUERY_TIMEOUT_MS = 5000L

        /**
         * 2D matrix / stacked symbologies (by [CodeConstants] display-name). A linear 1D engine
         * ([ScannerModelInfo.supports2d] == false) physically cannot decode any of these, so they
         * can be rejected statically from just the model id without a service round-trip.
         */
        val TWO_D_SYMBOLOGIES: Set<String> = setOf(
            CodeConstants.QR_CODE,
            CodeConstants.MICRO_QR_CODE,
            CodeConstants.DATA_MATRIX,
            CodeConstants.PDF417,
            CodeConstants.MICRO_PDF417,
            CodeConstants.AZTEC,
            CodeConstants.MAXI_CODE,
            CodeConstants.HAN_XIN_CODE,
            CodeConstants.GRID_MATRIX,
            CodeConstants.CODE_ONE,
            CodeConstants.DOT_CODE,
        )

        /** Whether [name] is a 2D matrix/stacked symbology (see [TWO_D_SYMBOLOGIES]). */
        fun is2dSymbology(name: String): Boolean = name in TWO_D_SYMBOLOGIES
    }
}
