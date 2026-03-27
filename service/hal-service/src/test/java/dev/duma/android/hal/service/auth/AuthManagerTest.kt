package dev.duma.android.hal.service.auth

import dev.duma.android.hal.transport.core.CallerContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for [AuthManager] — orchestration of the requestToken flow:
 * developer key verification, user consent dialog, and token creation.
 */
class AuthManagerTest {

    private val tokenManager = mockk<TokenManager>()
    private val verifier = mockk<DeveloperKeyVerifier>()
    private var dialogDecision: GrantDecision = GrantDecision.AllowPermanent
    private var dialogCalled = false

    private val authManager = AuthManager(
        tokenManager = tokenManager,
        developerKeyVerifier = verifier,
        showGrantDialog = { _, _ ->
            dialogCalled = true
            dialogDecision
        }
    )

    private val testTokenEntity = TokenEntity(
        id = 1,
        token = "test-token-hex",
        clientId = "app",
        clientType = "android",
        permissions = "printer",
        grantedBy = "developer_key",
        grantedAt = System.currentTimeMillis(),
        expiresAt = null,
        boundPackageName = "com.test",
        boundCertHash = null,
        boundOrigin = null
    )

    @Test
    fun `valid devKey creates token with JWT permissions`() = runTest {
        every { verifier.verify(any(), any()) } returns VerificationResult.Success(
            DevKeyClaims(permissions = listOf("printer"), clientType = "android")
        )
        coEvery { tokenManager.findExistingToken(any(), any(), any(), any(), any()) } returns null
        coEvery { tokenManager.createToken(any(), any(), any(), any(), any(), any(), any()) } returns testTokenEntity

        val result = authManager.requestToken(
            TokenRequest(developerKey = "valid-jwt", clientId = "app"),
            CallerContext(transport = "aidl", packageName = "com.test")
        )

        assertIs<TokenResponse.Success>(result)
        assertEquals("test-token-hex", result.token)
    }

    @Test
    fun `invalid devKey returns error without dialog`() = runTest {
        dialogCalled = false
        every { verifier.verify(any(), any()) } returns VerificationResult.Error(DeveloperKeyError.INVALID_SIGNATURE)

        val result = authManager.requestToken(
            TokenRequest(developerKey = "bad-jwt", clientId = "app"),
            CallerContext(transport = "aidl")
        )

        assertIs<TokenResponse.Error>(result)
        assertEquals("invalid_developer_key", result.code)
        assertEquals(false, dialogCalled)
    }

    @Test
    fun `no devKey shows dialog when no existing token`() = runTest {
        dialogCalled = false
        dialogDecision = GrantDecision.AllowPermanent
        coEvery { tokenManager.findExistingToken(any(), any(), any(), any(), any()) } returns null
        coEvery { tokenManager.createToken(any(), any(), any(), any(), any(), any(), any()) } returns testTokenEntity

        val result = authManager.requestToken(
            TokenRequest(developerKey = null, clientId = "app"),
            CallerContext(transport = "aidl")
        )

        assertIs<TokenResponse.Success>(result)
        assertEquals(true, dialogCalled)
    }

    @Test
    fun `existing token skips dialog for user grant`() = runTest {
        dialogCalled = false
        coEvery { tokenManager.findExistingToken(any(), any(), any(), any(), any()) } returns testTokenEntity

        val result = authManager.requestToken(
            TokenRequest(developerKey = null, clientId = "app"),
            CallerContext(transport = "aidl")
        )

        assertIs<TokenResponse.Success>(result)
        assertEquals("test-token-hex", result.token)
        assertEquals(false, dialogCalled)
    }

    @Test
    fun `existing token skips createToken for devKey`() = runTest {
        every { verifier.verify(any(), any()) } returns VerificationResult.Success(
            DevKeyClaims(permissions = listOf("printer"), clientType = "android")
        )
        coEvery { tokenManager.findExistingToken(any(), any(), any(), any(), any()) } returns testTokenEntity

        val result = authManager.requestToken(
            TokenRequest(developerKey = "valid-jwt", clientId = "app"),
            CallerContext(transport = "aidl", packageName = "com.test")
        )

        assertIs<TokenResponse.Success>(result)
        assertEquals("test-token-hex", result.token)
        coVerify(exactly = 0) { tokenManager.createToken(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid devKey returns error even if matching token exists`() = runTest {
        every { verifier.verify(any(), any()) } returns VerificationResult.Error(DeveloperKeyError.INVALID_SIGNATURE)

        val result = authManager.requestToken(
            TokenRequest(developerKey = "bad-jwt", clientId = "app"),
            CallerContext(transport = "aidl")
        )

        assertIs<TokenResponse.Error>(result)
        assertEquals("invalid_developer_key", result.code)
        coVerify(exactly = 0) { tokenManager.findExistingToken(any(), any(), any(), any(), any()) }
    }
}
