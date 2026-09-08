/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.broadcast

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.data.broadcast.db.BroadcastDatabase
import org.fcitx.fcitx5.android.data.broadcast.db.PairedAppEntity
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object BroadcastSecurityManager : org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.OnClipboardUpdateListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {

    private const val TAG = "Fcitx5广播剪切板"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
    private const val KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val SHARED_PREFS_NAME = "broadcast_prefs"
    private const val BROADCAST_KEY_PREF = "broadcast_key"
    private const val KEY_ALIAS_PREFIX = "fcitx5_clipboard_"
    private const val PAIRING_CODE_LENGTH = 6
    private const val PAIRING_CODE_EXPIRY_MS = 5 * 60 * 1000L // 5 minutes

    const val ACTION_CLIPBOARD_BROADCAST =
        "org.fcitx.fcitx5.android.action.CLIPBOARD_BROADCAST"
    const val EXTRA_ENCRYPTED_DATA = "encrypted_data"
    const val EXTRA_TIMESTAMP = "timestamp"
    const val EXTRA_SENDER_PACKAGE = "sender_package"

    private lateinit var db: BroadcastDatabase
    private val dao get() = db.broadcastDao()
    private val mutex = Mutex()

    private var currentPairingCode: String? = null
    private var pairingCodeTimestamp: Long = 0L

    fun init(context: Context) {
        db = Room.databaseBuilder(
            context,
            BroadcastDatabase::class.java,
            BroadcastDatabase.DATABASE_NAME
        ).build()
    }

    fun isEnabled(): Boolean {
        return AppPrefs.getInstance().broadcast.enabled.getValue()
    }

    fun startListening() {
        if (isEnabled()) {
            org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.addOnUpdateListener(this)
        }
    }

    fun stopListening() {
        org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.removeOnUpdateListener(this)
    }

    override fun onUpdate(entry: org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry) {
        if (!isEnabled()) return
        launch {
            broadcastClipboardEntry(entry.text)
        }
    }

    suspend fun broadcastClipboardEntry(text: String) {
        if (!isEnabled()) return
        val pairedApps = dao.getAll()
        if (pairedApps.isEmpty()) return

        val encryptedData = encryptForAll(text, pairedApps)

        val intent = Intent(ACTION_CLIPBOARD_BROADCAST).apply {
            putExtra(EXTRA_ENCRYPTED_DATA, encryptedData)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
            putExtra(EXTRA_SENDER_PACKAGE, appContext.packageName)
            setPackage(null) // broadcast to all
        }

        pairedApps.forEach { app ->
            try {
                val targetedIntent = Intent(intent).setPackage(app.packageName)
                appContext.sendBroadcast(targetedIntent, "org.fcitx.fcitx5.android.permission.CLIPBOARD_BROADCAST")
                Timber.tag(TAG).d("广播已发送至 ${app.packageName}")
            } catch (e: Exception) {
                Timber.tag(TAG).w("广播发送失败 ${app.packageName}: ${e.message}")
            }
        }
    }

    private fun encryptForAll(text: String, pairedApps: List<PairedAppEntity>): ByteArray {
        val broadcastKey = getOrCreateBroadcastKey()
        return encrypt(text.toByteArray(Charsets.UTF_8), broadcastKey)
    }

    fun generatePairingCode(): String {
        val code = (100000..999999).random().toString()
        currentPairingCode = code
        pairingCodeTimestamp = System.currentTimeMillis()
        Timber.tag(TAG).d("生成配对码: $code")
        return code
    }

    fun getPairingCode(): String? {
        if (currentPairingCode == null) return null
        if (System.currentTimeMillis() - pairingCodeTimestamp > PAIRING_CODE_EXPIRY_MS) {
            currentPairingCode = null
            return null
        }
        return currentPairingCode
    }

    suspend fun verifyPairingCode(code: String, packageName: String, appName: String): Boolean {
        val storedCode = currentPairingCode ?: return false
        if (System.currentTimeMillis() - pairingCodeTimestamp > PAIRING_CODE_EXPIRY_MS) {
            currentPairingCode = null
            return false
        }
        if (code != storedCode) return false

        mutex.withLock {
            val existing = dao.findByPackageName(packageName)
            if (existing != null) {
                Timber.tag(TAG).d("应用 $packageName 已配对")
                return true
            }

            val keyAlias = "$KEY_ALIAS_PREFIX${packageName.hashCode()}"
            generateKeyInKeystore(keyAlias)

            dao.insert(
                PairedAppEntity(
                    packageName = packageName,
                    appName = appName,
                    keyAlias = keyAlias
                )
            )
            currentPairingCode = null
            Timber.tag(TAG).d("配对成功: $packageName")
            return true
        }
    }

    suspend fun getAllPairedApps(): List<PairedAppEntity> {
        return dao.getAll()
    }

    suspend fun revokePairing(packageName: String): Boolean {
        return mutex.withLock {
            val app = dao.findByPackageName(packageName) ?: return@withLock false
            deleteKeyFromKeystore(app.keyAlias)
            dao.deleteByPackageName(packageName) > 0
        }
    }

    suspend fun isAppPaired(packageName: String): Boolean {
        return dao.findByPackageName(packageName) != null
    }

    fun getSharedKeyForPackage(packageName: String): String? {
        return getOrCreateBroadcastKeyBase64()
    }

    private fun getOrCreateBroadcastKeyBase64(): String {
        val prefs = appContext.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(BROADCAST_KEY_PREF, null)
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KEY_ALGORITHM)
        keyGen.init(KEY_SIZE, SecureRandom())
        val key = keyGen.generateKey()
        val encoded = Base64.encodeToString(key.encoded, Base64.NO_WRAP)
        prefs.edit().putString(BROADCAST_KEY_PREF, encoded).apply()
        return encoded
    }

    private fun getOrCreateBroadcastKey(): SecretKey {
        val encoded = getOrCreateBroadcastKeyBase64()
        val decoded = Base64.decode(encoded, Base64.NO_WRAP)
        return SecretKeySpec(decoded, KEY_ALGORITHM)
    }

    private fun getOrCreateSharedKey(): SecretKey {
        val alias = "${KEY_ALIAS_PREFIX}shared"
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getEntry(alias, null)?.let {
            return (it as KeyStore.SecretKeyEntry).secretKey
        }
        return generateKeyInKeystore(alias)
    }

    private fun generateKeyInKeystore(alias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KEY_ALGORITHM,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun deleteKeyFromKeystore(alias: String) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.deleteEntry(alias)
    }

    private fun getKeyFromKeystore(alias: String): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey
    }

    fun encrypt(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        // Prepend IV to encrypted data: [IV_LENGTH(1 byte)][IV][ENCRYPTED_DATA]
        return ByteArray(1 + iv.size + encrypted.size).apply {
            this[0] = iv.size.toByte()
            System.arraycopy(iv, 0, this, 1, iv.size)
            System.arraycopy(encrypted, 0, this, 1 + iv.size, encrypted.size)
        }
    }

    fun decrypt(data: ByteArray, key: SecretKey): ByteArray {
        val ivLength = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + ivLength)
        val encrypted = data.copyOfRange(1 + ivLength, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(encrypted)
    }

    fun decryptWithSharedKey(data: ByteArray): ByteArray? {
        return try {
            val key = getKeyFromKeystore("${KEY_ALIAS_PREFIX}shared") ?: return null
            decrypt(data, key)
        } catch (e: Exception) {
            Timber.tag(TAG).w("解密失败: ${e.message}")
            null
        }
    }
}
