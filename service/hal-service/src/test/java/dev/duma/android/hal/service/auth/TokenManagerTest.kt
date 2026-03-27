package dev.duma.android.hal.service.auth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.duma.android.hal.transport.core.CallerContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*

/**
 * Tests for [TokenManager] — token creation, validation with binding checks,
 * expiration, and revocation. Uses Robolectric with in-memory Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenManagerTest {

    private lateinit var db: TokenDatabase
    private lateinit var manager: TokenManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TokenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = TokenManager(db.tokenDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `create and validate token`() = runTest {
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

    @Test
    fun `token binding rejects wrong package`() = runTest {
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
            transport = "aidl", packageName = "com.evil.app"
        ))
        assertNull(result)
    }

    @Test
    fun `token binding rejects wrong origin`() = runTest {
        val token = manager.createToken(
            clientId = "web-app",
            permissions = listOf("scanner"),
            grantedBy = "user_permanent",
            duration = "permanent",
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = "https://myapp.com"
        )

        val result = manager.validateToken(token.token, CallerContext(
            transport = "ws", origin = "https://evil.com"
        ))
        assertNull(result)
    }

    @Test
    fun `expired token is rejected`() = runTest {
        val token = manager.createToken(
            clientId = "test",
            permissions = listOf("printer"),
            grantedBy = "user_day",
            duration = "day",
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )

        db.tokenDao().updateExpiry(token.token, System.currentTimeMillis() - 1000)

        val result = manager.validateToken(token.token, CallerContext(transport = "http"))
        assertNull(result)
    }

    @Test
    fun `revoke token`() = runTest {
        val token = manager.createToken(
            clientId = "test",
            permissions = listOf("printer"),
            grantedBy = "developer_key",
            duration = "permanent",
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )

        manager.revokeToken(token.token)

        val result = manager.validateToken(token.token, CallerContext(transport = "http"))
        assertNull(result)
    }

    @Test
    fun `unrestricted token works from any context`() = runTest {
        val token = manager.createToken(
            clientId = "unrestricted",
            permissions = listOf("printer"),
            grantedBy = "developer_key",
            duration = "permanent",
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )

        assertNotNull(manager.validateToken(token.token, CallerContext(
            transport = "aidl", packageName = "com.any.app"
        )))
        assertNotNull(manager.validateToken(token.token, CallerContext(
            transport = "ws", origin = "https://any.com"
        )))
    }

    @Test
    fun `findExistingToken returns matching token`() = runTest {
        val token = manager.createToken(
            clientId = "test-app",
            permissions = listOf("printer"),
            grantedBy = "user_permanent",
            duration = "permanent",
            boundPackageName = "com.test.app",
            boundCertHash = null,
            boundOrigin = null
        )

        val result = manager.findExistingToken(
            clientId = "test-app",
            requiredPermissions = listOf("printer"),
            boundPackageName = "com.test.app",
            boundCertHash = null,
            boundOrigin = null
        )
        assertNotNull(result)
        assertEquals(token.token, result!!.token)
    }

    @Test
    fun `findExistingToken returns null when no match`() = runTest {
        val result = manager.findExistingToken(
            clientId = "nonexistent",
            requiredPermissions = listOf("printer"),
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )
        assertNull(result)
    }

    @Test
    fun `findExistingToken ignores expired tokens`() = runTest {
        val token = manager.createToken(
            clientId = "test",
            permissions = listOf("printer"),
            grantedBy = "user_day",
            duration = "day",
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )

        db.tokenDao().updateExpiry(token.token, System.currentTimeMillis() - 1000)

        val result = manager.findExistingToken(
            clientId = "test",
            requiredPermissions = listOf("printer"),
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )
        assertNull(result)
    }

    @Test
    fun `findExistingToken with wildcard covers specific request`() = runTest {
        manager.createToken(
            clientId = "test",
            permissions = listOf("*"),
            grantedBy = "user_permanent",
            duration = "permanent",
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )

        val result = manager.findExistingToken(
            clientId = "test",
            requiredPermissions = listOf("printer"),
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )
        assertNotNull(result)
    }

    @Test
    fun `findExistingToken with subset permissions returns null`() = runTest {
        manager.createToken(
            clientId = "test",
            permissions = listOf("printer"),
            grantedBy = "user_permanent",
            duration = "permanent",
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )

        val result = manager.findExistingToken(
            clientId = "test",
            requiredPermissions = listOf("printer", "scanner"),
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )
        assertNull(result)
    }

    @Test
    fun `findExistingToken with superset permissions returns match`() = runTest {
        manager.createToken(
            clientId = "test",
            permissions = listOf("printer", "scanner"),
            grantedBy = "user_permanent",
            duration = "permanent",
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )

        val result = manager.findExistingToken(
            clientId = "test",
            requiredPermissions = listOf("printer"),
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )
        assertNotNull(result)
    }

    @Test
    fun `findExistingToken rejects wrong binding`() = runTest {
        manager.createToken(
            clientId = "test",
            permissions = listOf("printer"),
            grantedBy = "user_permanent",
            duration = "permanent",
            boundPackageName = "com.test.app",
            boundCertHash = null,
            boundOrigin = null
        )

        val result = manager.findExistingToken(
            clientId = "test",
            requiredPermissions = listOf("printer"),
            boundPackageName = "com.other.app",
            boundCertHash = null,
            boundOrigin = null
        )
        assertNull(result)
    }

    @Test
    fun `findExistingToken with prefix permission matching`() = runTest {
        manager.createToken(
            clientId = "test",
            permissions = listOf("printer"),
            grantedBy = "user_permanent",
            duration = "permanent",
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )

        val result = manager.findExistingToken(
            clientId = "test",
            requiredPermissions = listOf("printer.status"),
            boundPackageName = null,
            boundCertHash = null,
            boundOrigin = null
        )
        assertNotNull(result)
    }
}
