package com.unraiddesk.app.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerConfigDao {
    @Query("SELECT * FROM server_configs WHERE id = 1 LIMIT 1")
    fun observePrimaryServer(): Flow<ServerConfigEntity?>

    @Query("SELECT * FROM server_configs WHERE id = 1 LIMIT 1")
    suspend fun getPrimaryServer(): ServerConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: ServerConfigEntity): Long

    @Query("DELETE FROM server_configs")
    suspend fun deleteAll()
}

@Dao
interface CachedMetricSnapshotDao {
    @Query("SELECT * FROM cached_metric_snapshots WHERE serverId = :serverId LIMIT 1")
    fun observeLatest(serverId: Long = 1L): Flow<CachedMetricSnapshotEntity?>

    @Query("SELECT * FROM cached_metric_snapshots WHERE serverId = :serverId LIMIT 1")
    suspend fun getLatest(serverId: Long = 1L): CachedMetricSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: CachedMetricSnapshotEntity)
}

@Dao
interface ShareMountDao {
    @Query("SELECT * FROM share_mounts WHERE serverId = :serverId ORDER BY shareName ASC")
    fun observeForServer(serverId: Long = 1L): Flow<List<ShareMountEntity>>

    @Query("SELECT * FROM share_mounts WHERE serverId = :serverId ORDER BY shareName ASC")
    suspend fun getForServer(serverId: Long = 1L): List<ShareMountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(shares: List<ShareMountEntity>)

    @Query("UPDATE share_mounts SET isMonitored = :monitored WHERE id = :id")
    suspend fun updateMonitored(id: Long, monitored: Boolean)

    @Query("DELETE FROM share_mounts WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: Long = 1L)
}

@Dao
interface DockerFolderDao {
    @Query("SELECT * FROM docker_folders WHERE serverId = :serverId ORDER BY sortOrder ASC, name ASC")
    fun observeFolders(serverId: Long = 1L): Flow<List<DockerFolderEntity>>

    @Query("SELECT * FROM docker_folder_memberships")
    fun observeMemberships(): Flow<List<DockerFolderMembershipEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: DockerFolderEntity): Long

    @Query("DELETE FROM docker_folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: Long)

    @Query("UPDATE docker_folders SET isExpanded = :expanded WHERE id = :folderId")
    suspend fun setExpanded(folderId: Long, expanded: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembership(membership: DockerFolderMembershipEntity)

    @Query("DELETE FROM docker_folder_memberships WHERE folderId = :folderId AND containerName = :containerName")
    suspend fun deleteMembership(folderId: Long, containerName: String)
}

@Dao
interface PinnedCertDao {
    @Query("SELECT * FROM pinned_certs WHERE host = :host LIMIT 1")
    fun getByHostSync(host: String): PinnedCertEntity?

    @Query("SELECT * FROM pinned_certs ORDER BY pinnedAtMillis DESC")
    fun observeAll(): Flow<List<PinnedCertEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSync(cert: PinnedCertEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cert: PinnedCertEntity)

    @Query("DELETE FROM pinned_certs WHERE host = :host")
    suspend fun deleteByHost(host: String)
}
