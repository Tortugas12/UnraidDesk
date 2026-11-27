package com.unraiddesk.app.widget

import com.unraiddesk.app.core.data.db.CachedMetricSnapshotDao
import com.unraiddesk.app.core.data.db.CachedMetricSnapshotEntity
import com.unraiddesk.app.core.data.db.ServerConfigDao
import com.unraiddesk.app.core.data.db.ServerConfigEntity
import com.unraiddesk.app.core.network.UnraidGraphQlClient
import com.unraiddesk.app.core.security.KeystoreCryptoManager
import com.unraiddesk.app.core.security.LanVpnGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.InetAddress
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class WidgetSyncWorkerTest {

    private class TestKeyProvider : KeystoreCryptoManager.SecretKeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun getOrCreateKey(): SecretKey = key
    }

    private class FakeServerDao(private val server: ServerConfigEntity?) : ServerConfigDao {
        private val flow = MutableStateFlow(server)
        override fun observePrimaryServer(): Flow<ServerConfigEntity?> = flow
        override suspend fun getPrimaryServer(): ServerConfigEntity? = server
        override suspend fun upsert(config: ServerConfigEntity): Long = config.id
        override suspend fun deleteAll() = Unit
    }

    private class FakeSnapshotDao : CachedMetricSnapshotDao {
        var stored: CachedMetricSnapshotEntity? = null
        private val flow = MutableStateFlow<CachedMetricSnapshotEntity?>(null)
        override fun observeLatest(serverId: Long): Flow<CachedMetricSnapshotEntity?> = flow
        override suspend fun getLatest(serverId: Long): CachedMetricSnapshotEntity? = stored
        override suspend fun upsert(snapshot: CachedMetricSnapshotEntity) {
            stored = snapshot
            flow.value = snapshot
        }
    }

    @Test
    fun syncOnceBlocksPublicHostWhenOffLanAndVpnInactive() = runTest {
        val crypto = KeystoreCryptoManager(TestKeyProvider())
        val server = ServerConfigEntity(
            id = 1L,
            name = "Public",
            host = "8.8.8.8",
            port = 443,
            useHttps = true,
            encryptedApiKey = crypto.encryptString("key"),
            createdAtMillis = 0L,
            updatedAtMillis = 0L
        )
        val guard = LanVpnGuard(
            dnsResolver = { host -> listOf(InetAddress.getByName(host)) },
            vpnStatusProvider = { false }
        )
        val snapshotDao = FakeSnapshotDao()
        val result = WidgetSyncWorker.syncOnce(
            serverConfigDao = FakeServerDao(server),
            snapshotDao = snapshotDao,
            lanVpnGuard = guard,
            cryptoManager = crypto,
            graphQlClient = UnraidGraphQlClient(OkHttpClient())
        )

        assertEquals(WidgetSyncWorker.SyncOutcome.BLOCKED_OFF_LAN, result.outcome)
    }

    @Test
    fun syncOnceUpdatesRoomSnapshotOnLan() = runTest {
        val mockServer = MockWebServer()
        mockServer.start()
        try {
            mockServer.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "data": {
                        "info": { "os": { "distro": "Unraid", "release": "7.4" }, "cpu": { "cores": 8 } },
                        "metrics": { "cpu": { "percentTotal": 22.0 }, "memory": { "percentTotal": 55.0, "used": 100, "total": 200 } },
                        "array": { "state": "STARTED", "capacity": { "kilobytes": { "free": 5000, "used": 5000, "total": 10000 } }, "parityCheckStatus": { "status": "IDLE", "progress": 0.0 }, "disks": [], "caches": [] }
                      }
                    }
                    """.trimIndent()
                )
            )
            mockServer.enqueue(MockResponse().setBody("""{"data":{"shares":[{"name":"media","size":100,"free":50,"used":50}]}}"""))
            mockServer.enqueue(MockResponse().setBody("""{"data":{"docker":{"containers":[{"id":"c1","names":["/plex"],"state":"RUNNING","ports":[],"labels":{}}]}}}"""))

            val crypto = KeystoreCryptoManager(TestKeyProvider())
            val server = ServerConfigEntity(
                id = 1L,
                name = "Tower",
                host = mockServer.hostName,
                port = mockServer.port,
                useHttps = false,
                allowInsecureHttp = true,
                encryptedApiKey = crypto.encryptString("api-key"),
                createdAtMillis = 0L,
                updatedAtMillis = 0L
            )
            val guard = LanVpnGuard(
                dnsResolver = { _ -> listOf(InetAddress.getByName("127.0.0.1")) },
                vpnStatusProvider = { false }
            )
            val snapshotDao = FakeSnapshotDao()
            val result = WidgetSyncWorker.syncOnce(
                serverConfigDao = FakeServerDao(server),
                snapshotDao = snapshotDao,
                lanVpnGuard = guard,
                cryptoManager = crypto,
                graphQlClient = UnraidGraphQlClient(OkHttpClient())
            )

            assertEquals(WidgetSyncWorker.SyncOutcome.SYNCED, result.outcome)
            assertNotNull(snapshotDao.stored)
            assertEquals(22.0, snapshotDao.stored!!.cpuPercent, 0.01)
            assertEquals("STARTED", snapshotDao.stored!!.arrayState)
            assertEquals(5000L * 1024L, snapshotDao.stored!!.arrayUsedBytes)
            assertEquals(10000L * 1024L, snapshotDao.stored!!.arrayTotalBytes)
        } finally {
            mockServer.shutdown()
        }
    }
}
