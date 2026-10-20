package com.protectmyphone.app.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class KnownDeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val capturedAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
)

data class DeviceActionStatus(val message: String, val updatedAtEpochMillis: Long)

data class RealtimeLocationStatus(
    val enabled: Boolean,
    val intervalMinutes: Int,
    val message: String,
    val updatedAtEpochMillis: Long,
)

/** Persists useful remote-phone state locally; coordinates are encrypted with an Android Keystore key. */
class PairedDeviceStateStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun saveLocation(
        pairingId: String,
        peerDeviceId: String,
        location: DeviceLocation,
        receivedAtEpochMillis: Long,
    ): Boolean {
        if (!validCoordinates(location.latitude, location.longitude) || receivedAtEpochMillis <= 0L) return false
        val key = recordKey(LOCATION_PREFIX, pairingId, peerDeviceId)
        return try {
            val payload = ByteBuffer.allocate(32)
                .putDouble(location.latitude)
                .putDouble(location.longitude)
                .putLong(location.capturedAtEpochMillis.coerceAtLeast(0L))
                .putLong(receivedAtEpochMillis)
                .array()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            cipher.updateAAD(key.toByteArray(StandardCharsets.UTF_8))
            val encrypted = cipher.doFinal(payload)
            val stored = cipher.iv + encrypted
            preferences.edit().putString(key, Base64.encodeToString(stored, Base64.NO_WRAP)).commit()
        } catch (_: Exception) {
            false
        }
    }

    fun location(pairingId: String, peerDeviceId: String): KnownDeviceLocation? {
        val key = recordKey(LOCATION_PREFIX, pairingId, peerDeviceId)
        val encoded = preferences.getString(key, null) ?: return null
        return try {
            val stored = Base64.decode(encoded, Base64.NO_WRAP)
            if (stored.size != IV_SIZE_BYTES + 32 + GCM_TAG_SIZE_BYTES) return null
            val iv = stored.copyOfRange(0, IV_SIZE_BYTES)
            val encrypted = stored.copyOfRange(IV_SIZE_BYTES, stored.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(key.toByteArray(StandardCharsets.UTF_8))
            val bytes = cipher.doFinal(encrypted)
            if (bytes.size != 32) return null
            val buffer = ByteBuffer.wrap(bytes)
            val location = KnownDeviceLocation(
                latitude = buffer.double,
                longitude = buffer.double,
                capturedAtEpochMillis = buffer.long,
                receivedAtEpochMillis = buffer.long,
            )
            location.takeIf {
                validCoordinates(it.latitude, it.longitude) && it.receivedAtEpochMillis > 0L
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveRealtimeStatus(
        pairingId: String,
        peerDeviceId: String,
        status: RealtimeLocationStatus,
    ): Boolean = preferences.edit()
        .putBoolean(recordKey(TRACKING_ENABLED_PREFIX, pairingId, peerDeviceId), status.enabled)
        .putInt(recordKey(TRACKING_INTERVAL_PREFIX, pairingId, peerDeviceId), status.intervalMinutes)
        .putString(recordKey(TRACKING_MESSAGE_PREFIX, pairingId, peerDeviceId), status.message.take(MAX_STATUS_CHARS))
        .putLong(recordKey(TRACKING_UPDATED_PREFIX, pairingId, peerDeviceId), status.updatedAtEpochMillis)
        .commit()

    fun realtimeStatus(pairingId: String, peerDeviceId: String): RealtimeLocationStatus? {
        val enabledKey = recordKey(TRACKING_ENABLED_PREFIX, pairingId, peerDeviceId)
        if (!preferences.contains(enabledKey)) return null
        val interval = preferences.getInt(recordKey(TRACKING_INTERVAL_PREFIX, pairingId, peerDeviceId), 15)
        return RealtimeLocationStatus(
            enabled = preferences.getBoolean(enabledKey, false),
            intervalMinutes = interval.coerceIn(5, 24 * 60),
            message = preferences.getString(recordKey(TRACKING_MESSAGE_PREFIX, pairingId, peerDeviceId), "") ?: "",
            updatedAtEpochMillis = preferences.getLong(recordKey(TRACKING_UPDATED_PREFIX, pairingId, peerDeviceId), 0L),
        )
    }

    fun saveSoundStatus(pairingId: String, peerDeviceId: String, status: DeviceActionStatus): Boolean =
        saveActionStatus(SOUND_PREFIX, pairingId, peerDeviceId, status)

    fun soundStatus(pairingId: String, peerDeviceId: String): DeviceActionStatus? =
        actionStatus(SOUND_PREFIX, pairingId, peerDeviceId)

    fun saveLockMessageStatus(pairingId: String, peerDeviceId: String, status: DeviceActionStatus): Boolean =
        saveActionStatus(LOCK_MESSAGE_PREFIX, pairingId, peerDeviceId, status)

    fun lockMessageStatus(pairingId: String, peerDeviceId: String): DeviceActionStatus? =
        actionStatus(LOCK_MESSAGE_PREFIX, pairingId, peerDeviceId)

    fun remove(pairingId: String, peerDeviceId: String): Boolean {
        val id = identityHash(pairingId, peerDeviceId)
        val editor = preferences.edit()
        listOf(
            LOCATION_PREFIX,
            TRACKING_ENABLED_PREFIX,
            TRACKING_INTERVAL_PREFIX,
            TRACKING_MESSAGE_PREFIX,
            TRACKING_UPDATED_PREFIX,
            SOUND_PREFIX,
            SOUND_TIME_PREFIX,
            LOCK_MESSAGE_PREFIX,
            LOCK_MESSAGE_TIME_PREFIX,
        ).forEach { editor.remove("$it$id") }
        return editor.commit()
    }

    private fun saveActionStatus(prefix: String, pairingId: String, peerDeviceId: String, status: DeviceActionStatus): Boolean {
        val timePrefix = if (prefix == SOUND_PREFIX) SOUND_TIME_PREFIX else LOCK_MESSAGE_TIME_PREFIX
        return preferences.edit()
            .putString(recordKey(prefix, pairingId, peerDeviceId), status.message.take(MAX_STATUS_CHARS))
            .putLong(recordKey(timePrefix, pairingId, peerDeviceId), status.updatedAtEpochMillis)
            .commit()
    }

    private fun actionStatus(prefix: String, pairingId: String, peerDeviceId: String): DeviceActionStatus? {
        val messageKey = recordKey(prefix, pairingId, peerDeviceId)
        val message = preferences.getString(messageKey, null) ?: return null
        val timePrefix = if (prefix == SOUND_PREFIX) SOUND_TIME_PREFIX else LOCK_MESSAGE_TIME_PREFIX
        return DeviceActionStatus(
            message,
            preferences.getLong(recordKey(timePrefix, pairingId, peerDeviceId), 0L),
        )
    }

    private fun recordKey(prefix: String, pairingId: String, peerDeviceId: String) =
        prefix + identityHash(pairingId, peerDeviceId)

    private fun identityHash(pairingId: String, peerDeviceId: String): String {
        val bytes = "$pairingId\u0000$peerDeviceId".toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun validCoordinates(latitude: Double, longitude: Double) =
        latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0

    private companion object {
        const val PREFERENCES_NAME = "pmp_paired_device_state_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pmp_last_location_aes_gcm_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val GCM_TAG_SIZE_BYTES = 16
        const val MAX_STATUS_CHARS = 180
        const val LOCATION_PREFIX = "loc_"
        const val TRACKING_ENABLED_PREFIX = "track_enabled_"
        const val TRACKING_INTERVAL_PREFIX = "track_interval_"
        const val TRACKING_MESSAGE_PREFIX = "track_message_"
        const val TRACKING_UPDATED_PREFIX = "track_updated_"
        const val SOUND_PREFIX = "sound_"
        const val SOUND_TIME_PREFIX = "sound_time_"
        const val LOCK_MESSAGE_PREFIX = "lock_message_"
        const val LOCK_MESSAGE_TIME_PREFIX = "lock_message_time_"
    }
}
