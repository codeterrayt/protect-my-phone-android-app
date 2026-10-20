package com.protectmyphone.app.security

import android.telephony.PhoneNumberUtils
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class PairingHandshakeMessageType { OFFER, ANSWER }

data class PairingHandshakeMessage(
    val type: PairingHandshakeMessageType,
    val sessionId: String,
    val senderDeviceId: String,
    val targetDeviceId: String,
    val issuedAtEpochMillis: Long,
    val ephemeralPublicKey: String,
)

/** Public ECDH keys only. No shared secret or raw AES key is ever encoded into an SMS. */
object PairingHandshakeMessageCodec {
    private const val PREFIX = "PMPAIR1|"
    private const val MAX_BODY_CHARS = 1024
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun encode(message: PairingHandshakeMessage): String? {
        if (!validSessionId(message.sessionId) || !validIdentifier(message.senderDeviceId) ||
            !validIdentifier(message.targetDeviceId) || message.issuedAtEpochMillis <= 0L
        ) return null
        val publicKey = decodePublicKey(message.ephemeralPublicKey) ?: return null
        val type = if (message.type == PairingHandshakeMessageType.OFFER) "O" else "A"
        val body = listOf(
            "PMPAIR1", type, message.sessionId, message.senderDeviceId, message.targetDeviceId,
            message.issuedAtEpochMillis.toString(), encodeBase64(publicKey),
        ).joinToString("|")
        return body.takeIf { it.length <= MAX_BODY_CHARS }
    }

    fun parse(body: String): PairingHandshakeMessage? {
        if (body.length !in PREFIX.length..MAX_BODY_CHARS || body.any { it == '\r' || it == '\n' } || !body.startsWith(PREFIX)) return null
        val fields = body.split('|', limit = 7)
        if (fields.size != 7 || fields[0] != "PMPAIR1") return null
        val type = when (fields[1]) {
            "O" -> PairingHandshakeMessageType.OFFER
            "A" -> PairingHandshakeMessageType.ANSWER
            else -> return null
        }
        val sessionId = fields[2]
        val sender = fields[3]
        val target = fields[4]
        val issuedAt = fields[5].toLongOrNull()?.takeIf { it > 0L } ?: return null
        if (!validSessionId(sessionId) || !validIdentifier(sender) || !validIdentifier(target)) return null
        val publicKeyBytes = decodeBase64(fields[6], MAX_PUBLIC_KEY_BYTES) ?: return null
        if (publicKeyBytes.size !in MIN_PUBLIC_KEY_BYTES..MAX_PUBLIC_KEY_BYTES ||
            encodeBase64(publicKeyBytes) != fields[6] || !isP256PublicKey(publicKeyBytes)
        ) return null
        return PairingHandshakeMessage(type, sessionId, sender, target, issuedAt, fields[6])
    }

    internal fun decodePublicKey(encoded: String): ByteArray? {
        val bytes = decodeBase64(encoded, MAX_PUBLIC_KEY_BYTES) ?: return null
        return bytes.takeIf { it.size in MIN_PUBLIC_KEY_BYTES..MAX_PUBLIC_KEY_BYTES && isP256PublicKey(it) }
    }

    internal fun encodeBase64(bytes: ByteArray) = Base64.encodeToString(bytes, BASE64_FLAGS)

    internal fun decodeBase64(value: String, maxBytes: Int): ByteArray? = try {
        Base64.decode(value, BASE64_FLAGS).takeIf { it.size <= maxBytes }
    } catch (_: IllegalArgumentException) {
        null
    }

    internal fun validIdentifier(value: String): Boolean = value.length in 1..64 && value.all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
    }

    private fun validSessionId(value: String): Boolean {
        val decoded = decodeBase64(value, SESSION_ID_BYTES) ?: return false
        return decoded.size == SESSION_ID_BYTES && encodeBase64(decoded) == value
    }

    private fun isP256PublicKey(encoded: ByteArray): Boolean {
        return try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as? java.security.interfaces.ECPublicKey
                ?: return false
            val expected = AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
            key.params.curve.field.fieldSize == 256 &&
                key.params.curve == expected.curve &&
                key.params.generator == expected.generator &&
                key.params.order == expected.order &&
                key.params.cofactor == expected.cofactor &&
                key.w.affineX >= java.math.BigInteger.ZERO && key.w.affineX < expected.order &&
                key.w.affineY >= java.math.BigInteger.ZERO && key.w.affineY < expected.order
        } catch (_: Exception) {
            false
        }
    }

    private const val SESSION_ID_BYTES = 16
    private const val MIN_PUBLIC_KEY_BYTES = 80
    private const val MAX_PUBLIC_KEY_BYTES = 256
}

/**
 * Candidate material exists only in memory until the owner compares the same full fingerprint
 * out of band on both devices. A process restart simply requires restarting pairing.
 */
class PendingPairingCandidate internal constructor(
    val sessionId: String,
    val peerDeviceId: String,
    val peerPhoneNumber: String,
    val fingerprintHex: String,
    val expiresAtEpochMillis: Long,
    private val sharedKey: ByteArray,
) {
    private var consumed = false

    @Synchronized
    fun approveAfterOutOfBandComparison(
        keyStore: ApprovedPairingKeyStore,
        pairingId: String,
        localFingerprint: String,
        peerFingerprint: String,
        explicitlyConfirmedByUser: Boolean,
        nowEpochMillis: Long,
    ): Boolean {
        if (consumed || nowEpochMillis > expiresAtEpochMillis) return false
        if (PairingApproval.fingerprint(sharedKey) != fingerprintHex) return false
        val approval = PairingApproval.compareFingerprints(
            sharedKey,
            localFingerprint,
            peerFingerprint,
            explicitlyConfirmedByUser,
        ) ?: return false
        val stored = keyStore.approvePairing(pairingId, peerDeviceId, peerPhoneNumber, sharedKey, approval)
        if (stored) {
            sharedKey.fill(0)
            consumed = true
        }
        return stored
    }

    @Synchronized
    fun discard() {
        sharedKey.fill(0)
        consumed = true
    }
}

data class PairingResponderResult(
    val answerBody: String,
    val candidate: PendingPairingCandidate,
)

/**
 * P-256 ECDH + RFC 5869 HKDF-SHA256. This helper prepares pending trust only: applications still
 * need an explicit local UI step for the owner to compare fingerprints and approve the candidate.
 */
object P256PairingHandshake {
    private const val MAX_AGE_MS = 5 * 60 * 1000L
    private const val FUTURE_TOLERANCE_MS = 60 * 1000L
    private const val SESSION_ID_BYTES = 16
    private const val SHARED_KEY_BYTES = 32
    private const val CURVE_NAME = "secp256r1"
    private const val HKDF_MAC = "HmacSHA256"
    private const val HKDF_INFO_PREFIX = "PMPAIR1|ECDH-P256|"
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun createOffer(
        localDeviceId: String,
        peerDeviceId: String,
        localPhoneNumber: String,
        peerPhoneNumber: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        secureRandom: SecureRandom = SecureRandom(),
    ): PairingInitiatorSession? {
        val localPhone = normalizeE164(localPhoneNumber) ?: return null
        val remotePhone = normalizeE164(peerPhoneNumber) ?: return null
        if (!PairingHandshakeMessageCodec.validIdentifier(localDeviceId) ||
            !PairingHandshakeMessageCodec.validIdentifier(peerDeviceId) || localDeviceId == peerDeviceId ||
            nowEpochMillis <= 0L
        ) return null
        return try {
            val keyPair = generateEphemeralKeyPair(secureRandom)
            val sessionId = ByteArray(SESSION_ID_BYTES).also(secureRandom::nextBytes)
                .let(PairingHandshakeMessageCodec::encodeBase64)
            val message = PairingHandshakeMessage(
                PairingHandshakeMessageType.OFFER,
                sessionId,
                localDeviceId,
                peerDeviceId,
                nowEpochMillis,
                PairingHandshakeMessageCodec.encodeBase64(keyPair.public.encoded),
            )
            val body = PairingHandshakeMessageCodec.encode(message) ?: return null
            PairingInitiatorSession(
                message,
                body,
                keyPair,
                localPhone,
                remotePhone,
                nowEpochMillis + MAX_AGE_MS,
            )
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    fun acceptOffer(
        offerBody: String,
        smsOriginatingAddress: String,
        localDeviceId: String,
        localPhoneNumber: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        secureRandom: SecureRandom = SecureRandom(),
    ): PairingResponderResult? {
        val offer = PairingHandshakeMessageCodec.parse(offerBody) ?: return null
        if (offer.type != PairingHandshakeMessageType.OFFER || offer.targetDeviceId != localDeviceId ||
            !isFresh(offer.issuedAtEpochMillis, nowEpochMillis)
        ) return null
        val localPhone = normalizeE164(localPhoneNumber) ?: return null
        val peerPhone = normalizeE164(smsOriginatingAddress) ?: return null
        return try {
            val remotePublicKey = decodePeerKey(offer.ephemeralPublicKey) ?: return null
            val localKeyPair = generateEphemeralKeyPair(secureRandom)
            val sessionIdBytes = PairingHandshakeMessageCodec.decodeBase64(offer.sessionId, SESSION_ID_BYTES)
                ?.takeIf { it.size == SESSION_ID_BYTES } ?: return null
            val derivedKey = deriveSharedKey(localKeyPair, remotePublicKey, sessionIdBytes, offer.senderDeviceId, peerPhone, localDeviceId, localPhone)
            val answer = PairingHandshakeMessage(
                PairingHandshakeMessageType.ANSWER,
                offer.sessionId,
                localDeviceId,
                offer.senderDeviceId,
                nowEpochMillis,
                PairingHandshakeMessageCodec.encodeBase64(localKeyPair.public.encoded),
            )
            val answerBody = PairingHandshakeMessageCodec.encode(answer) ?: return null
            PairingResponderResult(
                answerBody,
                PendingPairingCandidate(
                    offer.sessionId,
                    offer.senderDeviceId,
                    peerPhone,
                    PairingApproval.fingerprint(derivedKey),
                    nowEpochMillis + MAX_AGE_MS,
                    derivedKey,
                ),
            )
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun generateEphemeralKeyPair(random: SecureRandom): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(CURVE_NAME), random)
        }.generateKeyPair()

    private fun decodePeerKey(encoded: String): java.security.PublicKey? {
        val bytes = PairingHandshakeMessageCodec.decodePublicKey(encoded) ?: return null
        return try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun deriveSharedKey(
        localKeyPair: KeyPair,
        remotePublicKey: java.security.PublicKey,
        sessionId: ByteArray,
        firstDeviceId: String,
        firstPhone: String,
        secondDeviceId: String,
        secondPhone: String,
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localKeyPair.private)
        agreement.doPhase(remotePublicKey, true)
        val ecdhSecret = agreement.generateSecret()
        val identities = listOf(
            "$firstDeviceId|$firstPhone",
            "$secondDeviceId|$secondPhone",
        ).sorted()
        val info = (HKDF_INFO_PREFIX + identities.joinToString("|")).toByteArray(StandardCharsets.UTF_8)
        val derived = hkdfSha256(ecdhSecret, sessionId, info, SHARED_KEY_BYTES)
        ecdhSecret.fill(0)
        return derived
    }

    private fun hkdfSha256(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance(HKDF_MAC)
        extract.init(SecretKeySpec(salt, HKDF_MAC))
        val pseudorandomKey = extract.doFinal(inputKeyMaterial)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val expand = Mac.getInstance(HKDF_MAC)
            expand.init(SecretKeySpec(pseudorandomKey, HKDF_MAC))
            expand.update(previous)
            expand.update(info)
            expand.update(counter.toByte())
            previous = expand.doFinal()
            val copyLength = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, output, offset, copyLength)
            offset += copyLength
            counter++
        }
        pseudorandomKey.fill(0)
        previous.fill(0)
        return output
    }

    private fun normalizeE164(value: String): String? {
        val normalized = PhoneNumberUtils.normalizeNumber(value)
        if (!normalized.startsWith('+')) return null
        return normalized.takeIf { it.count { char -> char.isDigit() } in 8..15 && it.drop(1).all { char -> char.isDigit() } }
    }

    private fun isFresh(issuedAt: Long, now: Long) =
        issuedAt > 0L && issuedAt <= now + FUTURE_TOLERANCE_MS && issuedAt >= now - MAX_AGE_MS

    class PairingInitiatorSession internal constructor(
        private val offer: PairingHandshakeMessage,
        val offerBody: String,
        private val localKeyPair: KeyPair,
        private val localPhoneNumber: String,
        private val peerPhoneNumber: String,
        private val expiresAtEpochMillis: Long,
    ) {
        private var consumed = false

        @Synchronized
        fun acceptAnswer(
            answerBody: String,
            smsOriginatingAddress: String,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): PendingPairingCandidate? {
            if (consumed || nowEpochMillis > expiresAtEpochMillis) return null
            val answer = PairingHandshakeMessageCodec.parse(answerBody) ?: return null
            if (answer.type != PairingHandshakeMessageType.ANSWER || answer.sessionId != offer.sessionId ||
                answer.senderDeviceId != offer.targetDeviceId || answer.targetDeviceId != offer.senderDeviceId ||
                !isFresh(answer.issuedAtEpochMillis, nowEpochMillis)
            ) return null
            val actualPeerPhone = normalizeE164(smsOriginatingAddress) ?: return null
            if (actualPeerPhone != peerPhoneNumber) return null
            val remotePublicKey = decodePeerKey(answer.ephemeralPublicKey) ?: return null
            val sessionIdBytes = PairingHandshakeMessageCodec.decodeBase64(offer.sessionId, SESSION_ID_BYTES)
                ?.takeIf { it.size == SESSION_ID_BYTES } ?: return null
            return try {
                val derivedKey = deriveSharedKey(
                    localKeyPair,
                    remotePublicKey,
                    sessionIdBytes,
                    offer.senderDeviceId,
                    localPhoneNumber,
                    answer.senderDeviceId,
                    actualPeerPhone,
                )
                consumed = true
                PendingPairingCandidate(
                    offer.sessionId,
                    answer.senderDeviceId,
                    actualPeerPhone,
                    PairingApproval.fingerprint(derivedKey),
                    expiresAtEpochMillis,
                    derivedKey,
                )
            } catch (_: GeneralSecurityException) {
                null
            }
        }
    }
}
