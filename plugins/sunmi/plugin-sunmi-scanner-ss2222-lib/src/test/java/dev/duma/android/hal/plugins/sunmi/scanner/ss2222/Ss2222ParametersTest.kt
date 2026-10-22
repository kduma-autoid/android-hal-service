package dev.duma.android.hal.plugins.sunmi.scanner.ss2222

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Ss2222ParametersTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun `named configuration maps only the verified symbologies and sound settings`() {
        assertEquals(mapOf(0xF024 to 1, 0x03 to 0, 0x38 to 0, 0x8C to 2), Ss2222Parameters.config(json(
            """{"symbologies":{"DATA_MATRIX":true,"EAN13":false},"goodReadBeep":false,"beepVolume":"low"}"""
        )))
        assertEquals(14, Ss2222Parameters.symbologies.size)
        assertTrue(0x09 !in Ss2222Parameters.configurableIds) // Code93 never decoded on the device.
        assertTrue(0x0F in Ss2222Parameters.configurableIds) // PDF417 includes the observed MicroPDF417 support.
    }

    @Test fun `wrong types unknown options and irreversible settings are rejected`() {
        for (input in listOf(
            """{"goodReadBeep":"false"}""", """{"beepVolume":2}""",
            """{"symbologies":{"AZTEC":true}}""", """{"symbologies":[]}""",
            """{"goodReadBeep":true,"permanent":true}""", "{}",
        )) assertFailsWith<IllegalArgumentException>(input) { Ss2222Parameters.config(json(input)) }
        for (id in listOf(0xEB, 0xF303, 0x8A, 0xF202, 0x69, 0x68, 0x6A)) {
            assertFailsWith<IllegalArgumentException> { Ss2222Parameters.validateWrites(mapOf(id to 0)) }
        }
    }

    @Test fun `numeric diagnostic writes cannot bypass named configuration policy`() {
        assertEquals(mapOf(0x38 to 0, 0x8C to 3), Ss2222Parameters.parameters(json(
            """{"parameters":[{"id":56,"value":0},{"id":140,"value":3}]}"""
        )))
        for (input in listOf(
            """{"parameters":[{"id":56,"value":2}]}""",
            """{"parameters":[{"id":56.5,"value":0}]}""",
            """{"parameters":[{"id":"56","value":0}]}""",
            """{"parameters":[{"id":56,"value":1},{"id":56,"value":0}]}""",
            """{"parameters":[{"id":56,"value":0,"permanent":true}]}""",
            """{"parameters":[{"id":56,"value":0}],"status":8}""",
        )) assertFailsWith<IllegalArgumentException>(input) { Ss2222Parameters.parameters(json(input)) }
    }

    @Test fun `raw SSI shares the policy and refuses destructive commands before any IO`() {
        assertEquals(mapOf(0xF024 to 1, 0x38 to 0), Ss2222Parameters.raw(json(
            """{"opcode":198,"data":"FF F0 24 01 38 00"}"""
        )).writes)
        assertEquals(0xF303, Ss2222Parameters.raw(json("""{"opcode":199,"data":"F3 03"}""")).parameter)
        for (input in listOf(
            """{"opcode":200}""", // PARAM_DEFAULTS
            """{"opcode":231,"data":"01"}""", // Host-controlled illumination
            """{"opcode":198,"data":"FF 8A 0C"}""", // USB identity change
            """{"opcode":198,"data":"FF F3 03 00"}""", // Cannot restore factory value
            """{"opcode":198,"data":"FF EB 00"}""", // Hidden terminator lost
            """{"opcode":198,"data":"FF 38 00","status":8}""",
            """{"opcode":198,"data":"FF 38 00 38 01"}""",
            """{"opcode":198,"data":"FF F0 24"}""",
            """{"opcode":199,"data":"03 04"}""",
            """{"opcode":199,"data":"FE"}""",
            """{"opcode":230,"data":"02"}""",
            """{"opcode":233,"data":"00"}""",
            """{"opcode":163,"data":"0Z"}""",
        )) assertFailsWith<IllegalArgumentException>(input) { Ss2222Parameters.raw(json(input)) }
    }

    @Test fun `read whitelist excludes empty sweep responses and accepts extended identified parameters`() {
        assertEquals(336, Ss2222ReadParameters.ids.size)
        assertEquals(0xF488, Ss2222Parameters.readable(0xF488))
        for (id in listOf(-1, 0xFE, 0xF2C4, 0xF2E7, 0xF2FB, 0x10000)) {
            assertFailsWith<IllegalArgumentException> { Ss2222Parameters.readable(id) }
        }
    }
}
