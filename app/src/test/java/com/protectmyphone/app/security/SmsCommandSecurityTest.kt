package com.protectmyphone.app.security

import android.content.Context
import android.util.Base64
import com.protectmyphone.app.platform.SmsAuthenticationFailure
import com.protectmyphone.app.platform.SmsCommand
import com.protectmyphone.app.platform.SmsCommandAuthenticationResult
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SmsCommandSecurityTest {
    private val now = 1_800_000_000_000L
    private val pairingId = "pairing_01"
    private val senderId = "controller_01"
    private val targetId = "protected_01"
    private val senderPhoneNumber = "+15550101001"
    private val targetPhoneNumber = "+15550101002"
    private val sharedKey = ByteArray(32) { index -> (index + 1).toByte() }

    @Test
    fun approvedCommandAuthenticatesAndItsNonceCanOnlyBeConsumedOnce() {
        val harness = harness()
        val body = harness.security.seal(pairingId, targetId, senderId, "LOCATE", now)

        assertNotNull(body)
        assertAuthorized(
            senderId,
            senderPhoneNumber,
            pairingId,
            SmsCommand.Locate,
            body!!,
            harness.security.authenticate(body, senderPhoneNumber, targetId, now),
        )
        assertRejected(
            SmsAuthenticationFailure.REPLAYED_OR_REPLAY_STORE_UNAVAILABLE,
            harness.security.authenticate(body, senderPhoneNumber, targetId, now),
        )
    }

    @Test
    fun envelopeCodecRoundTripsAndRejectsNonCanonicalOrMalformedBodies() {
        val harness = harness()
        val body = harness.security.seal(pairingId, targetId, senderId, "STATUS", now)!!
        val parsed = SmsCommandEnvelopeCodec.parseBody(body)

        assertNotNull(parsed)
        assertEquals(body, SmsCommandEnvelopeCodec.encode(parsed!!))
        assertNull(SmsCommandEnvelopeCodec.parseBody("PMP1|pairing_01"))
        assertNull(SmsCommandEnvelopeCodec.parseBody("$body\n"))
        assertNull(SmsCommandEnvelopeCodec.parseBody(body.replaceFirst("PMP1|", "PMP2|")))
        assertNull(SmsCommandEnvelopeCodec.parseBody(body.replaceFirst("|controller_01|", "|bad sender|")))
    }

    @Test
    fun authenticationRejectsWrongTargetBeforeCommandCanBeUsed() {
        val harness = harness()
        val body = harness.security.seal(pairingId, targetId, senderId, "LOCK", now)!!

        assertRejected(
            SmsAuthenticationFailure.WRONG_TARGET,
            harness.security.authenticate(body, senderPhoneNumber, "some_other_phone", now),
        )
        assertAuthorized(
            senderId,
            senderPhoneNumber,
            pairingId,
            SmsCommand.Lock,
            body,
            harness.security.authenticate(body, senderPhoneNumber, targetId, now),
        )
    }

    @Test
    fun authenticationRejectsUnpairedSendersAndInvalidAuthenticator() {
        val harness = harness()
        val body = harness.security.seal(pairingId, targetId, senderId, "ALARM_START", now)!!
        val envelope = SmsCommandEnvelopeCodec.parseBody(body)!!
        val changedIv = ByteArray(12) { 0x5A.toByte() }
        val tamperedBody = SmsCommandEnvelopeCodec.encode(
            envelope.copy(iv = encodeBase64(changedIv)),
        )!!

        harness.pairings.removePairing(pairingId, senderId)
        assertRejected(
            SmsAuthenticationFailure.UNPAIRED_SENDER,
            harness.security.authenticate(body, senderPhoneNumber, targetId, now),
        )

        harness.pairings.put(pairingId, senderId, senderPhoneNumber, sharedKey)
        assertRejected(
            SmsAuthenticationFailure.INVALID_AUTHENTICATOR,
            harness.security.authenticate(tamperedBody, senderPhoneNumber, targetId, now),
        )
    }

    @Test
    fun authenticationRejectsExpiredAndTooFarInFutureMessages() {
        val harness = harness()
        val expired = harness.security.seal(pairingId, targetId, senderId, "STATUS", now - 6 * 60_000L)!!
        val future = harness.security.seal(pairingId, targetId, senderId, "STATUS", now + 2 * 60_000L)!!

        assertRejected(
            SmsAuthenticationFailure.EXPIRED_OR_FUTURE,
            harness.security.authenticate(expired, senderPhoneNumber, targetId, now),
        )
        assertRejected(
            SmsAuthenticationFailure.EXPIRED_OR_FUTURE,
            harness.security.authenticate(future, senderPhoneNumber, targetId, now),
        )
    }

    @Test
    fun authenticationFailsClosedWhenReplayStorageCannotConsumeNonce() {
        val harness = harness()
        val body = harness.security.seal(pairingId, targetId, senderId, "LOCATE", now)!!
        harness.replayGuard.failClosed = true

        assertRejected(
            SmsAuthenticationFailure.REPLAYED_OR_REPLAY_STORE_UNAVAILABLE,
            harness.security.authenticate(body, senderPhoneNumber, targetId, now),
        )
    }

    @Test
    fun androidReplayGuardPersistsConsumedNoncesBetweenInstances() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pmp_sms_replay_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val first = AndroidSmsReplayGuard(context)
        val second = AndroidSmsReplayGuard(context)

        assertTrue(first.consumeIfNew(pairingId, senderId, "nonce_a", now + 60_000L, now))
        assertFalse(second.consumeIfNew(pairingId, senderId, "nonce_a", now + 60_000L, now))
        assertFalse(second.consumeIfNew(pairingId, senderId, "nonce_b", now, now))
    }

    @Test
    fun sealingRefusesUnknownCommandsAndUnapprovedPairings() {
        val harness = harness()

        assertNull(harness.security.seal(pairingId, targetId, senderId, "ERASE", now))
        harness.pairings.removePairing(pairingId, targetId)
        assertNull(harness.security.seal(pairingId, targetId, senderId, "LOCK", now))
        assertNull(harness.security.seal("bad id", targetId, senderId, "LOCK", now))
    }

    @Test
    fun pairingApprovalRequiresExplicitMatchingFingerprintConfirmation() {
        val fingerprint = PairingApproval.fingerprint(sharedKey)

        assertNotNull(PairingApproval.compareFingerprints(sharedKey, fingerprint, fingerprint, true))
        assertNull(PairingApproval.compareFingerprints(sharedKey, fingerprint, fingerprint, false))
        assertNull(PairingApproval.compareFingerprints(sharedKey, fingerprint, "0".repeat(64), true))
        assertNull(PairingApproval.compareFingerprints(ByteArray(16), fingerprint, fingerprint, true))
    }

    @Test
    fun approvedKeyStoreRejectsKeyWithoutExplicitFingerprintApproval() {
        val store = MemoryApprovedPairings()
        val key = SecretKeySpec(sharedKey, "AES")
        assertNull(store.getApprovedKey(pairingId, senderId))

        val fingerprint = PairingApproval.fingerprint(sharedKey)
        val approval = PairingApproval.compareFingerprints(sharedKey, fingerprint, fingerprint, true)!!
        assertTrue(store.approvePairing(pairingId, senderId, senderPhoneNumber, sharedKey, approval))
        assertNotNull(store.getApprovedKey(pairingId, senderId))
        assertFalse(store.approvePairing(pairingId, "bad id", senderPhoneNumber, sharedKey, approval))
        assertFalse(store.approvePairing(pairingId, senderId, senderPhoneNumber, ByteArray(16), approval))
        assertEquals(key.algorithm, store.getApprovedKey(pairingId, senderId)!!.algorithm)
    }

    @Test
    fun authenticatedUnknownPlaintextIsRejectedAfterDecryption() {
        val harness = harness()
        val unknownBody = sealRaw("ERASE", now)

        assertRejected(
            SmsAuthenticationFailure.INVALID_COMMAND,
            harness.security.authenticate(unknownBody, senderPhoneNumber, targetId, now),
        )
    }

    @Test
    fun authenticatedSmsFromDifferentPhoneNumberIsRejected() {
        val harness = harness()
        val body = harness.security.seal(pairingId, targetId, senderId, "STATUS", now)!!

        assertRejected(
            SmsAuthenticationFailure.UNPAIRED_SENDER,
            harness.security.authenticate(body, "+15550999999", targetId, now),
        )
    }

    private fun harness(): Harness {
        val pairings = MemoryApprovedPairings().apply {
            put(pairingId, senderId, senderPhoneNumber, sharedKey)
            put(pairingId, targetId, targetPhoneNumber, sharedKey)
        }
        val replayGuard = MemoryReplayGuard()
        return Harness(
            security = SmsCommandSecurity(pairings, replayGuard, SequenceSecureRandom()),
            pairings = pairings,
            replayGuard = replayGuard,
        )
    }

    /** Creates authenticated but unsupported plaintext to ensure parsing follows decryption. */
    private fun sealRaw(payload: String, issuedAt: Long): String {
        val nonce = ByteArray(12) { 0x21.toByte() }
        val iv = ByteArray(12) { 0x42.toByte() }
        val envelope = SmsCommandEnvelope(
            protocolVersion = 1,
            pairingId = pairingId,
            senderDeviceId = senderId,
            targetDeviceId = targetId,
            issuedAtEpochMillis = issuedAt,
            nonce = encodeBase64(nonce),
            iv = encodeBase64(iv),
            ciphertext = "",
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(SmsCommandEnvelopeCodec.associatedData(envelope))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return SmsCommandEnvelopeCodec.encode(envelope.copy(ciphertext = encodeBase64(ciphertext)))!!
    }

    private fun encodeBase64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun assertRejected(
        expectedFailure: SmsAuthenticationFailure,
        actual: SmsCommandAuthenticationResult,
    ) {
        assertEquals(SmsCommandAuthenticationResult.Rejected(expectedFailure), actual)
    }

    private fun assertAuthorized(
        expectedSenderId: String,
        expectedPhoneNumber: String,
        expectedPairingId: String,
        expectedCommand: SmsCommand,
        envelopeBody: String,
        actual: SmsCommandAuthenticationResult,
    ) {
        val authorized = actual as? SmsCommandAuthenticationResult.Authorized
        assertNotNull("Expected authorized result, got $actual", authorized)
        assertEquals(expectedSenderId, authorized!!.senderDeviceId)
        assertEquals(expectedPhoneNumber, authorized.senderPhoneNumber)
        assertEquals(expectedPairingId, authorized.pairingId)
        assertEquals(SmsCommandEnvelopeCodec.parseBody(envelopeBody)!!.nonce, authorized.requestNonce)
        assertEquals(expectedCommand, authorized.command)
        assertEquals(now, authorized.issuedAtEpochMillis)
    }

    private data class Harness(
        val security: SmsCommandSecurity,
        val pairings: MemoryApprovedPairings,
        val replayGuard: MemoryReplayGuard,
    )

    private class MemoryApprovedPairings : ApprovedPairingKeyStore {
        private val values = ConcurrentHashMap<String, ApprovedPairing>()

        fun put(pairing: String, peer: String, phoneNumber: String, bytes: ByteArray) {
            values[key(pairing, peer)] = ApprovedPairing(SecretKeySpec(bytes.copyOf(), "AES"), phoneNumber)
        }

        override fun getApprovedPairing(pairingId: String, peerDeviceId: String): ApprovedPairing? =
            values[key(pairingId, peerDeviceId)]

        override fun approvePairing(
            pairingId: String,
            peerDeviceId: String,
            peerPhoneNumber: String,
            sharedKey: ByteArray,
            approval: PairingApproval,
        ): Boolean {
            if (!pairingId.matches(Regex("[A-Za-z0-9_-]{1,64}")) ||
                !peerDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) ||
                !peerPhoneNumber.matches(Regex("\\+[0-9]{8,15}")) ||
                sharedKey.size != 32 ||
                approval.fingerprintHex != PairingApproval.fingerprint(sharedKey)
            ) return false
            put(pairingId, peerDeviceId, peerPhoneNumber, sharedKey)
            return true
        }

        override fun removePairing(pairingId: String, peerDeviceId: String): Boolean =
            values.remove(key(pairingId, peerDeviceId)) != null

        private fun key(pairing: String, peer: String) = "$pairing\u0000$peer"
    }

    private class MemoryReplayGuard : SmsReplayGuard {
        private val consumed = ConcurrentHashMap.newKeySet<String>()
        var failClosed: Boolean = false

        override fun consumeIfNew(
            pairingId: String,
            senderDeviceId: String,
            nonce: String,
            expiresAtEpochMillis: Long,
            nowEpochMillis: Long,
        ): Boolean {
            if (failClosed || expiresAtEpochMillis <= nowEpochMillis) return false
            return consumed.add("$pairingId\u0000$senderDeviceId\u0000$nonce")
        }
    }

    private class SequenceSecureRandom : SecureRandom() {
        private var next = 0

        override fun nextBytes(bytes: ByteArray) {
            next += 1
            bytes.indices.forEach { index -> bytes[index] = (next * 31 + index).toByte() }
        }
    }
}
