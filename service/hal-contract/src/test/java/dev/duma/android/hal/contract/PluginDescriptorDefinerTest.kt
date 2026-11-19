package dev.duma.android.hal.contract

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginDescriptorDefinerTest {

    private val contract = InterfaceContract(interfaceId = "scale", version = 1, methods = emptyList(), events = emptyList())

    private fun plugin(
        groups: List<DescriptorGroup> = emptyList(),
        defines: List<InterfaceContract> = emptyList(),
        provides: List<InterfaceBinding> = emptyList()
    ) = PluginDescriptor("p", "P", 1, capabilities = emptyList(), groups = groups, definesInterfaces = defines, interfaces = provides)

    @Test
    fun `a plugin that only defines interfaces is a definer`() {
        assertTrue(plugin(defines = listOf(contract)).isInterfaceDefinerOnly)
    }

    @Test
    fun `providers and plugins with their own API are not`() {
        assertFalse(plugin().isInterfaceDefinerOnly)
        assertFalse(plugin(provides = listOf(InterfaceBinding("scale"))).isInterfaceDefinerOnly)
        assertFalse(plugin(defines = listOf(contract), provides = listOf(InterfaceBinding("scale"))).isInterfaceDefinerOnly)
        val own = DescriptorGroup(methods = listOf(MethodDescriptor("x.y", "", "x", exampleParameters = "{}", exampleOutput = "{}")))
        assertFalse(plugin(groups = listOf(own), defines = listOf(contract)).isInterfaceDefinerOnly)
    }
}
