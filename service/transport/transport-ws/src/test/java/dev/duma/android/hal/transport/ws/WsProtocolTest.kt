package dev.duma.android.hal.transport.ws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for [WsProtocol] — parsing client messages, serializing server responses/events,
 * and handling invalid input.
 */
class WsProtocolTest {
    @Test
    fun `parse requestToken message`() {
        val json = """{"id":"1","type":"requestToken","clientId":"app","serviceKey":"jwt"}"""
        val msg = WsProtocol.parse(json)
        assertIs<WsMessage.RequestToken>(msg)
        assertEquals("1", msg.id)
        assertEquals("app", msg.clientId)
        assertEquals("jwt", msg.serviceKey)
    }

    @Test
    fun `parse command message`() {
        val json = """{"id":"2","type":"command","method":"printer.print","params":{"x":1}}"""
        val msg = WsProtocol.parse(json)
        assertIs<WsMessage.Command>(msg)
        assertEquals("printer.print", msg.method)
    }

    @Test
    fun `parse subscribe with wildcards`() {
        val json = """{"id":"3","type":"subscribe","events":["scanner.barcode","rfid.*"]}"""
        val msg = WsProtocol.parse(json)
        assertIs<WsMessage.Subscribe>(msg)
        assertEquals(listOf("scanner.barcode", "rfid.*"), msg.events)
    }

    @Test
    fun `serialize response`() {
        val json = WsProtocol.serializeResponse("1", """{"status":"ok"}""")
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("1", parsed["id"]?.jsonPrimitive?.content)
        assertEquals("response", parsed["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `serialize event`() {
        val json = WsProtocol.serializeEvent("rfid.tag", """{"epc":"E200"}""", "sunmi.rfid")
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("event", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("rfid.tag", parsed["event"]?.jsonPrimitive?.content)
        // Emitting plugin id is exposed in the frame header, not inside `data`.
        assertEquals("sunmi.rfid", parsed["source"]?.jsonPrimitive?.content)
    }

    @Test
    fun `invalid json returns parse error`() {
        val msg = WsProtocol.parse("not json")
        assertIs<WsMessage.ParseError>(msg)
    }
}
