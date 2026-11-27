package com.unraiddesk.app.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.unraiddesk.app.core.security.TofuTrustManager

@Database(
    entities = [
        ServerConfigEntity::class,
        CachedMetricSnapshotEntity::class,
        ShareMountEntity::class,
        DockerFolderEntity::class,
        DockerFolderMembershipEntity::class,
        PinnedCertEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class UnraidDeskDatabase : RoomDatabase() {
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun cachedMetricSnapshotDao(): CachedMetricSnapshotDao
    abstract fun shareMountDao(): ShareMountDao
    abstract fun dockerFolderDao(): DockerFolderDao
    abstract fun pinnedCertDao(): PinnedCertDao

    companion object {
        @Volatile
        private var instance: UnraidDeskDatabase? = null

        fun getInstance(context: Context): UnraidDeskDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UnraidDeskDatabase::class.java,
                    "unraiddesk.db"
                ).build().also { instance = it }
            }
    }
}

class RoomPinnedCertStore(
    private val dao: PinnedCertDao
) : TofuTrustManager.PinnedCertStore {
    override fun isPinned(host: String, sha256Fingerprint: String): Boolean {
        val normalized = host.trim().lowercase()
        val stored = dao.getByHostSync(normalized) ?: return false
        return stored.sha256Fingerprint == sha256Fingerprint
    }

    override fun pin(host: String, sha256Fingerprint: String) {
        val normalized = host.trim().lowercase()
        val existing = dao.getByHostSync(normalized)
        dao.upsertSync(
            PinnedCertEntity(
                host = normalized,
                sha256Fingerprint = sha256Fingerprint,
                issuer = existing?.issuer.orEmpty(),
                subject = existing?.subject.orEmpty(),
                notBeforeMillis = existing?.notBeforeMillis ?: 0L,
                notAfterMillis = existing?.notAfterMillis ?: 0L,
                pinnedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun pinCertInfo(info: TofuTrustManager.UntrustedCertInfo) {
        dao.upsert(
            PinnedCertEntity(
                host = info.host.trim().lowercase(),
                sha256Fingerprint = info.sha256Fingerprint,
                issuer = info.issuer,
                subject = info.subject,
                notBeforeMillis = info.notBeforeMillis,
                notAfterMillis = info.notAfterMillis,
                pinnedAtMillis = System.currentTimeMillis()
            )
        )
    }
}
