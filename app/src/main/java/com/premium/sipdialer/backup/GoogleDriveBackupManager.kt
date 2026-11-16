package com.premium.sipdialer.backup

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.premium.sipdialer.database.AppDatabase
import com.premium.sipdialer.database.CallRecordEntity
import com.premium.sipdialer.database.SipAccountEntity
import com.premium.sipdialer.security.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EncryptedCloudBackupPayload(
    val version: Int = 1,
    val timestamp: String,
    val accounts: List<SipAccountEntity>,
    val encryptedPasswordsBase64: Map<String, String>,
    val callHistory: List<CallRecordEntity>
)

class GoogleDriveBackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val vault: CredentialVault
) {
    private val gson = Gson()

    suspend fun createEncryptedBackupFile(): File = withContext(Dispatchers.IO) {
        val accounts = db.dao().getAllSipAccounts()
        val logs = db.dao().getAllCallLogs()
        val encryptedMap = mutableMapOf<String, String>()

        accounts.forEach { acc ->
            val rawPwd = vault.getSipPassword(acc.accountId)
            if (rawPwd.isNotEmpty()) {
                val encBytes = CredentialVault.encryptBytes(rawPwd.toByteArray(Charsets.UTF_8))
                encryptedMap[acc.accountId] = Base64.encodeToString(encBytes, Base64.NO_WRAP)
            }
        }

        val nowStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        val payload = EncryptedCloudBackupPayload(
            timestamp = nowStr,
            accounts = accounts,
            encryptedPasswordsBase64 = encryptedMap,
            callHistory = logs
        )

        val rawJsonBytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        val encryptedFileBytes = CredentialVault.encryptBytes(rawJsonBytes)
        val backupFile = File(context.filesDir, "premium_sip_encrypted_backup.enc")
        backupFile.writeBytes(encryptedFileBytes)
        vault.setLastBackupTime(nowStr)
        backupFile
    }

    suspend fun restoreFromEncryptedBackupFile(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext false
        return@withContext try {
            val decryptedBytes = CredentialVault.decryptBytes(file.readBytes())
            val payload = gson.fromJson(
                String(decryptedBytes, Charsets.UTF_8),
                EncryptedCloudBackupPayload::class.java
            )
            payload.accounts.forEach { acc ->
                db.dao().upsertSipAccount(acc)
                payload.encryptedPasswordsBase64[acc.accountId]?.let { b64 ->
                    val pwdBytes = CredentialVault.decryptBytes(Base64.decode(b64, Base64.NO_WRAP))
                    vault.saveSipPassword(acc.accountId, String(pwdBytes, Charsets.UTF_8))
                }
            }
            payload.callHistory.forEach { log ->
                db.dao().insertCallLog(log)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
