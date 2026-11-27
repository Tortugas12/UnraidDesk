package com.unraiddesk.app.ui.onboarding

import com.unraiddesk.app.core.data.db.ServerConfigDao
import com.unraiddesk.app.core.data.db.ServerConfigEntity
import com.unraiddesk.app.core.data.db.ShareMountDao
import com.unraiddesk.app.core.data.db.ShareMountEntity
import com.unraiddesk.app.core.network.DockerRootVariant
import com.unraiddesk.app.core.network.UnraidShare
import com.unraiddesk.app.core.security.KeystoreCryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class OnboardingPersistenceTest {

    private class TestKeyProvider : KeystoreCryptoManager.SecretKeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun getOrCreateKey(): SecretKey = key
    }

    private class FakeServerConfigDao : ServerConfigDao {
        private val state = MutableStateFlow<ServerConfigEntity?>(null)
        override fun observePrimaryServer(): Flow<ServerConfigEntity?> = state
        override suspend fun getPrimaryServer(): ServerConfigEntity? = state.value
        override suspend fun upsert(config: ServerConfigEntity): Long {
            state.value = config
            return config.id
        }
        override suspend fun deleteAll() {
            state.value = null
        }
    }

    private class FakeShareMountDao : ShareMountDao {
        private val shares = mutableListOf<ShareMountEntity>()
        private val flow = MutableStateFlow<List<ShareMountEntity>>(emptyList())

        override fun observeForServer(serverId: Long): Flow<List<ShareMountEntity>> = flow
        override suspend fun getForServer(serverId: Long): List<ShareMountEntity> =
            shares.filter { it.serverId == serverId }

        override suspend fun upsertAll(shares: List<ShareMountEntity>) {
            this.shares.addAll(shares)
            flow.value = this.shares.toList()
        }

        override suspend fun updateMonitored(id: Long, monitored: Boolean) = Unit
        override suspend fun deleteForServer(serverId: Long) {
            shares.removeAll { it.serverId == serverId }
            flow.value = shares.toList()
        }
    }

    @Test
    fun persistOnboardingConfigEncryptsSecretsAndPopulatesShareMounts() = runTest {
        val serverDao = FakeServerConfigDao()
        val shareDao = FakeShareMountDao()
        val cryptoManager = KeystoreCryptoManager(TestKeyProvider())

        val rawApiKey = "unraid-secret-api-key-9876"
        val rawSmbUser = "nasuser"
        val rawSmbPass = "super-secret-smb-pass"

        val shares = listOf(
            UnraidShare(name = "appdata", comment = "Docker appdata", sizeBytes = 1000L, freeBytes = 400L, usedBytes = 600L),
            UnraidShare(name = "media", comment = "Media library", sizeBytes = 5000L, freeBytes = 2000L, usedBytes = 3000L)
        )

        persistOnboardingConfig(
            serverConfigDao = serverDao,
            shareMountDao = shareDao,
            cryptoManager = cryptoManager,
            serverName = "Tower",
            host = "192.168.1.10",
            port = 443,
            useHttps = true,
            apiKey = rawApiKey,
            dockerRootVariant = DockerRootVariant.DOCKER_OBJ,
            shares = shares,
            selectedShareNames = setOf("media"),
            smbUsername = rawSmbUser,
            smbPassword = rawSmbPass,
            smbDomain = "WORKGROUP"
        )

        val storedServer = serverDao.getPrimaryServer()
        assertNotNull(storedServer)
        storedServer!!

        assertFalse(storedServer.encryptedApiKey == rawApiKey)
        assertFalse(storedServer.encryptedSmbUsername == rawSmbUser)
        assertFalse(storedServer.encryptedSmbPassword == rawSmbPass)

        assertEquals(rawApiKey, cryptoManager.decryptString(storedServer.encryptedApiKey))
        assertEquals(rawSmbUser, cryptoManager.decryptString(storedServer.encryptedSmbUsername!!))
        assertEquals(rawSmbPass, cryptoManager.decryptString(storedServer.encryptedSmbPassword!!))

        val storedShares = shareDao.getForServer(1L)
        assertEquals(2, storedShares.size)
        val appdata = storedShares.first { it.shareName == "appdata" }
        val media = storedShares.first { it.shareName == "media" }
        assertFalse(appdata.isMonitored)
        assertTrue(media.isMonitored)
    }
}
