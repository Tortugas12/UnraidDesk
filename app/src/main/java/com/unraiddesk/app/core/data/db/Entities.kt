package com.unraiddesk.app.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "server_configs")
data class ServerConfigEntity(
    @PrimaryKey val id: Long = 1L,
    val name: String,
    val host: String,
    val port: Int = 443,
    val useHttps: Boolean = true,
    val allowInsecureHttp: Boolean = false,
    val encryptedApiKey: String,
    val encryptedSmbUsername: String? = null,
    val encryptedSmbPassword: String? = null,
    val smbDomain: String? = null,
    val dockerRootVariant: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    fun baseUrl(): String {
        val scheme = if (useHttps) "https" else "http"
        val isDefaultPort = (useHttps && port == 443) || (!useHttps && port == 80)
        return if (isDefaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }
}

@Entity(tableName = "cached_metric_snapshots")
data class CachedMetricSnapshotEntity(
    @PrimaryKey val serverId: Long = 1L,
    val osInfoJson: String,
    val cpuPercent: Double,
    val ramPercent: Double,
    val ramUsedBytes: Long,
    val ramTotalBytes: Long,
    val arrayState: String,
    val arrayUsedBytes: Long,
    val arrayTotalBytes: Long,
    val parityStatus: String,
    val parityProgress: Double,
    val sharesJson: String,
    val containersJson: String,
    val disksJson: String,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "share_mounts",
    indices = [Index(value = ["serverId", "shareName"], unique = true)]
)
data class ShareMountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val serverId: Long = 1L,
    val shareName: String,
    val comment: String = "",
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val includeDisks: String = "",
    val excludeDisks: String = "",
    val cacheMode: String = "",
    val smbEnabled: Boolean = true,
    val isMonitored: Boolean = true
)

@Entity(tableName = "docker_folders")
data class DockerFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val serverId: Long = 1L,
    val name: String,
    val sortOrder: Int = 0,
    val isExpanded: Boolean = true
)

@Entity(
    tableName = "docker_folder_memberships",
    primaryKeys = ["folderId", "containerName"],
    foreignKeys = [
        ForeignKey(
            entity = DockerFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["folderId"])]
)
data class DockerFolderMembershipEntity(
    val folderId: Long,
    val containerName: String
)

@Entity(tableName = "pinned_certs")
data class PinnedCertEntity(
    @PrimaryKey val host: String,
    val sha256Fingerprint: String,
    val issuer: String,
    val subject: String,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
    val pinnedAtMillis: Long
)
