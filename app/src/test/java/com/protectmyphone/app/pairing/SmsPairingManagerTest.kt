package com.protectmyphone.app.pairing

import com.protectmyphone.app.security.ApprovedPairing
import com.protectmyphone.app.security.ApprovedPairingKeyStore
import com.protectmyphone.app.security.PairingApproval
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsPairingManagerTest {
    @Test
    fun pairingNeedsOwnerAcceptanceAndMatchingExplicitFingerprintsOnBothPhones() {
        val ownerAStore = TestApprovedStore()
        val ownerBStore = TestApprovedStore()
        val ownerA = manager("device_a", "Owner A", ownerAStore, InMemoryPendingPairingStateStore())
        val ownerB = manager("device_b", "Owner B", ownerBStore, InMemoryPendingPairingStateStore())

        val pair1 = ownerA.preparePair1(PHONE_B) as PairingPreparationResult.ReadyToSend
        assertTrue(pair1.message.body.startsWith("PAIR1|1|"))
        assertNull(ownerAStore.getApprovedPairing(pair1.message.sessionId, "device_b"))

        val inbound = ownerB.receivePair1(PHONE_A, pair1.message.body) as Pair1ReceiveResult.Pending
        assertEquals(pair1.message.sessionId, inbound.request.sessionId)
        assertEquals(1, ownerB.pendingRequests().size)
        assertNull(ownerBStore.getApprovedPairing(pair1.message.sessionId, "device_a"))

        val accepted = ownerB.acceptPair1(pair1.message.sessionId) as PairingAcceptResult.Accepted
        assertTrue(accepted.response.body.startsWith("PAIR2|1|"))
        assertNull(ownerBStore.getApprovedPairing(pair1.message.sessionId, "device_a"))

        val challengeA = ownerA.receivePair2(PHONE_B, accepted.response.body)
            as Pair2ReceiveResult.AwaitingConfirmation
        assertEquals(challengeA.fingerprint.fullFingerprint, accepted.fingerprint.fullFingerprint)
        assertEquals(challengeA.fingerprint.shortFingerprint, accepted.fingerprint.shortFingerprint)
        assertNull(ownerAStore.getApprovedPairing(pair1.message.sessionId, "device_b"))
        assertNull(ownerBStore.getApprovedPairing(pair1.message.sessionId, "device_a"))

        val wrongComparison = ownerA.confirmFingerprint(
            pair1.message.sessionId,
            challengeA.fingerprint.fullFingerprint,
            "00".repeat(32),
            explicitlyConfirmedByUser = true,
        ) as PairingConfirmationResult.Rejected
        assertEquals(PairingFailure.FINGERPRINT_MISMATCH, wrongComparison.reason)
        val noConsent = ownerA.confirmFingerprint(
            pair1.message.sessionId,
            challengeA.fingerprint.fullFingerprint,
            accepted.fingerprint.fullFingerprint,
            explicitlyConfirmedByUser = false,
        ) as PairingConfirmationResult.Rejected
        assertEquals(PairingFailure.USER_CONFIRMATION_REQUIRED, noConsent.reason)
        assertNull(ownerAStore.getApprovedPairing(pair1.message.sessionId, "device_b"))

        assertTrue(ownerA.confirmFingerprint(
            pair1.message.sessionId,
            challengeA.fingerprint.fullFingerprint,
            accepted.fingerprint.fullFingerprint,
            explicitlyConfirmedByUser = true,
        ) is PairingConfirmationResult.Paired)
        assertTrue(ownerB.confirmFingerprint(
            pair1.message.sessionId,
            accepted.fingerprint.fullFingerprint,
            challengeA.fingerprint.fullFingerprint,
            explicitlyConfirmedByUser = true,
        ) is PairingConfirmationResult.Paired)

        val keyA = ownerAStore.getApprovedPairing(pair1.message.sessionId, "device_b")?.key
        val keyB = ownerBStore.getApprovedPairing(pair1.message.sessionId, "device_a")?.key
        assertNotNull(keyA)
        assertNotNull(keyB)
        assertArrayEquals(keyA!!.encoded, keyB!!.encoded)
    }

    @Test
    fun pendingStateSurvivesManagerRecreationWithoutApprovingTheKey() {
        val persistentA = InMemoryPendingPairingStateStore()
        val persistentB = InMemoryPendingPairingStateStore()
        val storeA = TestApprovedStore()
        val storeB = TestApprovedStore()
        val firstA = manager("device_a", "Owner A", storeA, persistentA)
        val firstB = manager("device_b", "Owner B", storeB, persistentB)

        val pair1 = firstA.preparePair1(PHONE_B) as PairingPreparationResult.ReadyToSend
        val pending = firstB.receivePair1(PHONE_A, pair1.message.body) as Pair1ReceiveResult.Pending

        val restoredA = manager("device_a", "Owner A", storeA, persistentA)
        val restoredB = manager("device_b", "Owner B", storeB, persistentB)
        assertTrue(restoredA.isStorageAvailable())
        assertEquals(pending.request.sessionId, restoredB.pendingRequests().single().sessionId)

        val pair2 = restoredB.acceptPair1(pending.request.sessionId) as PairingAcceptResult.Accepted
        val confirmedLaterB = manager("device_b", "Owner B", storeB, persistentB)
        val restoredChallengeB = confirmedLaterB.pendingFingerprintConfirmations().single()
        val restoredChallengeA = restoredA.receivePair2(PHONE_B, pair2.response.body)
            as Pair2ReceiveResult.AwaitingConfirmation

        assertEquals(restoredChallengeA.fingerprint.fullFingerprint, restoredChallengeB.fullFingerprint)
        assertNull(storeA.getApprovedPairing(pair1.message.sessionId, "device_b"))
        assertNull(storeB.getApprovedPairing(pair1.message.sessionId, "device_a"))
    }

    @Test
    fun malformedOrUnsolicitedMessagesNeverCreateApproval() {
        val store = TestApprovedStore()
        val manager = manager("device_b", "Owner B", store, InMemoryPendingPairingStateStore())

        assertTrue(manager.receivePair1(PHONE_A, "PAIR1|2|bad").let {
            it is Pair1ReceiveResult.Rejected && it.reason == PairingFailure.MALFORMED_PAIR1
        })
        assertTrue(manager.receivePair1(PHONE_A, "PAIR1|1|bad\nbody").let {
            it is Pair1ReceiveResult.Rejected && it.reason == PairingFailure.MALFORMED_PAIR1
        })
        assertTrue(manager.receivePair2(PHONE_A, "PAIR2|1|unsolicited").let {
            it is Pair2ReceiveResult.Rejected && it.reason == PairingFailure.MALFORMED_PAIR2
        })
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun pairingFailsClosedWhenPendingStorageCannotCommit() {
        val manager = manager(
            "device_a",
            "Owner A",
            TestApprovedStore(),
            object : PendingPairingStateStore {
                override fun load() = PendingPairingStateLoadResult.Empty
                override fun save(serializedState: ByteArray) = false
                override fun clear() = true
            },
        )

        val result = manager.preparePair1(PHONE_B) as PairingPreparationResult.Rejected
        assertEquals(PairingFailure.PENDING_STORAGE_UNAVAILABLE, result.reason)
        assertFalse(manager.isStorageAvailable())
    }

    private fun manager(
        id: String,
        label: String,
        approvedStore: TestApprovedStore,
        pendingStore: PendingPairingStateStore,
    ) = SmsPairingManager(
        localDeviceId = id,
        localDeviceLabel = label,
        keyStore = approvedStore,
        pendingStateStore = pendingStore,
    )

    private class TestApprovedStore : ApprovedPairingKeyStore {
        val values = ConcurrentHashMap<String, ApprovedPairing>()

        override fun getApprovedPairing(pairingId: String, peerDeviceId: String): ApprovedPairing? =
            values["$pairingId|$peerDeviceId"]

        override fun approvePairing(
            pairingId: String,
            peerDeviceId: String,
            peerPhoneNumber: String,
            sharedKey: ByteArray,
            approval: PairingApproval,
        ): Boolean {
            if (sharedKey.size != 32) return false
            values["$pairingId|$peerDeviceId"] = ApprovedPairing(
                key = SecretKeySpec(sharedKey.copyOf(), "AES"),
                peerPhoneNumber = peerPhoneNumber,
            )
            return true
        }

        override fun removePairing(pairingId: String, peerDeviceId: String): Boolean =
            values.remove("$pairingId|$peerDeviceId") != null
    }

    private companion object {
        const val PHONE_A = "+14155550101"
        const val PHONE_B = "+14155550102"
    }
}
