package com.protectmyphone.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.PhoneNumberUtils
import android.util.Base64
import com.protectmyphone.app.platform.SmsAuthenticationFailure
import com.protectmyphone.app.platform.SmsCommandAuthenticationResult
import com.protectmyphone.app.platform.SmsCommandParser
import com.protectmyphone.app.platform.SmsCommandParseResult
import com.protectmyphone.app.platform.SmsInboundAuthenticationResult
import com.protectmyphone.app.platform.SmsReply
import com.protectmyphone.app.platform.SmsReplyAuthenticationResult
import com.protectmyphone.app.platform.SmsReplyCodec
import com.protectmyphone.app.platform.SmsReplyParseResult
import java.nio.ByteBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire fields remain untrusted until [SmsCommandSecurity.authenticate] succeeds. */
data class SmsCommandEnvelope(
    val protocolVersion: Int,
    val pairingId: String,
    val senderDeviceId: String,
    val targetDeviceId: String,
    val issuedAtEpochMillis: Long,
    val nonce: String,
    val iv: String,
    val ciphertext: String,
)

/**
 * A bounded, canonical SMS representation. Large envelopes may need concatenated SMS segments;
 * the concrete Android transport is responsible for sending/reassembling those segments.
 */
object SmsCommandEnvelopeCodec {
    private const val PREFIX = "PMP1|"
    private const val MAX_BODY_CHARS = 4096
    private const val MAX_CIPHERTEXT_BYTES = 528
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun parseBody(body: String): SmsCommandEnvelope? {
        if (body.length !in PREFIX.length..MAX_BODY_CHARS || body.any { it == '\n' || it == '\r' }) return null
        if (!body.startsWith(PREFIX)) return null
        val fields = body.split('|', limit = 8)
        if (fields.size != 8 || fields[0] != "PMP1") return null

        val pairingId = fields[1]
        val sender = fields[2]
        val target = fields[3]
        if (!isIdentifier(pairingId) || !isIdentifier(sender) || !isIdentifier(target)) return null
        val issuedAt = fields[4].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val nonceBytes = decodeCanonicalBase64(fields[5], expectedBytes = 12) ?: return null
        val ivBytes = decodeCanonicalBase64(fields[6], expectedBytes = 12) ?: return null
        val ciphertextBytes = decodeCanonicalBase64(fields[7], maxBytes = MAX_CIPHERTEXT_BYTES)
            ?.takeIf { it.size >= 16 } ?: return null

        return SmsCommandEnvelope(
            protocolVersion = 1,
            pairingId = pairingId,
            senderDeviceId = sender,
            targetDeviceId = target,
            issuedAtEpochMillis = issuedAt,
            nonce = encodeBase64(nonceBytes),
            iv = encodeBase64(ivBytes),
            ciphertext = encodeBase64(ciphertextBytes),
        )
    }

    fun encode(envelope: SmsCommandEnvelope): String? {
        if (envelope.protocolVersion != 1 || !isIdentifier(envelope.pairingId) ||
            !isIdentifier(envelope.senderDeviceId) || !isIdentifier(envelope.targetDeviceId) ||
            envelope.issuedAtEpochMillis <= 0L
        ) return null
        val nonce = decodeCanonicalBase64(envelope.nonce, expectedBytes = 12) ?: return null
        val iv = decodeCanonicalBase64(envelope.iv, expectedBytes = 12) ?: return null
        val cipherText = decodeCanonicalBase64(envelope.ciphertext, maxBytes = MAX_CIPHERTEXT_BYTES)
            ?.takeIf { it.size >= 16 } ?: return null
        val body = listOf(
            "PMP1",
            envelope.pairingId,
            envelope.senderDeviceId,
            envelope.targetDeviceId,
            envelope.issuedAtEpochMillis.toString(),
            encodeBase64(nonce),
            encodeBase64(iv),
            encodeBase64(cipherText),
        ).joinToString("|")
        return body.takeIf { it.length <= MAX_BODY_CHARS }
    }

    internal fun associatedData(envelope: SmsCommandEnvelope): ByteArray = listOf(
        "PMP1",
        envelope.pairingId,
        envelope.senderDeviceId,
        envelope.targetDeviceId,
        envelope.issuedAtEpochMillis.toString(),
        envelope.nonce,
        envelope.iv,
    ).joinToString("|").toByteArray(StandardCharsets.UTF_8)

    internal fun decodeBase64(value: String): ByteArray? =
        decodeCanonicalBase64(value, maxBytes = MAX_CIPHERTEXT_BYTES)

    internal fun encodeBase64(value: ByteArray): String =
        Base64.encodeToString(value, BASE64_FLAGS)

    private fun isIdentifier(value: String): Boolean = value.length in 1..64 && value.all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
    }

    private fun decodeCanonicalBase64(
        value: String,
        expectedBytes: Int? = null,
        maxBytes: Int = 64,
    ): ByteArray? {
        if (value.isEmpty() || value.length > maxBytes * 2) return null
        val decoded = try {
            Base64.decode(value, BASE64_FLAGS)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.size > maxBytes || (expectedBytes != null && decoded.size != expectedBytes)) return null
        return decoded.takeIf { encodeBase64(it) == value }
    }
}

/** A key is available only after a locally confirmed pairing has been stored. */
interface ApprovedPairingKeyStore {
    fun getApprovedPairing(pairingId: String, peerDeviceId: String): ApprovedPairing?

    fun getApprovedKey(pairingId: String, peerDeviceId: String): SecretKey? =
        getApprovedPairing(pairingId, peerDeviceId)?.key

    fun approvePairing(
        pairingId: String,
        peerDeviceId: String,
        peerPhoneNumber: String,
        sharedKey: ByteArray,
        approval: PairingApproval,
    ): Boolean

    fun removePairing(pairingId: String, peerDeviceId: String): Boolean
}

data class ApprovedPairing(val key: SecretKey, val peerPhoneNumber: String)

/** Body/destination produced together so callers cannot accidentally reply to an arbitrary number. */
data class SealedSmsMessage(val body: String, val destinationPhoneNumber: String, val messageNonce: String)

/**
 * Proof that a person explicitly compared the full key fingerprints on both devices.
 * Generate the shared key through an authenticated key-agreement ceremony; never send it as
 * plaintext in an SMS. Comparing fingerprints over the same untrusted SMS channel is insufficient.
 */
class PairingApproval private constructor(internal val fingerprintHex: String) {
    companion object {
        fun compareFingerprints(
            sharedKey: ByteArray,
            localFingerprint: String,
            peerFingerprint: String,
            explicitlyConfirmedByUser: Boolean,
        ): PairingApproval? {
            if (!explicitlyConfirmedByUser || sharedKey.size != SHARED_KEY_BYTES) return null
            val expected = fingerprint(sharedKey)
            val local = normalizeFingerprint(localFingerprint) ?: return null
            val peer = normalizeFingerprint(peerFingerprint) ?: return null
            if (!constantTimeEquals(expected, local) || !constantTimeEquals(expected, peer)) return null
            return PairingApproval(expected)
        }

        fun fingerprint(sharedKey: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(sharedKey).joinToString("") { "%02X".format(it) }

        private const val SHARED_KEY_BYTES = 32

        private fun normalizeFingerprint(value: String): String? {
            val normalized = value.filterNot { it == ':' || it == '-' || it.isWhitespace() }.uppercase()
            return normalized.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'A'..'F' } }
        }

        private fun constantTimeEquals(expected: String, actual: String): Boolean =
            MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.US_ASCII),
                actual.toByteArray(StandardCharsets.US_ASCII),
            )
    }
}

/**
 * Stores only Android-Keystore-wrapped pairing material. The wrapping key is not exportable;
 * a device with no approved entry (or an entry that cannot be decrypted) has no trusted peer.
 */
class AndroidKeystorePairingKeyStore(context: Context) : ApprovedPairingKeyStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun getApprovedPairing(pairingId: String, peerDeviceId: String): ApprovedPairing? =
        synchronized(processLock) {
            val encrypted = preferences.getString(preferenceKey(pairingId, peerDeviceId), null) ?: return@synchronized null
            try {
                val wrapped = SmsCommandEnvelopeCodec.decodeBase64(encrypted) ?: return@synchronized null
                if (wrapped.size <= GCM_IV_BYTES + GCM_TAG_BYTES / 8) return@synchronized null
                val iv = wrapped.copyOfRange(0, GCM_IV_BYTES)
                val ciphertext = wrapped.copyOfRange(GCM_IV_BYTES, wrapped.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BYTES, iv))
                cipher.updateAAD(pairingAad(pairingId, peerDeviceId))
                val plaintext = cipher.doFinal(ciphertext)
                val input = DataInputStream(ByteArrayInputStream(plaintext))
                val keySize = input.readInt()
                if (keySize != SHARED_KEY_BYTES || input.available() < keySize) return@synchronized null
                val rawKey = ByteArray(keySize).also { input.readFully(it) }
                val phoneNumber = input.readUTF()
                if (input.available() != 0 || normalizeE164(phoneNumber) != phoneNumber) return@synchronized null
                if (rawKey.size != SHARED_KEY_BYTES) return@synchronized null
                ApprovedPairing(SecretKeySpec(rawKey, "AES"), phoneNumber)
            } catch (_: Exception) {
                null
            }
        }

    override fun approvePairing(
        pairingId: String,
        peerDeviceId: String,
        peerPhoneNumber: String,
        sharedKey: ByteArray,
        approval: PairingApproval,
    ): Boolean = synchronized(processLock) {
        val normalizedPhone = normalizeE164(peerPhoneNumber) ?: return@synchronized false
        if (!isValidIdentifier(pairingId) || !isValidIdentifier(peerDeviceId) ||
            sharedKey.size != SHARED_KEY_BYTES ||
            !MessageDigest.isEqual(
                PairingApproval.fingerprint(sharedKey).toByteArray(StandardCharsets.US_ASCII),
                approval.fingerprintHex.toByteArray(StandardCharsets.US_ASCII),
            )
        ) return@synchronized false

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Android Keystore keys require randomized encryption; let Keystore create the GCM IV.
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val iv = cipher.iv?.takeIf { it.size == GCM_IV_BYTES } ?: return@synchronized false
            cipher.updateAAD(pairingAad(pairingId, peerDeviceId))
            val serialized = ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(sharedKey.size)
                    output.write(sharedKey)
                    output.writeUTF(normalizedPhone)
                    output.flush()
                }
                buffer.toByteArray()
            }
            val ciphertext = cipher.doFinal(serialized)
            val wrapped = iv + ciphertext
            preferences.edit()
                .putString(preferenceKey(pairingId, peerDeviceId), SmsCommandEnvelopeCodec.encodeBase64(wrapped))
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    override fun removePairing(pairingId: String, peerDeviceId: String): Boolean = synchronized(processLock) {
        if (!isValidIdentifier(pairingId) || !isValidIdentifier(peerDeviceId)) return@synchronized false
        preferences.edit().remove(preferenceKey(pairingId, peerDeviceId)).commit()
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(WRAPPING_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAPPING_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun pairingAad(pairingId: String, peerDeviceId: String) =
        "pmp-pairing-wrap-v1|$pairingId|$peerDeviceId".toByteArray(StandardCharsets.UTF_8)

    private fun preferenceKey(pairingId: String, peerDeviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$pairingId\u0000$peerDeviceId".toByteArray(StandardCharsets.UTF_8))
        return "pair_${digest.joinToString("") { "%02x".format(it) }}"
    }

    private fun isValidIdentifier(value: String) = value.length in 1..64 && value.all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
    }

    private fun normalizeE164(value: String): String? {
        val normalized = PhoneNumberUtils.normalizeNumber(value)
        if (!normalized.startsWith('+')) return null
        val digitCount = normalized.count { it.isDigit() }
        return normalized.takeIf { digitCount in 8..15 && it.drop(1).all { char -> char.isDigit() } }
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val WRAPPING_KEY_ALIAS = "com.protectmyphone.pairing.wrap.v1"
        private const val PREFERENCES_NAME = "pmp_approved_pairings_v1"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 128
        private const val SHARED_KEY_BYTES = 32
        private val secureRandom = SecureRandom()
        private val processLock = Any()
    }
}

/** Atomic nonce consumption. False means replayed, storage-full, or storage failure: reject it. */
interface SmsReplayGuard {
    fun consumeIfNew(
        pairingId: String,
        senderDeviceId: String,
        nonce: String,
        expiresAtEpochMillis: Long,
        nowEpochMillis: Long,
    ): Boolean
}

class AndroidSmsReplayGuard(context: Context) : SmsReplayGuard {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun consumeIfNew(
        pairingId: String,
        senderDeviceId: String,
        nonce: String,
        expiresAtEpochMillis: Long,
        nowEpochMillis: Long,
    ): Boolean = synchronized(processLock) {
        if (expiresAtEpochMillis <= nowEpochMillis) return@synchronized false
        val entryKey = replayKey(pairingId, senderDeviceId, nonce)
        if (preferences.contains(entryKey)) return@synchronized false

        val activeEntries = preferences.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(NONCE_PREFIX)) null else key to (value as? Long)
        }
        val expired = activeEntries.filter { (_, expiresAt) -> expiresAt == null || expiresAt <= nowEpochMillis }
        val activeCount = activeEntries.size - expired.size
        if (activeCount >= MAX_ENTRIES) return@synchronized false

        val editor = preferences.edit()
        expired.forEach { (key, _) -> editor.remove(key) }
        editor.putLong(entryKey, expiresAtEpochMillis)
        editor.commit()
    }

    private fun replayKey(pairingId: String, senderDeviceId: String, nonce: String): String {
        val data = "$pairingId\u0000$senderDeviceId\u0000$nonce".toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return NONCE_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFERENCES_NAME = "pmp_sms_replay_v1"
        private const val NONCE_PREFIX = "nonce_"
        private const val MAX_ENTRIES = 4096
        private val processLock = Any()
    }
}

/**
 * Encrypts outgoing authenticated command payloads and authenticates incoming ones. A missing
 * pairing, bad tag, wrong device, stale timestamp, replay, or unknown command always fails closed.
 */
class SmsCommandSecurity(
    private val pairingKeys: ApprovedPairingKeyStore,
    private val replayGuard: SmsReplayGuard,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    /** Convenience for callers that only need the encrypted body; use sealCommand for its nonce. */
    fun seal(
        pairingId: String,
        peerDeviceId: String,
        senderDeviceId: String,
        commandPayload: String,
        issuedAtEpochMillis: Long,
    ): String? = sealCommand(
        pairingId, peerDeviceId, senderDeviceId, commandPayload, issuedAtEpochMillis,
    )?.body

    fun sealCommand(
        pairingId: String,
        peerDeviceId: String,
        senderDeviceId: String,
        commandPayload: String,
        issuedAtEpochMillis: Long,
    ): SealedSmsMessage? {
        if (!isIdentifier(pairingId) || !isIdentifier(peerDeviceId) || !isIdentifier(senderDeviceId) ||
            issuedAtEpochMillis <= 0L || commandPayload.toByteArray(StandardCharsets.UTF_8).size > MAX_PLAINTEXT_BYTES ||
            SmsCommandParser.parseAuthenticatedPayload(commandPayload) !is SmsCommandParseResult.Parsed
        ) return null

        val pairing = pairingKeys.getApprovedPairing(pairingId, peerDeviceId) ?: return null
        val body = sealPayload(pairingId, peerDeviceId, senderDeviceId, pairing.key, commandPayload, issuedAtEpochMillis)
            ?: return null
        val nonce = SmsCommandEnvelopeCodec.parseBody(body)?.nonce ?: return null
        return SealedSmsMessage(body, pairing.peerPhoneNumber, nonce)
    }

    fun sealReply(
        pairingId: String,
        peerDeviceId: String,
        senderDeviceId: String,
        reply: SmsReply,
        issuedAtEpochMillis: Long,
    ): SealedSmsMessage? {
        if (!isIdentifier(pairingId) || !isIdentifier(peerDeviceId) || !isIdentifier(senderDeviceId) ||
            issuedAtEpochMillis <= 0L
        ) return null
        val payload = SmsReplyCodec.encode(reply) ?: return null
        val pairing = pairingKeys.getApprovedPairing(pairingId, peerDeviceId) ?: return null
        val body = sealPayload(pairingId, peerDeviceId, senderDeviceId, pairing.key, payload, issuedAtEpochMillis)
            ?: return null
        val nonce = SmsCommandEnvelopeCodec.parseBody(body)?.nonce ?: return null
        return SealedSmsMessage(body, pairing.peerPhoneNumber, nonce)
    }

    fun authenticate(
        body: String,
        smsOriginatingAddress: String,
        expectedTargetDeviceId: String,
        nowEpochMillis: Long,
    ): SmsCommandAuthenticationResult {
        val payload = when (val decrypted = decryptAuthenticatedPayload(
            body = body,
            smsOriginatingAddress = smsOriginatingAddress,
            expectedTargetDeviceId = expectedTargetDeviceId,
            nowEpochMillis = nowEpochMillis,
        )) {
            is DecryptionResult.Rejected -> return rejected(decrypted.reason)
            is DecryptionResult.Accepted -> decrypted
        }
        val parsed = SmsCommandParser.parseAuthenticatedPayload(payload.plaintext)
        if (parsed !is SmsCommandParseResult.Parsed) {
            return rejected(SmsAuthenticationFailure.INVALID_COMMAND)
        }
        if (!consumeReplay(payload.envelope, nowEpochMillis)) {
            return rejected(SmsAuthenticationFailure.REPLAYED_OR_REPLAY_STORE_UNAVAILABLE)
        }
        return SmsCommandAuthenticationResult.Authorized(
            senderDeviceId = payload.envelope.senderDeviceId,
            senderPhoneNumber = payload.approvedPeer.peerPhoneNumber,
            pairingId = payload.envelope.pairingId,
            requestNonce = payload.envelope.nonce,
            command = parsed.command,
            issuedAtEpochMillis = payload.envelope.issuedAtEpochMillis,
        )
    }

    fun authenticateReply(
        body: String,
        smsOriginatingAddress: String,
        expectedTargetDeviceId: String,
        expectedPeerDeviceId: String,
        expectedPairingId: String,
        expectedRequestNonce: String,
        nowEpochMillis: Long,
    ): SmsReplyAuthenticationResult {
        val payload = when (val decrypted = decryptAuthenticatedPayload(
            body = body,
            smsOriginatingAddress = smsOriginatingAddress,
            expectedTargetDeviceId = expectedTargetDeviceId,
            expectedPeerDeviceId = expectedPeerDeviceId,
            expectedPairingId = expectedPairingId,
            nowEpochMillis = nowEpochMillis,
        )) {
            is DecryptionResult.Rejected -> return SmsReplyAuthenticationResult.Rejected(decrypted.reason)
            is DecryptionResult.Accepted -> decrypted
        }
        val parsed = SmsReplyCodec.parseAuthenticatedPayload(payload.plaintext)
        if (parsed !is SmsReplyParseResult.Parsed) {
            return SmsReplyAuthenticationResult.Rejected(SmsAuthenticationFailure.INVALID_COMMAND)
        }
        if (payload.envelope.pairingId != expectedPairingId || parsed.reply.requestNonce != expectedRequestNonce) {
            return SmsReplyAuthenticationResult.Rejected(SmsAuthenticationFailure.WRONG_CORRELATION)
        }
        if (!consumeReplay(payload.envelope, nowEpochMillis)) {
            return SmsReplyAuthenticationResult.Rejected(SmsAuthenticationFailure.REPLAYED_OR_REPLAY_STORE_UNAVAILABLE)
        }
        return SmsReplyAuthenticationResult.Authorized(
            senderDeviceId = payload.envelope.senderDeviceId,
            pairingId = payload.envelope.pairingId,
            reply = parsed.reply,
        )
    }

    /**
     * Authenticates an inbound SMS exactly once, then classifies it as a command or a correlated
     * reply. The callback must match replies against a locally recorded outstanding request.
     */
    fun authenticateInbound(
        body: String,
        smsOriginatingAddress: String,
        expectedTargetDeviceId: String,
        nowEpochMillis: Long,
        isExpectedReply: (pairingId: String, peerDeviceId: String, requestNonce: String) -> Boolean,
    ): SmsInboundAuthenticationResult {
        val payload = when (val decrypted = decryptAuthenticatedPayload(
            body = body,
            smsOriginatingAddress = smsOriginatingAddress,
            expectedTargetDeviceId = expectedTargetDeviceId,
            nowEpochMillis = nowEpochMillis,
        )) {
            is DecryptionResult.Rejected -> return SmsInboundAuthenticationResult.Rejected(decrypted.reason)
            is DecryptionResult.Accepted -> decrypted
        }

        val command = SmsCommandParser.parseAuthenticatedPayload(payload.plaintext)
        if (command is SmsCommandParseResult.Parsed) {
            if (!consumeReplay(payload.envelope, nowEpochMillis)) {
                return SmsInboundAuthenticationResult.Rejected(SmsAuthenticationFailure.REPLAYED_OR_REPLAY_STORE_UNAVAILABLE)
            }
            return SmsInboundAuthenticationResult.CommandAuthorized(
                SmsCommandAuthenticationResult.Authorized(
                    senderDeviceId = payload.envelope.senderDeviceId,
                    senderPhoneNumber = payload.approvedPeer.peerPhoneNumber,
                    pairingId = payload.envelope.pairingId,
                    requestNonce = payload.envelope.nonce,
                    command = command.command,
                    issuedAtEpochMillis = payload.envelope.issuedAtEpochMillis,
                ),
            )
        }

        val reply = SmsReplyCodec.parseAuthenticatedPayload(payload.plaintext)
        if (reply !is SmsReplyParseResult.Parsed) {
            return SmsInboundAuthenticationResult.Rejected(SmsAuthenticationFailure.INVALID_COMMAND)
        }
        val matchesRequest = try {
            isExpectedReply(payload.envelope.pairingId, payload.envelope.senderDeviceId, reply.reply.requestNonce)
        } catch (_: RuntimeException) {
            false
        }
        if (!matchesRequest) {
            return SmsInboundAuthenticationResult.Rejected(SmsAuthenticationFailure.WRONG_CORRELATION)
        }
        if (!consumeReplay(payload.envelope, nowEpochMillis)) {
            return SmsInboundAuthenticationResult.Rejected(SmsAuthenticationFailure.REPLAYED_OR_REPLAY_STORE_UNAVAILABLE)
        }
        return SmsInboundAuthenticationResult.ReplyAuthorized(
            SmsReplyAuthenticationResult.Authorized(
                senderDeviceId = payload.envelope.senderDeviceId,
                pairingId = payload.envelope.pairingId,
                reply = reply.reply,
            ),
        )
    }

    private fun sealPayload(
        pairingId: String,
        peerDeviceId: String,
        senderDeviceId: String,
        key: SecretKey,
        plaintext: String,
        issuedAtEpochMillis: Long,
    ): String? {
        val keyBytes = key.encoded ?: return null
        if (keyBytes.size != SHARED_KEY_BYTES || !key.algorithm.equals("AES", ignoreCase = true) ||
            plaintext.toByteArray(StandardCharsets.UTF_8).size > MAX_PLAINTEXT_BYTES
        ) return null
        return try {
            val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
            val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
            var envelope = SmsCommandEnvelope(
                protocolVersion = 1,
                pairingId = pairingId,
                senderDeviceId = senderDeviceId,
                targetDeviceId = peerDeviceId,
                issuedAtEpochMillis = issuedAtEpochMillis,
                nonce = SmsCommandEnvelopeCodec.encodeBase64(nonce),
                iv = SmsCommandEnvelopeCodec.encodeBase64(iv),
                ciphertext = "",
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(SmsCommandEnvelopeCodec.associatedData(envelope))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            envelope = envelope.copy(ciphertext = SmsCommandEnvelopeCodec.encodeBase64(ciphertext))
            SmsCommandEnvelopeCodec.encode(envelope)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun decryptAuthenticatedPayload(
        body: String,
        smsOriginatingAddress: String,
        expectedTargetDeviceId: String,
        expectedPeerDeviceId: String? = null,
        expectedPairingId: String? = null,
        nowEpochMillis: Long,
    ): DecryptionResult {
        val envelope = SmsCommandEnvelopeCodec.parseBody(body)
            ?: return DecryptionResult.Rejected(SmsAuthenticationFailure.MALFORMED_ENVELOPE)
        if (envelope.protocolVersion != 1) return DecryptionResult.Rejected(SmsAuthenticationFailure.UNSUPPORTED_VERSION)
        if (envelope.targetDeviceId != expectedTargetDeviceId) {
            return DecryptionResult.Rejected(SmsAuthenticationFailure.WRONG_TARGET)
        }
        if ((expectedPeerDeviceId != null && envelope.senderDeviceId != expectedPeerDeviceId) ||
            (expectedPairingId != null && envelope.pairingId != expectedPairingId)
        ) return DecryptionResult.Rejected(SmsAuthenticationFailure.UNPAIRED_SENDER)
        if (envelope.issuedAtEpochMillis > nowEpochMillis + FUTURE_TOLERANCE_MS ||
            envelope.issuedAtEpochMillis < nowEpochMillis - MAX_AGE_MS ||
            envelope.issuedAtEpochMillis > Long.MAX_VALUE - MAX_AGE_MS
        ) return DecryptionResult.Rejected(SmsAuthenticationFailure.EXPIRED_OR_FUTURE)

        val pairing = pairingKeys.getApprovedPairing(envelope.pairingId, envelope.senderDeviceId)
            ?: return DecryptionResult.Rejected(SmsAuthenticationFailure.UNPAIRED_SENDER)
        if (!samePhoneNumber(pairing.peerPhoneNumber, smsOriginatingAddress)) {
            return DecryptionResult.Rejected(SmsAuthenticationFailure.UNPAIRED_SENDER)
        }
        val plaintextBytes = try {
            val iv = SmsCommandEnvelopeCodec.decodeBase64(envelope.iv)
                ?: return DecryptionResult.Rejected(SmsAuthenticationFailure.MALFORMED_ENVELOPE)
            val ciphertext = SmsCommandEnvelopeCodec.decodeBase64(envelope.ciphertext)
                ?: return DecryptionResult.Rejected(SmsAuthenticationFailure.MALFORMED_ENVELOPE)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, pairing.key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(SmsCommandEnvelopeCodec.associatedData(envelope))
            cipher.doFinal(ciphertext)
        } catch (_: GeneralSecurityException) {
            return DecryptionResult.Rejected(SmsAuthenticationFailure.INVALID_AUTHENTICATOR)
        } catch (_: IllegalArgumentException) {
            return DecryptionResult.Rejected(SmsAuthenticationFailure.INVALID_AUTHENTICATOR)
        }
        if (plaintextBytes.size > MAX_PLAINTEXT_BYTES) {
            return DecryptionResult.Rejected(SmsAuthenticationFailure.INVALID_COMMAND)
        }
        val plaintext = decodeUtf8(plaintextBytes)
            ?: return DecryptionResult.Rejected(SmsAuthenticationFailure.INVALID_COMMAND)
        return DecryptionResult.Accepted(envelope, pairing, plaintext)
    }

    private fun consumeReplay(envelope: SmsCommandEnvelope, nowEpochMillis: Long): Boolean {
        val expiresAt = envelope.issuedAtEpochMillis + MAX_AGE_MS
        return replayGuard.consumeIfNew(
            envelope.pairingId,
            envelope.senderDeviceId,
            envelope.nonce,
            expiresAt,
            nowEpochMillis,
        )
    }

    private fun samePhoneNumber(approved: String, source: String): Boolean {
        val normalizedSource = PhoneNumberUtils.normalizeNumber(source)
        if (normalizedSource.isBlank()) return false
        return normalizedSource == approved || normalizedSource.removePrefix("+") == approved.removePrefix("+")
    }

    private sealed class DecryptionResult {
        data class Accepted(
            val envelope: SmsCommandEnvelope,
            val approvedPeer: ApprovedPairing,
            val plaintext: String,
        ) : DecryptionResult()
        data class Rejected(val reason: SmsAuthenticationFailure) : DecryptionResult()
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun rejected(reason: SmsAuthenticationFailure) =
        SmsCommandAuthenticationResult.Rejected(reason)

    private fun isIdentifier(value: String) = value.length in 1..64 && value.all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
    }

    companion object {
        private const val SHARED_KEY_BYTES = 32
        private const val MAX_PLAINTEXT_BYTES = 512
        private const val NONCE_BYTES = 12
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MAX_AGE_MS = 5 * 60 * 1000L
        private const val FUTURE_TOLERANCE_MS = 60 * 1000L
    }
}
