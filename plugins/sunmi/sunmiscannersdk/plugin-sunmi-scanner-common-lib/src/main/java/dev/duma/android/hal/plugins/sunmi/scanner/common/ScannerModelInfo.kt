package dev.duma.android.hal.plugins.sunmi.scanner.common

/**
 * Structured metadata about the scanner engine (a.k.a. "scanner head" / "ScannerHeadModel")
 * that `com.sunmi.scanner` reports through [com.sunmi.scanner.sdk.InnerScanner.getScannerModel].
 *
 * ## Where the capability data lives
 *
 * Whether a given scanner model supports a given symbology/feature is **not** decided by our
 * code — it is baked into the `com.sunmi.scanner` system service. The service ships one JSON
 * descriptor per engine family in its `assets/` (discovered from the decompiled `CodeConstants`
 * of an older service build):
 *
 *  - Honeywell engines → `HoneywellConfig.json` / `HoneywellDefaultConfig.json`
 *  - Newland engines    → `NewlandConfig.json`  / `NewlandDefaultConfig.json`
 *                         (+ model overrides `Newland2096Config.json`, `Newland2596Config.json`,
 *                          `Newland3108Config.json`)
 *  - Zebra engines      → `ZebraConfig.json`    / `ZebraDefaultConfig.json`
 *                         (+ model override `Zebra1350Config.json`)
 *  - SM engines         → `SmConfig.json`       / `SmDefaultConfig.json`
 *  - N1365/Y1825 combo  → `Fp1825Nls1365Config.json` / `Fp1825Nls1365DefaultConfig.json`
 *
 * These files enumerate the symbologies and parameter ranges the engine accepts. Sending a
 * `scanXXXX=..;` command for a symbology the engine does not implement is silently ignored by
 * the service — which is exactly the "unreliable" behaviour we want to guard against.
 *
 * Because we cannot read those service-internal assets at runtime, the **authoritative runtime
 * source of truth** is the service's own query API: `QUERY_ALL_ENABLE_CODE` (`scan0001000`)
 * returns the exact list of symbologies the *currently connected* engine supports. See
 * [ScannerCapabilities], which is built on top of it.
 *
 * [engineFamily] here is a coarse grouping derived from the model name; it lets callers reason
 * about capability at the family level (e.g. for UI hints) without a round-trip, but it is NOT a
 * substitute for the live [ScannerCapabilities] query.
 */
enum class ScannerEngineFamily {
    NONE,
    NEWLAND,
    ZEBRA,
    HONEYWELL,
    SM,
    /** Sunmi's combined FP1825 + Newland N1365 module (model 101, SUPER_N1365_Y1825). */
    FP1825_NLS1365,
    UNKNOWN,
}

/**
 * Immutable description of a scanner-head model id as reported by `getScannerModel()`.
 *
 * The id values match the constants in [dev.duma.android.hal.plugins.sunmi.scanner.common.compat.ScannerService]
 * (100..122 for the service build this project was derived from). Unknown ids are surfaced as
 * [ScannerEngineFamily.UNKNOWN] rather than dropped, so newer firmware that reports a higher id
 * still degrades gracefully.
 */
data class ScannerModelInfo(
    val id: Int,
    val name: String,
    val engineFamily: ScannerEngineFamily,
    /**
     * Whether the engine can decode 2D matrix symbologies (QR, Data Matrix, PDF417, Aztec, …).
     *
     *  - `true`  — 2D area imager (proven or vendor-part-number confirmed).
     *  - `false` — linear 1D-only engine; physically cannot read 2D codes.
     *  - `null`  — unknown (Sunmi publishes no capability data, e.g. the in-house `SM_SS_*`
     *              engines, or an unrecognised/newer model id).
     *
     * Sourced from Sunmi's Scanner User Guide symbology matrix
     * (https://docs.sunmi.com/read/en-US/frmeghjk546). Only ids 101, 107 and 112 are documented
     * as 1D-only; the `SM_SS_*` engines and any id outside 100..122 are left as `null`.
     */
    val supports2d: Boolean?,
) {
    companion object {
        const val NONE_ID = 100

        /** Model ids documented as linear 1D-only engines (cannot decode 2D matrix codes). */
        private val ONE_D_ONLY_IDS = setOf(101, 107, 112)

        /** Model ids for which Sunmi publishes no symbology capability data (in-house engines). */
        private val UNKNOWN_CAPABILITY_IDS = setOf(113, 118, 119, 121, 122)

        /**
         * Known model id → name, mirroring the decompiled `ScannerService` table. Kept here as
         * the single structured source so [engineFamilyForId] and callers stay in sync.
         */
        private val NAMES: Map<Int, String> = mapOf(
            100 to "NONE",
            101 to "SUPER_N1365_Y1825",
            102 to "NLS_2096",
            103 to "ZEBRA_4710",
            104 to "HONEYWELL_3601",
            105 to "HONEYWELL_6603",
            106 to "ZEBRA_4750",
            107 to "ZEBRA_1350",
            108 to "HONEYWELL_6703",
            109 to "HONEYWELL_3603",
            110 to "NLS_CM47",
            111 to "NLS_3108",
            112 to "ZEBRA_965",
            113 to "SM_SS_1100",
            114 to "NLS_CM30",
            115 to "HONEYWELL_4603",
            116 to "ZEBRA_4770",
            117 to "NLS_2596",
            118 to "SM_SS_1103",
            119 to "SM_SS_1101",
            120 to "HONEYWELL_5703",
            121 to "SM_SS_1100_2",
            122 to "SM_SS_1104",
        )

        fun nameForId(id: Int): String = NAMES[id] ?: "UNKNOWN"

        fun engineFamilyForId(id: Int): ScannerEngineFamily {
            if (id == NONE_ID) return ScannerEngineFamily.NONE
            val name = NAMES[id] ?: return ScannerEngineFamily.UNKNOWN
            return when {
                name == "SUPER_N1365_Y1825" -> ScannerEngineFamily.FP1825_NLS1365
                name.startsWith("NLS_") -> ScannerEngineFamily.NEWLAND
                name.startsWith("ZEBRA_") -> ScannerEngineFamily.ZEBRA
                name.startsWith("HONEYWELL_") -> ScannerEngineFamily.HONEYWELL
                name.startsWith("SM_") -> ScannerEngineFamily.SM
                else -> ScannerEngineFamily.UNKNOWN
            }
        }

        /**
         * `true`/`false`/`null` for 2D-symbology support. `null` (unknown) for the in-house
         * `SM_SS_*` engines, for `NONE`, and for any id Sunmi has not documented.
         */
        fun supports2dForId(id: Int): Boolean? = when {
            id == NONE_ID -> null
            id in ONE_D_ONLY_IDS -> false
            id in UNKNOWN_CAPABILITY_IDS -> null
            id in 101..122 -> true
            else -> null
        }

        fun of(id: Int): ScannerModelInfo =
            ScannerModelInfo(id, nameForId(id), engineFamilyForId(id), supports2dForId(id))
    }
}
