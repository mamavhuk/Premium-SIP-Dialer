package com.premium.sipdialer

import android.app.Application
import com.premium.sipdialer.backup.GoogleDriveBackupManager
import com.premium.sipdialer.database.AppDatabase
import com.premium.sipdialer.security.CredentialVault
import com.premium.sipdialer.sip.SipEngineManager

class SipApplication : Application() {
    lateinit var database: AppDatabase
    lateinit var vault: CredentialVault
    lateinit var sipEngine: SipEngineManager
    lateinit var backupManager: GoogleDriveBackupManager

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.get(this)
        vault = CredentialVault(this)
        sipEngine = SipEngineManager(this, vault, database)
        backupManager = GoogleDriveBackupManager(this, database, vault)
    }
}
