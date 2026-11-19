package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceContract
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

/**
 * Definer for the `scale` interface — the unified weighing scale surface (first provider: `dymo.m10`).
 *
 * A **reading** is `{grams, value, unit, status, stable}`:
 * - `grams` — the weight in grams whatever unit the scale is set to (the field clients should use);
 * - `value` + `unit` (`"g"` / `"oz"`) — the weight exactly as the scale displays it;
 * - `status` — the state the scale itself reports: `zero`, `inMotion`, `stable`, `underZero`,
 *   `overWeight`, `fault`, `needCalibration`, `needZeroing` or `unknown`. Informational only;
 * - `stable` — computed by the provider: the weight has settled (not changed for a short while) and
 *   the scale reports no error. Use this rather than `status`, which some scales (the M10) report as
 *   `stable` nearly all the time.
 *
 * This plugin only *registers* the contract (no hardware, no capabilities).
 */
class ScaleInterface : HalPlugin {

    override val pluginId = "interface.scale"
    override val version = 1

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = emptyList()

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "[Interface] Scale",
        version = version,
        capabilities = emptyList(),
        groups = emptyList(),
        definesInterfaces = listOf(SCALE_CONTRACT)
    )

    override fun initialize(pluginContext: PluginContext) {}

    // Never routed here — interface methods run on the resolved provider plugin.
    override suspend fun execute(method: String, params: String): CommandResult =
        CommandResult.unsupportedMethod(method)

    override fun setEventCallback(callback: HalPluginEventCallback?) {}

    companion object {
        private const val PERMISSION = "scale"
        const val READING_EXAMPLE =
            """{"grams":1250.0,"value":1250.0,"unit":"g","status":"stable","stable":true}"""

        val SCALE_CONTRACT = InterfaceContract(
            interfaceId = "scale",
            version = 1,
            methods = listOf(
                MethodDescriptor(
                    "scale.getInfo",
                    "Scale name, connection state and the latest reading (null before the first one).",
                    PERMISSION,
                    exampleParameters = "{}",
                    exampleOutput = """{"name":"Dymo M10","connected":true,"reading":$READING_EXAMPLE}"""
                ),
                MethodDescriptor(
                    "scale.getWeight",
                    "The latest reading: grams, the displayed value + unit, the scale's own status and " +
                        "the provider-computed stable flag. Fails with unavailable before the first reading.",
                    PERMISSION,
                    exampleParameters = "{}",
                    exampleOutput = READING_EXAMPLE
                )
            ),
            events = listOf(
                EventDescriptor(
                    "scale.onWeight",
                    "A new reading: the weight moved (by 1 g or more; stable=false), settled (stable=true) " +
                        "or the scale's status changed.",
                    PERMISSION,
                    exampleEvent = READING_EXAMPLE
                ),
                EventDescriptor(
                    "scale.onStatus",
                    "The status reported by the scale changed.",
                    PERMISSION,
                    exampleEvent = """{"status":"stable","previous":"inMotion"}"""
                )
            )
        )
    }
}
