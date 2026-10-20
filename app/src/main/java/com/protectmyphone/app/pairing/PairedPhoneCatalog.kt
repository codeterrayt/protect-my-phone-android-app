package com.protectmyphone.app.pairing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Non-secret paired-phone labels and routing metadata; encryption keys remain in Android Keystore. */
class PairedPhoneCatalog(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<PairedPhone> = runCatching {
        val array = JSONArray(preferences.getString(PHONES, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val pairingId = item.optString("pairingId")
                val peerId = item.optString("peerDeviceId")
                val phone = item.optString("phoneNumber")
                if (pairingId.isBlank() || peerId.isBlank() || phone.isBlank()) continue
                add(
                    PairedPhone(
                        pairingId = pairingId,
                        peerDeviceId = peerId,
                        label = item.optString("label").ifBlank { "Protected phone" },
                        phoneNumber = phone,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun savePendingLabel(sessionId: String, label: String): Boolean =
        preferences.edit().putString(PENDING_LABEL_PREFIX + sessionId, label.take(60)).commit()

    @Synchronized
    fun removePendingLabel(sessionId: String): Boolean =
        preferences.edit().remove(PENDING_LABEL_PREFIX + sessionId).commit()

    @Synchronized
    fun upsert(pairing: PairingFingerprintConfirmation): Boolean {
        val displayLabel = preferences.getString(PENDING_LABEL_PREFIX + pairing.sessionId, null)
            ?.takeIf { it.isNotBlank() }
            ?: pairing.peerLabel.takeIf { it.isNotBlank() }
            ?: "Protected phone"
        val records = all().filterNot { it.pairingId == pairing.pairingId && it.peerDeviceId == pairing.peerDeviceId }
        val serialized = JSONArray().apply {
            (records + PairedPhone(pairing.pairingId, pairing.peerDeviceId, displayLabel, pairing.peerPhoneNumber)).forEach { phone ->
                put(
                    JSONObject()
                        .put("pairingId", phone.pairingId)
                        .put("peerDeviceId", phone.peerDeviceId)
                        .put("label", phone.label)
                        .put("phoneNumber", phone.phoneNumber),
                )
            }
        }
        return preferences.edit()
            .putString(PHONES, serialized.toString())
            .remove(PENDING_LABEL_PREFIX + pairing.sessionId)
            .commit()
    }

    @Synchronized
    fun remove(pairingId: String, peerDeviceId: String): Boolean {
        val serialized = JSONArray().apply {
            all().filterNot { it.pairingId == pairingId && it.peerDeviceId == peerDeviceId }.forEach { phone ->
                put(
                    JSONObject()
                        .put("pairingId", phone.pairingId)
                        .put("peerDeviceId", phone.peerDeviceId)
                        .put("label", phone.label)
                        .put("phoneNumber", phone.phoneNumber),
                )
            }
        }
        return preferences.edit().putString(PHONES, serialized.toString()).commit()
    }

    companion object {
        private const val PREFERENCES = "paired_phone_catalog"
        private const val PHONES = "phones"
        private const val PENDING_LABEL_PREFIX = "pending_label_"
    }
}

data class PairedPhone(
    val pairingId: String,
    val peerDeviceId: String,
    val label: String,
    val phoneNumber: String,
) {
    val catalogId: String get() = "$pairingId:$peerDeviceId"
}
