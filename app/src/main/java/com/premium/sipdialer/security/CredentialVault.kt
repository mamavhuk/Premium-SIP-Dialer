package com.premium.sipdialer.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialVault(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "secure_sip_vault",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveSipPassword(accountId: String, plainPassword: String) {
        securePrefs.edit().putString("pwd_$accountId", plainPassword).apply()
    }

    fun getSipPassword(accountId: String): String {
        return securePrefs.getString("pwd_$accountId", "") ?: ""
    }

    fun deleteSipPassword(accountId: String) {
        securePrefs.edit().remove("pwd_$accountId").apply()
    }

    fun setGuestMode(enabled: Boolean) {
        securePrefs.edit().putBoolean("guest_mode", enabled).apply()
    }

    fun isGuestMode(): Boolean = securePrefs.getBoolean("guest_mode", true)

    fun setLastBackupTime(timestamp: String) {
        securePrefs.edit().putString("last_backup_time", timestamp).apply()
    }

    fun getLastBackupTime(): String = securePrefs.getString("last_backup_time", "Never") ?: "Never"

    companion object {
        private const val KEYSTORE_ALIAS = "PremiumSipBackupKey"

        private fun getOrCreateAesKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            return keyGen.generateKey()
        }

        fun encryptBytes(plainBytes: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
            return cipher.iv + cipher.doFinal(plainBytes)
        }

        fun decryptBytes(encryptedPayload: ByteArray): ByteArray {
            val iv = encryptedPayload.copyOfRange(0, 12)
            val cipherText = encryptedPayload.copyOfRange(12, encryptedPayload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(128, iv))
            return cipher.doFinal(cipherText)
        }
    }
}
