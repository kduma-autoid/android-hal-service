package dev.duma.android.hal.plugins.generic

import dev.duma.android.hal.contract.CommandResult
import dev.duma.android.hal.contract.EventDescriptor
import dev.duma.android.hal.contract.HalPlugin
import dev.duma.android.hal.contract.HalPluginEventCallback
import dev.duma.android.hal.contract.InterfaceContract
import dev.duma.android.hal.contract.InterfaceFeature
import dev.duma.android.hal.contract.MethodDescriptor
import dev.duma.android.hal.contract.PluginContext
import dev.duma.android.hal.contract.PluginDescriptor

/**
 * Definer for the `magneticCardReader` interface — the unified magnetic stripe surface.
 *
 * Two kinds of provider fit it:
 * - **passive** readers (e.g. `sunmi.card`) that only report swipes via `magneticCardReader.onSwipe`;
 * - **active** readers/writers (e.g. `msr605x`) that can also be asked to wait for a swipe
 *   (`read`, feature `read`) and to encode a card (`write`, feature `write`).
 *
 * Track data is always given **without sentinels** (`%`/`;` … `?`). In a read result or event a
 * track is a string, `""` when the track is empty, or `null` when it could not be read.
 *
 * This plugin only *registers* the contract (no hardware, no capabilities).
 */
class MagneticCardReaderInterface : HalPlugin {

    override val pluginId = "interface.magneticCardReader"
    override val version = 1

    override fun isSupported(): Boolean = true

    override fun getCapabilities(): List<String> = emptyList()

    override fun getDescriptor(): PluginDescriptor = PluginDescriptor(
        pluginId = pluginId,
        name = "[Interface] Magnetic Card Reader",
        version = version,
        capabilities = emptyList(),
        groups = emptyList(),
        definesInterfaces = listOf(MAGNETIC_CARD_READER_CONTRACT)
    )

    override fun initialize(pluginContext: PluginContext) {}

    // Never routed here — interface methods run on the resolved provider plugin.
    override suspend fun execute(method: String, params: String): CommandResult =
        CommandResult.unsupportedMethod(method)

    override fun setEventCallback(callback: HalPluginEventCallback?) {}

    companion object {
        private const val PERMISSION = "magneticCardReader"
        private const val TRACKS_EXAMPLE =
            """{"track1":"B4111111111111111^DOE/JOHN^2512101123400001","track2":"4111111111111111=25121011234000010001","track3":""}"""

        val MAGNETIC_CARD_READER_CONTRACT = InterfaceContract(
            interfaceId = "magneticCardReader",
            version = 1,
            methods = listOf(
                MethodDescriptor(
                    "magneticCardReader.getInfo",
                    "Reader name and connection state.",
                    PERMISSION,
                    exampleParameters = "{}",
                    exampleOutput = """{"name":"MSR605X","connected":true}"""
                ),
                MethodDescriptor(
                    "magneticCardReader.read",
                    "Wait (up to timeoutMs) for a card swipe and return its tracks. Also emitted as magneticCardReader.onSwipe.",
                    PERMISSION,
                    exampleParameters = """{"timeoutMs":15000}""",
                    exampleOutput = TRACKS_EXAMPLE
                ),
                MethodDescriptor(
                    "magneticCardReader.cancel",
                    "Abort a pending magneticCardReader.read / write.",
                    PERMISSION,
                    exampleParameters = "{}",
                    exampleOutput = """{"cancelled":true}"""
                ),
                MethodDescriptor(
                    "magneticCardReader.write",
                    "Wait (up to timeoutMs) for a card swipe and encode the given tracks (data without sentinels).",
                    PERMISSION,
                    exampleParameters = """{"track1":"B4111111111111111^DOE/JOHN^2512101123400001","track2":"4111111111111111=2512101","timeoutMs":15000}""",
                    exampleOutput = """{"status":"ok"}"""
                )
            ),
            events = listOf(
                EventDescriptor(
                    "magneticCardReader.onSwipe",
                    "A card was swiped and read. The emitting reader is carried in the event header (source), " +
                        "so clients can filter with magneticCardReader.onSwipe@<providerId>.",
                    PERMISSION,
                    exampleEvent = TRACKS_EXAMPLE
                )
            ),
            features = listOf(
                InterfaceFeature(
                    "read",
                    "On-demand reads: the reader can be asked to wait for a swipe (passive readers only emit onSwipe).",
                    methods = listOf("magneticCardReader.read", "magneticCardReader.cancel")
                ),
                InterfaceFeature(
                    "write",
                    "The device can encode magnetic stripe cards.",
                    methods = listOf("magneticCardReader.write")
                )
            )
        )
    }
}
