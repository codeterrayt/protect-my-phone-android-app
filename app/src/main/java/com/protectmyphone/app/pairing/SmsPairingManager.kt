package com.protectmyphone.app.pairing

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.protectmyphone.app.security.ApprovedPairingKeyStore
import com.protectmyphone.app.security.PairingApproval
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Builds a two-message, SMS-carried ECDH ceremony. This class never sends SMS itself: callers
 * must show the message and let the owner explicitly send it. Receiving PAIR1 is passive until
 * [acceptPair1] is called, and neither SMS message alone approves a command key.
 *
 * The owner must compare the full fingerprint with the other phone over an independent channel
 * before calling [confirmFingerprint]. SMS sender numbers and message contents are not identity
 * proofs and can be spoofed or intercepted.
 */
class SmsPairingManager(
    private val localDeviceId: String,
    private val localDeviceLabel: String,
    private val keyStore: ApprovedPairingKeyStore,
    private val pendingStateStore: PendingPairingStateStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val states = LinkedHashMap<String, PairingState>()
    @Volatile
    private var storageAvailable = true

    init {
        val loaded = try {
            pendingStateStore.load()
        } catch (_: Exception) {
            PendingPairingStateLoadResult.Unavailable
        }
        when (loaded) {
            PendingPairingStateLoadResult.Empty -> Unit
            PendingPairingStateLoadResult.Unavailable -> {
                storageAvailable = try {
                    pendingStateStore.clear()
                } catch (_: Exception) {
                    false
                }
                if (!storageAvailable) Log.e(TAG, "Could not clear unreadable pending pairing state")
            }
            is PendingPairingStateLoadResult.Present -> {
                try {
                    if (!restoreStates(loaded.serializedState)) {
                        clearPendingStates()
                        storageAvailable = try {
                            pendingStateStore.clear()
                        } catch (_: Exception) {
                            false
                        }
                        if (!storageAvailable) Log.e(TAG, "Could not clear invalid pending pairing state")
                    }
                } finally {
                    loaded.serializedState.fill(0)
                }
            }
        }
    }

    /** False means pending ceremonies cannot be safely read or persisted; pairing fails closed. */
    fun isStorageAvailable(): Boolean = storageAvailable

    /** Prepare PAIR1. The caller must get explicit owner intent before opening an SMS composer. */
    @Synchronized
    fun preparePair1(peerPhoneNumber: String): PairingPreparationResult {
        pruneExpired()
        if (!storageAvailable) return PairingPreparationResult.Rejected(PairingFailure.PENDING_STORAGE_UNAVAILABLE)
        if (!PairingSmsCodec.isValidIdentifier(localDeviceId)) {
            return PairingPreparationResult.Rejected(PairingFailure.INVALID_DEVICE_ID)
        }
        val label = PairingSmsCodec.normalizeLabel(localDeviceLabel)
            ?: return PairingPreparationResult.Rejected(PairingFailure.INVALID_DEVICE_LABEL)
        val phone = PairingSmsCodec.normalizePhone(peerPhoneNumber)
            ?: return PairingPreparationResult.Rejected(PairingFailure.INVALID_PHONE_NUMBER)
        if (states.size >= MAX_PENDING_SESSIONS) {
            return PairingPreparationResult.Rejected(PairingFailure.TOO_MANY_PENDING_SESSIONS)
        }

        return try {
            val keyPair = generateP256KeyPair(secureRandom)
            val nonce = randomBytes(PairingSmsCodec.NONCE_BYTES, secureRandom)
            val sessionId = PairingSmsCodec.encodeBase64(randomBytes(PairingSmsCodec.SESSION_ID_BYTES, secureRandom))
            val message = Pair1Message(
                sessionId = sessionId,
                deviceId = localDeviceId,
                label = label,
                nonce = PairingSmsCodec.encodeBase64(nonce),
                publicKey = PairingSmsCodec.encodeBase64(keyPair.public.encoded),
            )
            val body = PairingSmsCodec.encodePair1(message)
                ?: return PairingPreparationResult.Rejected(PairingFailure.CRYPTOGRAPHIC_FAILURE)
            states[sessionId] = PairingState.OutgoingAwaitingPair2(
                message = message,
                body = body,
                keyPair = keyPair,
                peerPhoneNumber = phone,
                createdAtMillis = clock(),
            )
            if (!persistStates()) {
                states.remove(sessionId)
                return PairingPreparationResult.Rejected(PairingFailure.PENDING_STORAGE_UNAVAILABLE)
            }
            PairingPreparationResult.ReadyToSend(
                SmsPairingMessage(sessionId = sessionId, destinationPhoneNumber = phone, body = body),
            )
        } catch (_: GeneralSecurityException) {
            PairingPreparationResult.Rejected(PairingFailure.CRYPTOGRAPHIC_FAILURE)
        } catch (_: IllegalArgumentException) {
            PairingPreparationResult.Rejected(PairingFailure.CRYPTOGRAPHIC_FAILURE)
        }
    }

    /**
     * Record an incoming PAIR1 for display. This does not generate PAIR2, derive a key, or send
     * any response. The receiving owner must explicitly accept with [acceptPair1].
     */
    @Synchronized
    fun receivePair1(senderPhoneNumber: String, body: String): Pair1ReceiveResult {
        pruneExpired()
        if (!storageAvailable) return Pair1ReceiveResult.Rejected(PairingFailure.PENDING_STORAGE_UNAVAILABLE)
        val phone = PairingSmsCodec.normalizePhone(senderPhoneNumber)
            ?: return Pair1ReceiveResult.Rejected(PairingFailure.INVALID_PHONE_NUMBER)
        val message = PairingSmsCodec.parsePair1(body)
            ?: return Pair1ReceiveResult.Rejected(PairingFailure.MALFORMED_PAIR1)
        if (message.deviceId == localDeviceId) {
            return Pair1ReceiveResult.Rejected(PairingFailure.SELF_PAIRING_NOT_ALLOWED)
        }

        val existing = states[message.sessionId]
        if (existing != null) {
            if (existing is PairingState.IncomingAwaitingAcceptance &&
                existing.peerPhoneNumber == phone && existing.body == body
            ) return Pair1ReceiveResult.Pending(existing.toPublicRequest())
            return Pair1ReceiveResult.Rejected(PairingFailure.DUPLICATE_OR_CONFLICTING_SESSION)
        }
        if (states.size >= MAX_PENDING_SESSIONS) {
            return Pair1ReceiveResult.Rejected(PairingFailure.TOO_MANY_PENDING_SESSIONS)
        }

        val createdAt = clock()
        val pending = PairingState.IncomingAwaitingAcceptance(
            message = message,
            body = body,
            peerPhoneNumber = phone,
            createdAtMillis = createdAt,
        )
        states[message.sessionId] = pending
        if (!persistStates()) {
            states.remove(message.sessionId)
            return Pair1ReceiveResult.Rejected(PairingFailure.PENDING_STORAGE_UNAVAILABLE)
        }
        return Pair1ReceiveResult.Pending(pending.toPublicRequest())
    }

    /**
     * Owner-accepted PAIR1: derive a pending key and prepare PAIR2 for an explicit SMS send.
     * The key is still unusable for commands until the fingerprint is explicitly confirmed.
     */
    @Synchronized
    fun acceptPair1(sessionId: String): PairingAcceptResult {
        pruneExpired()
        if (!storageAvailable) return PairingAcceptResult.Rejected(PairingFailure.PENDING_STORAGE_UNAVAILABLE)
        val pending = states[sessionId] as? PairingState.IncomingAwaitingAcceptance
            ?: return PairingAcceptResult.Rejected(PairingFailure.UNKNOWN_OR_EXPIRED_SESSION)
        return try {
            val keyPair = generateP256KeyPair(secureRandom)
            val responder = Pair2Message(
                sessionId = pending.message.sessionId,
                deviceId = localDeviceId,
                label = PairingSmsCodec.normalizeLabel(localDeviceLabel)
                    ?: return PairingAcceptResult.Rejected(PairingFailure.INVALID_DEVICE_LABEL),
                nonce = PairingSmsCodec.encodeBase64(randomBytes(PairingSmsCodec.NONCE_BYTES, secureRandom)),
                publicKey = PairingSmsCodec.encodeBase64(keyPair.public.encoded),
            )
            val responseBody = PairingSmsCodec.encodePair2(responder)
                ?: return PairingAcceptResult.Rejected(PairingFailure.CRYPTOGRAPHIC_FAILURE)
            val sharedKey = deriveSharedKey(
                localPrivateKey = keyPair,
                peerPublicKey = PairingSmsCodec.decodePublicKey(pending.message.publicKey)
                    ?: return PairingAcceptResult.Rejected(PairingFailure.MALFORMED_PAIR1),
                pair1Body = pending.body,
                pair2Body = responseBody,
                pair1 = pending.message,
                pair2 = responder,
            )
            val confirmation = confirmation(
                sessionId = sessionId,
                peerDeviceId = pending.message.deviceId,
                peerLabel = pending.message.label,
                peerPhoneNumber = pending.peerPhoneNumber,
                sharedKey = sharedKey,
            )
            states[sessionId] = PairingState.AwaitingFingerprint(
                confirmation = confirmation,
                sharedKey = sharedKey,
                createdAtMillis = pending.createdAtMillis,
            )
            if (!persistStates()) {
                states[sessionId] = pending
                sharedKey.fill(0)
                return PairingAcceptResult.Rejected(PairingFailure.PENDING_STORAGE_UNAVAILABLE)
            }
            PairingAcceptResult.Accepted(
                response = SmsPairingMessage(sessionId, pending.peerPhoneNumber, responseBody),
                fingerprint = confirmation,
            )
        } catch (_: GeneralSecurityException) {
            PairingAcceptResult.Rejected(PairingFailure.CRYPTOGRAPHIC_FAILURE)
        } catch (_: IllegalArgumentException) {
            PairingAcceptResult.Rejected(PairingFailure.CRYPTOGRAPHIC_FAILURE)
        }
    }

    /** Receive PAIR2 for an owner-initiated request; this only stages a confirmation challenge. */
    @Synchronized
    fun receivePair2(senderPhoneNumber: String, body: String): Pair2ReceiveResult {
        pruneExpired()
        if (!storageAvailable) return Pair2ReceiveResult.Rejected(PairingFailure.PENDING_STORAGE_UNAVAILABLE)
        val phone = PairingSmsCodec.normalizePhone(senderPhoneNumber)
            ?: return Pair2ReceiveResult.Rejected(PairingFailure.INVALID_PHONE_NUMBER)
        val message = PairingSmsCodec.parsePair2(body)
            ?: return Pair2ReceiveResult.Rejected(PairingFailure.MALFORMED_PAIR2)
        val pending = states[message.sessionId] as? PairingState.OutgoingAwaitingPair2
            ?: return Pair2ReceiveResult.Rejected(PairingFailure.UNKNOWN_OR_EXPIRED_SESSION)
        if (pending.peerPhoneNumber != phone) {
            return Pair2ReceiveResult.Rejected(PairingFailure.WRONG_SENDER)
        }
        if (message.deviceId == localDeviceId) {
            return Pair2ReceiveResult.Rejected(PairingFailure.SELF_PAIRING_NOT_ALLOWED)
        }

        return try {
            val sharedKey = deriveSharedKey(
                localPrivateKey = pending.keyPair,
                peerPublicKey = PairingSmsCodec.decodePublicKey(message.publicKey)
                    ?: return Pair2ReceiveResult.Rejected(PairingFailure.MALFORMED_PAIR2),
                pair1Body = pending.body,
                pair2Body = body,
                pair1 = pending.message,
                pair2 = message,
            )
            val confirmation = confirmation(
                sessionId = message.sessionId,
                peerDeviceId = message.deviceId,
                peerLabel = message.label,
                peerPhoneNumber = phone,
                sharedKey = sharedKey,
            )
            states[message.sessionId] = PairingState.AwaitingFingerprint(
                confirmation = confirmation,
                sharedKey = sharedKey,
                createdAtMillis = pending.createdAtMillis,
            )
            if (!persistStates()) {
                states[message.sessionId] = pending
                sharedKey.fill(0)
                return Pair2ReceiveResult.Rejected(PairingFailure.PENDING_STORAGE_UNAVAILABLE)
            }
            Pair2ReceiveResult.AwaitingConfirmation(confirmation)
        } catch (_: GeneralSecurityException) {
            Pair2ReceiveResult.Rejected(PairingFailure.CRYPTOGRAPHIC_FAILURE)
        } catch (_: IllegalArgumentException) {
            Pair2ReceiveResult.Rejected(PairingFailure.CRYPTOGRAPHIC_FAILURE)
        }
    }

    /**
     * Store the key only when both entered fingerprints match its SHA-256 fingerprint and the
     * caller represents an explicit owner confirmation. A pending PAIR1/PAIR2 never authorizes
     * commands. This method passes the verified approval token to the approved-key store.
     */
    @Synchronized
    fun confirmFingerprint(
        sessionId: String,
        localFingerprint: String,
        peerFingerprint: String,
        explicitlyConfirmedByUser: Boolean,
    ): PairingConfirmationResult {
        pruneExpired()
        if (!storageAvailable) return PairingConfirmationResult.Rejected(PairingFailure.PENDING_STORAGE_UNAVAILABLE)
        val pending = states[sessionId] as? PairingState.AwaitingFingerprint
            ?: return PairingConfirmationResult.Rejected(PairingFailure.UNKNOWN_OR_EXPIRED_SESSION)
        val approval = PairingApproval.compareFingerprints(
            sharedKey = pending.sharedKey,
            localFingerprint = localFingerprint,
            peerFingerprint = peerFingerprint,
            explicitlyConfirmedByUser = explicitlyConfirmedByUser,
        ) ?: return PairingConfirmationResult.Rejected(
            if (explicitlyConfirmedByUser) PairingFailure.FINGERPRINT_MISMATCH
            else PairingFailure.USER_CONFIRMATION_REQUIRED,
        )

        val stored = try {
            keyStore.approvePairing(
                pairingId = pending.confirmation.pairingId,
                peerDeviceId = pending.confirmation.peerDeviceId,
                peerPhoneNumber = pending.confirmation.peerPhoneNumber,
                sharedKey = pending.sharedKey,
                approval = approval,
            )
        } catch (_: Exception) {
            false
        }
        if (!stored) return PairingConfirmationResult.Rejected(PairingFailure.KEY_STORE_REJECTED)

        pending.sharedKey.fill(0)
        states.remove(sessionId)
        if (!persistStates()) {
            // The approved key is already durably stored. Drop all staged material fail-closed;
            // a stale encrypted pending snapshot may at worst require another explicit compare.
            states.values.filterIsInstance<PairingState.AwaitingFingerprint>().forEach { it.sharedKey.fill(0) }
            states.clear()
            storageAvailable = pendingStateStore.clear()
        }
        return PairingConfirmationResult.Paired(pending.confirmation)
    }

    /** A screen can list pending inbound requests without waking a transport or sending a reply. */
    @Synchronized
    fun pendingRequests(): List<IncomingPairingRequest> {
        pruneExpired()
        if (!storageAvailable) return emptyList()
        return states.values.filterIsInstance<PairingState.IncomingAwaitingAcceptance>()
            .map { it.toPublicRequest() }
    }

    @Synchronized
    fun pendingFingerprintConfirmations(): List<PairingFingerprintConfirmation> {
        pruneExpired()
        if (!storageAvailable) return emptyList()
        return states.values.filterIsInstance<PairingState.AwaitingFingerprint>()
            .map { it.confirmation }
    }

    /** Drop a pending ceremony and erase staged derived key bytes, if any. */
    @Synchronized
    fun cancel(sessionId: String): Boolean {
        val removed = states.remove(sessionId) ?: return false
        if (removed is PairingState.AwaitingFingerprint) removed.sharedKey.fill(0)
        return persistStates()
    }

    private fun confirmation(
        sessionId: String,
        peerDeviceId: String,
        peerLabel: String,
        peerPhoneNumber: String,
        sharedKey: ByteArray,
    ): PairingFingerprintConfirmation {
        val full = PairingApproval.fingerprint(sharedKey)
        return PairingFingerprintConfirmation(
            sessionId = sessionId,
            pairingId = sessionId,
            peerDeviceId = peerDeviceId,
            peerLabel = peerLabel,
            peerPhoneNumber = peerPhoneNumber,
            fullFingerprint = full,
            shortFingerprint = full.take(SHORT_FINGERPRINT_HEX_CHARS),
        )
    }

    private fun pruneExpired() {
        val now = clock()
        val expired = states.filterValues { now - it.createdAtMillis > SESSION_LIFETIME_MILLIS }.keys
        if (expired.isEmpty()) return
        expired.forEach { sessionId ->
            (states.remove(sessionId) as? PairingState.AwaitingFingerprint)?.sharedKey?.fill(0)
        }
        if (!persistStates()) storageAvailable = false
    }

    private fun persistStates(): Boolean {
        val payload = serializeStates() ?: run {
            storageAvailable = false
            return false
        }
        val persisted = try {
            pendingStateStore.save(payload)
        } catch (_: RuntimeException) {
            false
        }
        storageAvailable = persisted
        payload.fill(0)
        return persisted
    }

    private fun clearPendingStates() {
        states.values.filterIsInstance<PairingState.AwaitingFingerprint>().forEach { it.sharedKey.fill(0) }
        states.clear()
    }

    private fun serializeStates(): ByteArray? = try {
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(PENDING_STATE_MAGIC)
                output.writeInt(PENDING_STATE_VERSION)
                output.writeInt(states.size)
                if (states.size > MAX_PENDING_SESSIONS) return null
                states.values.forEach { state ->
                    output.writeLong(state.createdAtMillis)
                    when (state) {
                        is PairingState.OutgoingAwaitingPair2 -> {
                            output.writeByte(STATE_OUTGOING)
                            output.writeUTF(state.body)
                            writeBoundedBytes(output, state.keyPair.private.encoded ?: return null, MAX_PRIVATE_KEY_BYTES)
                            output.writeUTF(state.peerPhoneNumber)
                        }
                        is PairingState.IncomingAwaitingAcceptance -> {
                            output.writeByte(STATE_INCOMING)
                            output.writeUTF(state.body)
                            output.writeUTF(state.peerPhoneNumber)
                        }
                        is PairingState.AwaitingFingerprint -> {
                            output.writeByte(STATE_CONFIRMATION)
                            output.writeUTF(state.confirmation.sessionId)
                            output.writeUTF(state.confirmation.peerDeviceId)
                            output.writeUTF(state.confirmation.peerLabel)
                            output.writeUTF(state.confirmation.peerPhoneNumber)
                            writeBoundedBytes(output, state.sharedKey, SHARED_KEY_BYTES)
                        }
                    }
                }
                output.flush()
                bytes.toByteArray().takeIf { it.size <= MAX_PENDING_STATE_BYTES }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun restoreStates(serialized: ByteArray): Boolean {
        if (serialized.isEmpty() || serialized.size > MAX_PENDING_STATE_BYTES) return false
        return try {
            DataInputStream(ByteArrayInputStream(serialized)).use { input ->
                if (input.readInt() != PENDING_STATE_MAGIC || input.readInt() != PENDING_STATE_VERSION) return false
                val count = input.readInt()
                if (count !in 0..MAX_PENDING_SESSIONS) return false
                repeat(count) {
                    val createdAt = input.readLong()
                    val stateTag = input.readUnsignedByte()
                    if (createdAt <= 0L || createdAt > clock() + FUTURE_STATE_TOLERANCE_MILLIS) return false
                    if (clock() - createdAt > SESSION_LIFETIME_MILLIS) {
                        skipExpiredState(input, stateTag)
                        return@repeat
                    }
                    when (stateTag) {
                        STATE_OUTGOING -> {
                            val body = input.readUTF()
                            val pair1 = PairingSmsCodec.parsePair1(body) ?: return false
                            val privateBytes = readBoundedBytes(input, MAX_PRIVATE_KEY_BYTES)
                            val phone = PairingSmsCodec.normalizePhone(input.readUTF()) ?: return false
                            val privateKey = try {
                                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
                            } finally {
                                privateBytes.fill(0)
                            }
                            val privateParams = (privateKey as? ECPrivateKey)?.params ?: return false
                            val publicKey = PairingSmsCodec.decodePublicKey(pair1.publicKey) ?: return false
                            if (!PairingSmsCodec.isP256(privateParams) || !keyPairMatches(privateKey, publicKey)) return false
                            putRestored(pair1.sessionId, PairingState.OutgoingAwaitingPair2(
                                message = pair1,
                                body = body,
                                keyPair = KeyPair(publicKey, privateKey),
                                peerPhoneNumber = phone,
                                createdAtMillis = createdAt,
                            ))
                        }
                        STATE_INCOMING -> {
                            val body = input.readUTF()
                            val pair1 = PairingSmsCodec.parsePair1(body) ?: return false
                            val phone = PairingSmsCodec.normalizePhone(input.readUTF()) ?: return false
                            putRestored(pair1.sessionId, PairingState.IncomingAwaitingAcceptance(
                                message = pair1,
                                body = body,
                                peerPhoneNumber = phone,
                                createdAtMillis = createdAt,
                            ))
                        }
                        STATE_CONFIRMATION -> {
                            val sessionId = input.readUTF().takeIf(PairingSmsCodec::isValidSessionId) ?: return false
                            val peerDeviceId = input.readUTF().takeIf(PairingSmsCodec::isValidIdentifier) ?: return false
                            val peerLabel = PairingSmsCodec.normalizeLabel(input.readUTF()) ?: return false
                            val phone = PairingSmsCodec.normalizePhone(input.readUTF()) ?: return false
                            val sharedKey = readBoundedBytes(input, SHARED_KEY_BYTES)
                            if (sharedKey.size != SHARED_KEY_BYTES) {
                                sharedKey.fill(0)
                                return false
                            }
                            val fingerprint = confirmation(sessionId, peerDeviceId, peerLabel, phone, sharedKey)
                            putRestored(sessionId, PairingState.AwaitingFingerprint(
                                confirmation = fingerprint,
                                sharedKey = sharedKey,
                                createdAtMillis = createdAt,
                            ))
                        }
                        else -> return false
                    }
                }
                input.available() == 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun skipExpiredState(input: DataInputStream, stateTag: Int) {
        when (stateTag) {
            STATE_OUTGOING -> {
                input.readUTF()
                readBoundedBytes(input, MAX_PRIVATE_KEY_BYTES).fill(0)
                input.readUTF()
            }
            STATE_INCOMING -> {
                input.readUTF()
                input.readUTF()
            }
            STATE_CONFIRMATION -> {
                repeat(4) { input.readUTF() }
                readBoundedBytes(input, SHARED_KEY_BYTES).fill(0)
            }
            else -> throw IllegalArgumentException("Unknown pending pairing state")
        }
    }

    private fun putRestored(sessionId: String, state: PairingState) {
        require(states.put(sessionId, state) == null)
    }

    private fun keyPairMatches(privateKey: java.security.PrivateKey, publicKey: PublicKey): Boolean = try {
        val challenge = randomBytes(32, secureRandom)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey, secureRandom)
        signer.update(challenge)
        val signature = signer.sign()
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(challenge)
        challenge.fill(0)
        val valid = verifier.verify(signature)
        signature.fill(0)
        valid
    } catch (_: GeneralSecurityException) {
        false
    }

    private fun writeBoundedBytes(output: DataOutputStream, value: ByteArray, maxBytes: Int) {
        require(value.isNotEmpty() && value.size <= maxBytes)
        output.writeInt(value.size)
        output.write(value)
    }

    private fun readBoundedBytes(input: DataInputStream, maxBytes: Int): ByteArray {
        val size = input.readInt()
        require(size in 1..maxBytes)
        return ByteArray(size).also(input::readFully)
    }

    private sealed class PairingState(open val createdAtMillis: Long) {
        data class OutgoingAwaitingPair2(
            val message: Pair1Message,
            val body: String,
            val keyPair: KeyPair,
            val peerPhoneNumber: String,
            override val createdAtMillis: Long,
        ) : PairingState(createdAtMillis)

        data class IncomingAwaitingAcceptance(
            val message: Pair1Message,
            val body: String,
            val peerPhoneNumber: String,
            override val createdAtMillis: Long,
        ) : PairingState(createdAtMillis) {
            fun toPublicRequest() = IncomingPairingRequest(
                sessionId = message.sessionId,
                peerDeviceId = message.deviceId,
                peerLabel = message.label,
                peerPhoneNumber = peerPhoneNumber,
                receivedAtEpochMillis = createdAtMillis,
            )
        }

        data class AwaitingFingerprint(
            val confirmation: PairingFingerprintConfirmation,
            val sharedKey: ByteArray,
            override val createdAtMillis: Long,
        ) : PairingState(createdAtMillis)
    }

    companion object {
        private const val TAG = "PmpPairingStorage"
        private const val MAX_PENDING_SESSIONS = 8
        private const val SESSION_LIFETIME_MILLIS = 10 * 60 * 1000L
        private const val SHORT_FINGERPRINT_HEX_CHARS = 12
        private const val PENDING_STATE_MAGIC = 0x504D5053
        private const val PENDING_STATE_VERSION = 1
        private const val STATE_OUTGOING = 1
        private const val STATE_INCOMING = 2
        private const val STATE_CONFIRMATION = 3
        private const val SHARED_KEY_BYTES = 32
        private const val MAX_PRIVATE_KEY_BYTES = 512
        private const val MAX_PENDING_STATE_BYTES = 32 * 1024
        private const val FUTURE_STATE_TOLERANCE_MILLIS = 60_000L
    }
}

data class SmsPairingMessage(val sessionId: String, val destinationPhoneNumber: String, val body: String)

data class IncomingPairingRequest(
    val sessionId: String,
    val peerDeviceId: String,
    val peerLabel: String,
    val peerPhoneNumber: String,
    val receivedAtEpochMillis: Long,
)

data class PairingFingerprintConfirmation(
    val sessionId: String,
    val pairingId: String,
    val peerDeviceId: String,
    val peerLabel: String,
    val peerPhoneNumber: String,
    /** Full 256-bit SHA-256 fingerprint, formatted as 64 uppercase hexadecimal characters. */
    val fullFingerprint: String,
    /** First 48 fingerprint bits for quick visual comparison; approval still requires the full value. */
    val shortFingerprint: String,
)

sealed interface PairingPreparationResult {
    data class ReadyToSend(val message: SmsPairingMessage) : PairingPreparationResult
    data class Rejected(val reason: PairingFailure) : PairingPreparationResult
}

sealed interface Pair1ReceiveResult {
    data class Pending(val request: IncomingPairingRequest) : Pair1ReceiveResult
    data class Rejected(val reason: PairingFailure) : Pair1ReceiveResult
}

sealed interface PairingAcceptResult {
    data class Accepted(
        val response: SmsPairingMessage,
        val fingerprint: PairingFingerprintConfirmation,
    ) : PairingAcceptResult
    data class Rejected(val reason: PairingFailure) : PairingAcceptResult
}

sealed interface Pair2ReceiveResult {
    data class AwaitingConfirmation(val fingerprint: PairingFingerprintConfirmation) : Pair2ReceiveResult
    data class Rejected(val reason: PairingFailure) : Pair2ReceiveResult
}

sealed interface PairingConfirmationResult {
    data class Paired(val pairing: PairingFingerprintConfirmation) : PairingConfirmationResult
    data class Rejected(val reason: PairingFailure) : PairingConfirmationResult
}

enum class PairingFailure {
    INVALID_DEVICE_ID,
    INVALID_DEVICE_LABEL,
    INVALID_PHONE_NUMBER,
    PENDING_STORAGE_UNAVAILABLE,
    MALFORMED_PAIR1,
    MALFORMED_PAIR2,
    SELF_PAIRING_NOT_ALLOWED,
    DUPLICATE_OR_CONFLICTING_SESSION,
    TOO_MANY_PENDING_SESSIONS,
    UNKNOWN_OR_EXPIRED_SESSION,
    WRONG_SENDER,
    CRYPTOGRAPHIC_FAILURE,
    USER_CONFIRMATION_REQUIRED,
    FINGERPRINT_MISMATCH,
    KEY_STORE_REJECTED,
}

private interface PairingWireMessage {
    val sessionId: String
    val deviceId: String
    val label: String
    val nonce: String
    val publicKey: String
}

private data class Pair1Message(
    override val sessionId: String,
    override val deviceId: String,
    override val label: String,
    override val nonce: String,
    override val publicKey: String,
) : PairingWireMessage

private data class Pair2Message(
    override val sessionId: String,
    override val deviceId: String,
    override val label: String,
    override val nonce: String,
    override val publicKey: String,
) : PairingWireMessage

/** Strict, canonical, bounded wire codecs. The body contains public ephemeral keys only. */
private object PairingSmsCodec {
    const val SESSION_ID_BYTES = 16
    const val NONCE_BYTES = 16
    private const val MAX_BODY_CHARS = 768
    private const val MAX_LABEL_BYTES = 96
    private const val MAX_PUBLIC_KEY_BYTES = 256
    private val PHONE_PATTERN = Regex("\\+[1-9][0-9]{7,14}")

    fun encodePair1(message: Pair1Message): String? = encode("PAIR1", message)

    fun encodePair2(message: Pair2Message): String? = encode("PAIR2", message)

    fun parsePair1(body: String): Pair1Message? = parse("PAIR1", body)?.let {
        Pair1Message(it.sessionId, it.deviceId, it.label, it.nonce, it.publicKey)
    }

    fun parsePair2(body: String): Pair2Message? = parse("PAIR2", body)?.let {
        Pair2Message(it.sessionId, it.deviceId, it.label, it.nonce, it.publicKey)
    }

    fun decodePublicKey(encoded: String): PublicKey? {
        val bytes = decodeCanonicalBase64(encoded, maxBytes = MAX_PUBLIC_KEY_BYTES) ?: return null
        return try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
            val ecKey = key as? ECPublicKey ?: return null
            if (!isP256(ecKey.params)) return null
            key
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun encodeBase64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun isValidIdentifier(value: String): Boolean = value.length in 1..64 && value.all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
    }

    fun normalizePhone(value: String): String? = value.takeIf { PHONE_PATTERN.matches(it) }

    fun normalizeLabel(value: String): String? {
        if (value.any { it.isISOControl() }) return null
        val normalized = value.trim()
        val bytes = normalized.toByteArray(StandardCharsets.UTF_8)
        return normalized.takeIf { it.isNotEmpty() && bytes.size <= MAX_LABEL_BYTES }
    }

    private fun encode(prefix: String, message: PairingWireMessage): String? {
        if (!isValidSessionId(message.sessionId) || !isValidIdentifier(message.deviceId) ||
            normalizeLabel(message.label) != message.label ||
            decodeCanonicalBase64(message.nonce, expectedBytes = NONCE_BYTES) == null ||
            decodeCanonicalBase64(message.publicKey, maxBytes = MAX_PUBLIC_KEY_BYTES) == null ||
            decodePublicKey(message.publicKey) == null
        ) return null
        return listOf(
            prefix,
            "1",
            message.sessionId,
            message.deviceId,
            encodeBase64(message.label.toByteArray(StandardCharsets.UTF_8)),
            message.nonce,
            message.publicKey,
        ).joinToString("|").takeIf { it.length <= MAX_BODY_CHARS }
    }

    private fun parse(prefix: String, body: String): Pair1Message? {
        if (body.length !in 1..MAX_BODY_CHARS || body.any { it.code !in 0x21..0x7e }) return null
        val fields = body.split('|', limit = 8)
        if (fields.size != 7 || fields[0] != prefix || fields[1] != "1") return null
        val sessionId = fields[2].takeIf(::isValidSessionId) ?: return null
        val deviceId = fields[3].takeIf(::isValidIdentifier) ?: return null
        val labelBytes = decodeCanonicalBase64(fields[4], maxBytes = MAX_LABEL_BYTES)
            ?.takeIf { it.isNotEmpty() } ?: return null
        val label = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(labelBytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        if (normalizeLabel(label) != label || encodeBase64(label.toByteArray(StandardCharsets.UTF_8)) != fields[4]) return null
        decodeCanonicalBase64(fields[5], expectedBytes = NONCE_BYTES) ?: return null
        decodeCanonicalBase64(fields[6], maxBytes = MAX_PUBLIC_KEY_BYTES) ?: return null
        if (decodePublicKey(fields[6]) == null) return null
        return Pair1Message(sessionId, deviceId, label, fields[5], fields[6])
    }

    fun isValidSessionId(value: String): Boolean =
        decodeCanonicalBase64(value, expectedBytes = SESSION_ID_BYTES) != null

    private fun decodeCanonicalBase64(
        value: String,
        expectedBytes: Int? = null,
        maxBytes: Int = MAX_PUBLIC_KEY_BYTES,
    ): ByteArray? {
        if (value.isEmpty() || value.length > maxBytes * 2) return null
        val decoded = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.size > maxBytes || (expectedBytes != null && decoded.size != expectedBytes)) return null
        return decoded.takeIf { encodeBase64(it) == value }
    }

    fun isP256(params: ECParameterSpec): Boolean = try {
        val expected = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        params.curve == expected.curve && params.generator == expected.generator &&
            params.order == expected.order && params.cofactor == expected.cofactor
    } catch (_: GeneralSecurityException) {
        false
    }
}

/** Persistence must encrypt pending key material at rest; only approved keys use the approved store. */
interface PendingPairingStateStore {
    fun load(): PendingPairingStateLoadResult
    fun save(serializedState: ByteArray): Boolean
    fun clear(): Boolean
}

sealed interface PendingPairingStateLoadResult {
    data object Empty : PendingPairingStateLoadResult
    data object Unavailable : PendingPairingStateLoadResult
    data class Present(val serializedState: ByteArray) : PendingPairingStateLoadResult
}

/** Test-only or transient implementation. Production wiring should use [AndroidKeystorePendingPairingStateStore]. */
class InMemoryPendingPairingStateStore : PendingPairingStateStore {
    private var value: ByteArray? = null

    @Synchronized
    override fun load(): PendingPairingStateLoadResult =
        value?.let { PendingPairingStateLoadResult.Present(it.copyOf()) } ?: PendingPairingStateLoadResult.Empty

    @Synchronized
    override fun save(serializedState: ByteArray): Boolean {
        value?.fill(0)
        value = serializedState.copyOf()
        return true
    }

    @Synchronized
    override fun clear(): Boolean {
        value?.fill(0)
        value = null
        return true
    }
}

/**
 * Durable, app-private pending-state storage. Android Keystore owns the AES-GCM wrapping key;
 * SharedPreferences contains only nonce+ciphertext, never a private/shared pairing key in clear.
 */
class AndroidKeystorePendingPairingStateStore(context: Context) : PendingPairingStateStore {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): PendingPairingStateLoadResult {
        val value = preferences.getString(PENDING_VALUE_KEY, null)
            ?: return PendingPairingStateLoadResult.Empty
        if (value.length > MAX_ENCRYPTED_BASE64_CHARS) return PendingPairingStateLoadResult.Unavailable
        val wrapped = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            return PendingPairingStateLoadResult.Unavailable
        }
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(wrapped) != value ||
            wrapped.size !in (GCM_IV_BYTES + GCM_TAG_BITS / 8 + 1)..MAX_PENDING_CIPHERTEXT_BYTES
        ) return PendingPairingStateLoadResult.Unavailable
        return try {
            val key = existingWrappingKey() ?: return PendingPairingStateLoadResult.Unavailable
            val iv = wrapped.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = wrapped.copyOfRange(GCM_IV_BYTES, wrapped.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(PENDING_AAD)
            val plaintext = cipher.doFinal(ciphertext)
            if (plaintext.size !in 1..MAX_PENDING_STATE_BYTES) {
                plaintext.fill(0)
                PendingPairingStateLoadResult.Unavailable
            } else {
                PendingPairingStateLoadResult.Present(plaintext)
            }
        } catch (_: Exception) {
            Log.e(TAG, "Could not decrypt pending pairing state from Android Keystore")
            PendingPairingStateLoadResult.Unavailable
        } finally {
            wrapped.fill(0)
        }
    }

    @Synchronized
    override fun save(serializedState: ByteArray): Boolean {
        if (serializedState.isEmpty() || serializedState.size > MAX_PENDING_STATE_BYTES) {
            Log.e(TAG, "Pending pairing state has an invalid size: ${serializedState.size}")
            return false
        }
        val encoded = try {
            encryptState(serializedState, wrappingKey())
        } catch (firstFailure: Exception) {
            Log.w(TAG, "Existing Keystore key failed; rotating the pending-state key and retrying", firstFailure)
            if (!deleteWrappingKey()) {
                Log.e(TAG, "Could not replace the pending-state Keystore key")
                return false
            }
            try {
                encryptState(serializedState, wrappingKey())
            } catch (retryFailure: Exception) {
                Log.e(TAG, "Could not encrypt pending pairing state after key rotation", retryFailure)
                return false
            }
        }
        val saved = preferences.edit().putString(PENDING_VALUE_KEY, encoded).commit()
        if (!saved) Log.e(TAG, "SharedPreferences did not commit pending pairing state")
        return saved
    }

    @Synchronized
    override fun clear(): Boolean {
        val cleared = preferences.edit().remove(PENDING_VALUE_KEY).commit()
        if (!cleared) Log.e(TAG, "SharedPreferences did not clear pending pairing state")
        return cleared
    }

    private fun existingWrappingKey(): javax.crypto.SecretKey? {
        val androidKeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return androidKeyStore.getKey(WRAPPING_KEY_ALIAS, null) as? javax.crypto.SecretKey
    }

    private fun encryptState(serializedState: ByteArray, key: javax.crypto.SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // This key forbids caller-provided nonces. Let Android Keystore generate the GCM IV.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv?.takeIf { it.size == GCM_IV_BYTES }
            ?: throw GeneralSecurityException("Android Keystore did not provide a valid GCM nonce")
        cipher.updateAAD(PENDING_AAD)
        val ciphertext = cipher.doFinal(serializedState)
        val wrapped = iv + ciphertext
        return try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(wrapped)
        } finally {
            wrapped.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun deleteWrappingKey(): Boolean = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(WRAPPING_KEY_ALIAS)
        true
    } catch (exception: Exception) {
        Log.e(TAG, "Could not delete the stale pending-state Keystore key", exception)
        false
    }

    private fun wrappingKey(): javax.crypto.SecretKey {
        existingWrappingKey()?.let { return it }
        var lastFailure: Exception? = null
        for (keySize in intArrayOf(256, 128)) {
            try {
                val builder = KeyGenParameterSpec.Builder(
                    WRAPPING_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(keySize)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setUnlockedDeviceRequired(false)
                }
                return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                    .apply { init(builder.build()) }
                    .generateKey()
            } catch (exception: Exception) {
                lastFailure = exception
                runCatching {
                    KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(WRAPPING_KEY_ALIAS)
                }
            }
        }
        throw lastFailure ?: GeneralSecurityException("Could not create an Android Keystore wrapping key")
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAPPING_KEY_ALIAS = "protect_my_phone_pending_pairings_v1"
        private const val PREFERENCES_NAME = "protect_my_phone_pending_pairings_v1"
        private const val PENDING_VALUE_KEY = "encrypted_pending_sessions"
        private const val TAG = "PmpPairingStorage"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MAX_PENDING_STATE_BYTES = 32 * 1024
        private const val MAX_PENDING_CIPHERTEXT_BYTES = MAX_PENDING_STATE_BYTES + GCM_IV_BYTES + 16
        private const val MAX_ENCRYPTED_BASE64_CHARS = MAX_PENDING_CIPHERTEXT_BYTES * 2
        private val PENDING_AAD = "protect-my-phone-pending-pairing-v1".toByteArray(StandardCharsets.US_ASCII)
    }
}

private fun generateP256KeyPair(secureRandom: SecureRandom): KeyPair =
    KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), secureRandom)
    }.generateKeyPair()

private fun randomBytes(size: Int, secureRandom: SecureRandom): ByteArray =
    ByteArray(size).also(secureRandom::nextBytes)

private fun deriveSharedKey(
    localPrivateKey: KeyPair,
    peerPublicKey: PublicKey,
    pair1Body: String,
    pair2Body: String,
    pair1: Pair1Message,
    pair2: Pair2Message,
): ByteArray {
    val agreement = KeyAgreement.getInstance("ECDH")
    agreement.init(localPrivateKey.private)
    agreement.doPhase(peerPublicKey, true)
    val ecdhSecret = agreement.generateSecret()
    try {
        val saltMaterial = "pmp-pairing-salt-v1|${pair1.sessionId}|${pair1.nonce}|${pair2.nonce}"
            .toByteArray(StandardCharsets.US_ASCII)
        val salt = MessageDigest.getInstance("SHA-256").digest(saltMaterial)
        val transcript = "pmp-pairing-transcript-v1|$pair1Body|$pair2Body"
            .toByteArray(StandardCharsets.US_ASCII)
        val roleContext = "|initiator=${pair1.deviceId}|responder=${pair2.deviceId}"
            .toByteArray(StandardCharsets.US_ASCII)
        return hkdfSha256(ecdhSecret, salt, transcript + roleContext, 32)
    } finally {
        ecdhSecret.fill(0)
    }
}

/** RFC 5869 HKDF-SHA256. */
private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
    require(outputLength in 1..(255 * 32))
    val extract = Mac.getInstance("HmacSHA256")
    extract.init(SecretKeySpec(salt, "HmacSHA256"))
    val prk = extract.doFinal(ikm)
    try {
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        val output = ByteArrayOutputStream(outputLength)
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < outputLength) {
            expand.reset()
            expand.update(previous)
            expand.update(info)
            expand.update(counter.toByte())
            previous = expand.doFinal()
            output.write(previous)
            counter++
        }
        return output.toByteArray().copyOf(outputLength)
    } finally {
        prk.fill(0)
    }
}

private const val MAX_PENDING_SESSIONS = 8
private const val SESSION_LIFETIME_MILLIS = 10 * 60 * 1000L
private const val SHORT_FINGERPRINT_HEX_CHARS = 12
