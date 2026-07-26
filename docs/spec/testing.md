# Strategia testów

## Podejście

Testy wplecione w etapy implementacji. Testujemy to, co jest trudne do
zdebugowania ręcznie. Nie testujemy UI, Manifest, lifecycle serwera, AIDL binding.

Framework: JUnit 5 + kotlinx-coroutines-test + Mockk (lub Mockito-Kotlin).
Testy lokalne (JVM), nie instrumentalne (Android) — szybsze, nie wymagają emulatora.

## Zależności testowe (per moduł)

```kotlin
// W każdym module który ma testy:
testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
testImplementation("io.mockk:mockk:1.13.10")
testImplementation("org.jetbrains.kotlin:kotlin-test")
```

Room (hal-service):
```kotlin
testImplementation("androidx.room:room-testing:2.6.1")
```

## Etap 2: hal-contract testy

### EventBus — pattern matching

```kotlin
class EventBusPatternTest {
    @Test fun `exact match`() {
        assertTrue(EventBus.matchesPattern("scanner.barcode", "scanner.barcode"))
        assertFalse(EventBus.matchesPattern("scanner.barcode", "scanner.stop"))
    }

    @Test fun `wildcard prefix`() {
        assertTrue(EventBus.matchesPattern("rfid.*", "rfid.tag"))
        assertTrue(EventBus.matchesPattern("rfid.*", "rfid.batch"))
        assertFalse(EventBus.matchesPattern("rfid.*", "scanner.barcode"))
    }

    @Test fun `global wildcard`() {
        assertTrue(EventBus.matchesPattern("*", "rfid.tag"))
        assertTrue(EventBus.matchesPattern("*", "scanner.barcode"))
    }

    @Test fun `vendor prefix wildcard`() {
        assertTrue(EventBus.matchesPattern("sunmi.scanner.*", "sunmi.scanner.barcode"))
        assertFalse(EventBus.matchesPattern("sunmi.scanner.*", "zebra.scanner.barcode"))
    }

    @Test fun `pattern does not partial match`() {
        assertFalse(EventBus.matchesPattern("scan", "scanner.barcode"))
        assertFalse(EventBus.matchesPattern("scanner", "scanner.barcode"))
    }
}
```

### EventBus — loop protection

```kotlin
class EventBusLoopProtectionTest {
    @Test fun `plugin does not receive own events`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<String>()

        bus.addPluginListener(
            listenerPluginId = "scanner",
            pattern = "*",
            callback = { event, _ -> received.add(event) }
        )

        bus.emit("scanner.barcode", "{}", sourcePluginId = "scanner")
        bus.emit("printer.done", "{}", sourcePluginId = "printer")

        assertEquals(listOf("printer.done"), received)
    }

    @Test fun `different plugin receives events`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<String>()

        bus.addPluginListener(
            listenerPluginId = "generic.scanner",
            pattern = "sunmi.scanner.*",
            callback = { event, _ -> received.add(event) }
        )

        bus.emit("sunmi.scanner.barcode", "{}", sourcePluginId = "sunmi.scanner")

        assertEquals(listOf("sunmi.scanner.barcode"), received)
    }
}
```

### EventBus — SharedFlow delivery

```kotlin
class EventBusFlowTest {
    @Test fun `events delivered to flow collectors`() = runTest {
        val bus = EventBus()
        val collected = mutableListOf<EventBus.EventEnvelope>()

        val job = launch {
            bus.events.take(2).toList(collected)
        }

        bus.emit("a", "{}", "p1")
        bus.emit("b", "{}", "p2")
        job.join()

        assertEquals(2, collected.size)
        assertEquals("a", collected[0].eventName)
        assertEquals("b", collected[1].eventName)
    }
}
```

## Etap 3: hal-service/auth testy

### TokenManager

```kotlin
class TokenManagerTest {
    // Użyj in-memory Room database
    private lateinit var db: TokenDatabase
    private lateinit var manager: TokenManager

    @BeforeEach
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, TokenDatabase::class.java)
            .allowMainThreadQueries().build()
        manager = TokenManager(db.tokenDao())
    }

    @Test fun `create and validate token`() = runTest {
        val token = manager.createToken(
            clientId = "test-app",
            permissions = listOf("printer"),
            grantedBy = "developer_key",
            duration = "permanent",
            boundPackageName = "com.test.app",
            boundCertHash = null,
            boundOrigin = null
        )

        val result = manager.validateToken(token.token, CallerContext(
            transport = "aidl", packageName = "com.test.app"
        ))
        assertNotNull(result)
        assertEquals("test-app", result!!.clientId)
    }

    @Test fun `token binding rejects wrong package`() = runTest {
        val token = manager.createToken(
            clientId = "test-app", permissions = listOf("printer"),
            grantedBy = "developer_key", duration = "permanent",
            boundPackageName = "com.test.app",
            boundCertHash = null, boundOrigin = null
        )

        val result = manager.validateToken(token.token, CallerContext(
            transport = "aidl", packageName = "com.evil.app"
        ))
        assertNull(result)
    }

    @Test fun `token binding rejects wrong origin`() = runTest {
        val token = manager.createToken(
            clientId = "web-app", permissions = listOf("scanner"),
            grantedBy = "user_permanent", duration = "permanent",
            boundPackageName = null, boundCertHash = null,
            boundOrigin = "https://myapp.com"
        )

        val result = manager.validateToken(token.token, CallerContext(
            transport = "ws", origin = "https://evil.com"
        ))
        assertNull(result)
    }

    @Test fun `expired token is rejected`() = runTest {
        val token = manager.createToken(
            clientId = "test", permissions = listOf("printer"),
            grantedBy = "user_day", duration = "day",
            boundPackageName = null, boundCertHash = null, boundOrigin = null
        )

        // Ręcznie ustaw expiresAt w przeszłości
        db.tokenDao().updateExpiry(token.token, System.currentTimeMillis() - 1000)

        val result = manager.validateToken(token.token, CallerContext(transport = "http"))
        assertNull(result)
    }

    @Test fun `revoke token`() = runTest {
        val token = manager.createToken(
            clientId = "test", permissions = listOf("printer"),
            grantedBy = "developer_key", duration = "permanent",
            boundPackageName = null, boundCertHash = null, boundOrigin = null
        )

        manager.revokeToken(token.token)

        val result = manager.validateToken(token.token, CallerContext(transport = "http"))
        assertNull(result)
    }

    @Test fun `unrestricted token works from any context`() = runTest {
        val token = manager.createToken(
            clientId = "unrestricted", permissions = listOf("printer"),
            grantedBy = "developer_key", duration = "permanent",
            boundPackageName = null, boundCertHash = null, boundOrigin = null
        )

        // Działa z dowolnego kontekstu
        assertNotNull(manager.validateToken(token.token, CallerContext(
            transport = "aidl", packageName = "com.any.app"
        )))
        assertNotNull(manager.validateToken(token.token, CallerContext(
            transport = "ws", origin = "https://any.com"
        )))
    }
}
```

### ServiceKeyVerifier

```kotlin
class ServiceKeyVerifierTest {
    private lateinit var verifier: ServiceKeyVerifier
    // Wygenerowana testowa para kluczy (ta sama co wkompilowana w APK)

    @Test fun `valid JWT returns claims`() {
        val jwt = createTestJwt(
            permissions = listOf("printer", "scanner"),
            clientType = "android",
            packageName = "com.test.app"
        )
        val result = verifier.verify(jwt, CallerContext(
            transport = "aidl", packageName = "com.test.app"
        ))
        assertNotNull(result)
        assertEquals(listOf("printer", "scanner"), result!!.permissions)
    }

    @Test fun `expired JWT returns error`() {
        val jwt = createTestJwt(exp = pastTimestamp)
        val result = verifier.verify(jwt, CallerContext(transport = "aidl"))
        assertNull(result)
        // lub: assertEquals(ServiceKeyError.EXPIRED, error)
    }

    @Test fun `wrong signature returns error`() {
        val jwt = createJwtWithWrongKey(permissions = listOf("printer"))
        val result = verifier.verify(jwt, CallerContext(transport = "aidl"))
        assertNull(result)
    }

    @Test fun `restriction mismatch returns error`() {
        val jwt = createTestJwt(
            clientType = "android", packageName = "com.expected.app"
        )
        val result = verifier.verify(jwt, CallerContext(
            transport = "aidl", packageName = "com.wrong.app"
        ))
        assertNull(result)
    }

    @Test fun `unrestricted JWT works from any context`() {
        val jwt = createTestJwt(clientType = "unrestricted")
        val result = verifier.verify(jwt, CallerContext(
            transport = "ws", origin = "https://anything.com"
        ))
        assertNotNull(result)
    }

    @Test fun `web JWT checks origin`() {
        val jwt = createTestJwt(
            clientType = "web", origins = listOf("https://myapp.com")
        )

        assertNotNull(verifier.verify(jwt, CallerContext(
            transport = "http", origin = "https://myapp.com"
        )))
        assertNull(verifier.verify(jwt, CallerContext(
            transport = "http", origin = "https://evil.com"
        )))
    }
}
```

### AuthManager — flow orchestration

```kotlin
class AuthManagerTest {
    // Mock: tokenManager, serviceKeyVerifier, grantPermissionLauncher

    @Test fun `valid serviceKey creates token with JWT permissions`() = runTest {
        every { verifier.verify(any(), any()) } returns ServiceKeyClaims(
            permissions = listOf("printer"), clientType = "android"
        )
        val result = authManager.requestToken(
            TokenRequest(serviceKey = "valid-jwt", clientId = "app"),
            CallerContext(transport = "aidl", packageName = "com.test")
        )
        assertTrue(result is TokenResponse.Success)
        assertEquals(listOf("printer"), (result as TokenResponse.Success).permissions)
    }

    @Test fun `invalid serviceKey returns error without dialog`() = runTest {
        every { verifier.verify(any(), any()) } returns null

        val result = authManager.requestToken(
            TokenRequest(serviceKey = "bad-jwt", clientId = "app"),
            CallerContext(transport = "aidl")
        )
        assertTrue(result is TokenResponse.Error)
        // Verify dialog was NOT shown
        verify(exactly = 0) { grantPermissionLauncher.launch(any()) }
    }

    @Test fun `no serviceKey shows dialog`() = runTest {
        coEvery { showGrantDialog(any(), any()) } returns GrantDecision.AllowPermanent

        val result = authManager.requestToken(
            TokenRequest(serviceKey = null, clientId = "app"),
            CallerContext(transport = "aidl")
        )
        assertTrue(result is TokenResponse.Success)
        coVerify { showGrantDialog(any(), any()) }
    }
}
```

## Etap 4: transport testy

### WS protocol — serialization

```kotlin
class WsProtocolTest {
    @Test fun `parse requestToken message`() {
        val json = """{"id":"1","type":"requestToken","clientId":"app","serviceKey":"jwt"}"""
        val msg = WsProtocol.parse(json)
        assertTrue(msg is WsMessage.RequestToken)
        assertEquals("1", msg.id)
        assertEquals("app", (msg as WsMessage.RequestToken).clientId)
    }

    @Test fun `parse command message`() {
        val json = """{"id":"2","type":"command","method":"printer.print","params":{"x":1}}"""
        val msg = WsProtocol.parse(json)
        assertTrue(msg is WsMessage.Command)
        assertEquals("printer.print", (msg as WsMessage.Command).method)
    }

    @Test fun `parse subscribe with wildcards`() {
        val json = """{"id":"3","type":"subscribe","events":["scanner.barcode","rfid.*"]}"""
        val msg = WsProtocol.parse(json)
        assertTrue(msg is WsMessage.Subscribe)
        assertEquals(listOf("scanner.barcode", "rfid.*"), (msg as WsMessage.Subscribe).events)
    }

    @Test fun `serialize response`() {
        val json = WsProtocol.serializeResponse("1", """{"status":"ok"}""")
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("1", parsed["id"]?.jsonPrimitive?.content)
        assertEquals("response", parsed["type"]?.jsonPrimitive?.content)
    }

    @Test fun `serialize event`() {
        val json = WsProtocol.serializeEvent("rfid.tag", """{"epc":"E200"}""")
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("event", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("rfid.tag", parsed["event"]?.jsonPrimitive?.content)
    }

    @Test fun `invalid json returns parse error`() {
        val msg = WsProtocol.parse("not json")
        assertTrue(msg is WsMessage.ParseError)
    }
}
```

### Subscription filtering

```kotlin
class SubscriptionFilterTest {
    @Test fun `event matches exact subscription`() {
        val subs = setOf("scanner.barcode")
        assertTrue(matchesAnySubscription(subs, "scanner.barcode"))
        assertFalse(matchesAnySubscription(subs, "rfid.tag"))
    }

    @Test fun `event matches wildcard subscription`() {
        val subs = setOf("rfid.*")
        assertTrue(matchesAnySubscription(subs, "rfid.tag"))
        assertTrue(matchesAnySubscription(subs, "rfid.batch"))
        assertFalse(matchesAnySubscription(subs, "scanner.barcode"))
    }

    @Test fun `event matches global wildcard`() {
        val subs = setOf("*")
        assertTrue(matchesAnySubscription(subs, "anything.here"))
    }

    @Test fun `subscribe checks permissions`() {
        val tokenPermissions = listOf("printer", "scanner")
        val events = listOf("scanner.barcode", "rfid.*")

        val result = validateSubscriptions(events, tokenPermissions)
        assertEquals(listOf("scanner.barcode"), result.allowed)
        assertEquals(listOf("rfid.*"), result.denied)  // Brak uprawnienia "rfid"
    }
}
```

## Etap 5: interfejsy (rejestr)

Interfejsy (`printer`, `barcodeScanner`, `light`, …) testuje sie na poziomie `PluginRegistry` -- rejestracja
kontraktu, rozwiazanie providera (domyslny vs `__provider`), bramkowanie cech method-level, filtr
dostepnosci. Pelny zestaw: `service/hal-service/.../plugin/PluginRegistryInterfaceTest.kt`.

### Rozwiazanie providera + bramkowanie cechy

```kotlin
@Test fun `printer resolves to sunmi provider and feature-gates zpl`() = runTest {
    val registry = PluginRegistry()
    registry.registerBuiltIn(PrinterInterface())                 // definer rejestruje kontrakt
    registry.registerBuiltIn(
        FakeProvider("sunmi.printerx.printer",
            InterfaceBinding("printer", priority = 100, features = listOf("escpos", "tspl", "image", "cut")))
    )

    // Domyslny provider -> najwyzszy priorytet, zwracany w naglowku odpowiedzi:
    val escpos = registry.executeInterface("printer", null, "printer.printEscPos", "{}")
    assertEquals("sunmi.printerx.printer", (escpos as CommandResult.Success).provider)

    // Zaden provider nie ma cechy `zpl` -> metoda odrzucona przez rdzen:
    val zpl = registry.executeInterface("printer", null, "printer.printZpl", "{}")
    assertEquals("unavailable", (zpl as CommandResult.Failure).code)
}
```

### Brama rejestracji (wymog 3)

```kotlin
@Test fun `unregistered interface is not callable even with a provider`() = runTest {
    val registry = PluginRegistry()
    // Provider obecny, ale zaden definer nie zarejestrowal kontraktu.
    registry.registerBuiltIn(FakeProvider("sunmi.scanner.inner", InterfaceBinding("scanner", priority = 100)))
    assertNull(registry.interfaceIdForMethod("barcodeScanner.trigger"))
    assertTrue(registry.executeInterface("barcodeScanner", null, "barcodeScanner.trigger", "{}") is CommandResult.Failure)
}
```

Providerzy sprzetowi (Sunmi) sa weryfikowani przez CI (oba flavory); test rejestru z `FakeProvider`
o tym samym `pluginId` sprawdza binding i routing bez realnego SDK. Eventy interfejsu (`barcodeScanner.onScan`
z `source`) i subskrypcje `@source` -- patrz sekcja transportow/EventBus.

## Co NIE testować

- DashboardActivity UI (testuj ręcznie)
- AndroidManifest configuration
- Ktor server lifecycle (start/stop)
- AIDL binding/unbinding (wymaga instrumented test)
- BootReceiver
- GrantPermissionActivity UI (testuj ręcznie)
- Foreground notification
