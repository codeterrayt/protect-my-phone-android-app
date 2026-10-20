package com.protectmyphone.app.pairing

import android.content.Context
import android.os.Build
import com.protectmyphone.app.platform.DeviceIdentity
import com.protectmyphone.app.security.AndroidKeystorePairingKeyStore
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide facade shared by the activity and the SMS broadcast receiver. */
object PairingSmsRuntime {
    private val listeners = CopyOnWriteArrayList<(PairingInboxEvent) -> Unit>()

    @Volatile
    private var cachedManager: SmsPairingManager? = null

    fun manager(context: Context): SmsPairingManager? {
        cachedManager?.takeIf { it.isStorageAvailable() }?.let { return it }
        return synchronized(this) {
            cachedManager?.takeIf { it.isStorageAvailable() } ?: runCatching {
                val appContext = context.applicationContext
                val localId = DeviceIdentity.getOrCreate(appContext)
                SmsPairingManager(
                    localDeviceId = localId,
                    localDeviceLabel = Build.MODEL?.take(48)?.ifBlank { "Android phone" } ?: "Android phone",
                    keyStore = AndroidKeystorePairingKeyStore(appContext),
                    pendingStateStore = AndroidKeystorePendingPairingStateStore(appContext),
                ).also { cachedManager = it }
            }.getOrNull()
        }
    }

    /** Parses only pairing messages. The manager keeps PAIR1 passive until the owner accepts it. */
    fun receive(context: Context, senderPhoneNumber: String, body: String): PairingInboxEvent? {
        val manager = manager(context) ?: return PairingInboxEvent.StorageUnavailable
        val event = when {
            body.startsWith("PAIR1|") -> when (val result = manager.receivePair1(senderPhoneNumber, body)) {
                is Pair1ReceiveResult.Pending -> PairingInboxEvent.Invitation(result.request)
                is Pair1ReceiveResult.Rejected -> PairingInboxEvent.Rejected(result.reason)
            }
            body.startsWith("PAIR2|") -> when (val result = manager.receivePair2(senderPhoneNumber, body)) {
                is Pair2ReceiveResult.AwaitingConfirmation -> PairingInboxEvent.Fingerprint(result.fingerprint)
                is Pair2ReceiveResult.Rejected -> PairingInboxEvent.Rejected(result.reason)
            }
            else -> return null
        }
        listeners.forEach { listener -> runCatching { listener(event) } }
        return event
    }

    fun subscribe(listener: (PairingInboxEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}

sealed interface PairingInboxEvent {
    data class Invitation(val request: IncomingPairingRequest) : PairingInboxEvent
    data class Fingerprint(val confirmation: PairingFingerprintConfirmation) : PairingInboxEvent
    data class Rejected(val reason: PairingFailure) : PairingInboxEvent
    data object StorageUnavailable : PairingInboxEvent
}
