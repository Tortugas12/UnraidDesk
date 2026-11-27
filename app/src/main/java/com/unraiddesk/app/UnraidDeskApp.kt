package com.unraiddesk.app

import android.app.Application
import com.unraiddesk.app.core.data.db.RoomPinnedCertStore
import com.unraiddesk.app.core.data.db.UnraidDeskDatabase
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository
import com.unraiddesk.app.core.network.ApiKeyAuthCoordinator
import com.unraiddesk.app.core.network.OkHttpFactory
import com.unraiddesk.app.core.network.UnraidGraphQlClient
import com.unraiddesk.app.core.security.KeystoreCryptoManager
import com.unraiddesk.app.core.security.LanVpnGuard
import com.unraiddesk.app.core.security.TofuTrustManager

class UnraidDeskApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val database: UnraidDeskDatabase by lazy { UnraidDeskDatabase.getInstance(app) }
    val preferencesRepository: AppPreferencesRepository by lazy { AppPreferencesRepository(app) }
    val cryptoManager: KeystoreCryptoManager by lazy { KeystoreCryptoManager() }
    val lanVpnGuard: LanVpnGuard by lazy { LanVpnGuard(app) }
    val pinnedCertStore: RoomPinnedCertStore by lazy {
        RoomPinnedCertStore(database.pinnedCertDao())
    }
    val tofuTrustManager: TofuTrustManager by lazy {
        TofuTrustManager(pinnedCertStore = pinnedCertStore)
    }
    val okHttpFactory: OkHttpFactory by lazy {
        OkHttpFactory(lanVpnGuard = lanVpnGuard, tofuTrustManager = tofuTrustManager)
    }
    val graphQlClient: UnraidGraphQlClient by lazy {
        UnraidGraphQlClient(okHttpFactory.createClient())
    }
    val authCoordinator: ApiKeyAuthCoordinator by lazy { ApiKeyAuthCoordinator() }
    val transferQueueManager: com.unraiddesk.app.files.transfer.TransferQueueManager by lazy {
        com.unraiddesk.app.files.transfer.TransferQueueManager()
    }
}
