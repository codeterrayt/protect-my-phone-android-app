package com.protectmyphone.app.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.protectmyphone.app.pairing.IncomingPairingRequest
import com.protectmyphone.app.pairing.PairingFingerprintConfirmation
import com.protectmyphone.app.ui.DeviceLocationPin
import com.protectmyphone.app.ui.ProtectMyPhoneApp
import com.protectmyphone.app.ui.ProtectUiActions
import com.protectmyphone.app.ui.ProtectedDeviceUi
import com.protectmyphone.app.ui.UiActionResult

/** Screenshot-friendly app shell with fictional, non-operational sample data. */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val locations = demoDevices.mapNotNull { device ->
            val latitude = device.latitude ?: return@mapNotNull null
            val longitude = device.longitude ?: return@mapNotNull null
            DeviceLocationPin(
                id = device.id,
                name = device.name,
                latitude = latitude,
                longitude = longitude,
                updatedAt = device.locationUpdatedAt,
                isThisPhone = device.isCurrentPhone,
            )
        }

        setContent {
            ProtectMyPhoneApp(
                devices = demoDevices,
                actions = ProtectUiActions(
                    locationPins = locations,
                    pendingPairingRequests = listOf(demoIncomingRequest),
                    pendingFingerprintConfirmations = listOf(demoFingerprintConfirmation),
                    onCommand = { _, _ -> UiActionResult.NOT_READY },
                    onLiveLocationChanged = { _, _, _ -> UiActionResult.NOT_READY },
                    onPairDevice = { _, _ -> UiActionResult.NOT_READY },
                    onAcceptPairing = { UiActionResult.NOT_READY },
                    onConfirmPairing = { _, _, _ -> UiActionResult.NOT_READY },
                    onSetLockScreenMessage = { _, _ -> UiActionResult.NOT_READY },
                    onRemovePairing = { UiActionResult.NOT_READY },
                    onExitApp = ::finish,
                ),
            )
        }
    }
}

private val demoDevices = listOf(
    ProtectedDeviceUi(
        id = "demo-phone-a",
        name = "Demo Phone A",
        phoneNumber = "+1 202-555-0142",
        batteryPercent = 82,
        networkName = "Example Mobile · LTE",
        wifiName = "Demo Wi-Fi",
        lastLocation = "Central Park, New York",
        lastSeen = "Just now",
        isOnline = true,
        liveLocationEnabled = true,
        updateIntervalMinutes = 15,
        latitude = 40.7829,
        longitude = -73.9654,
        locationUpdatedAt = "Updated just now · sample data",
        soundStatus = "Demo alarm is playing",
        lockMessageStatus = "Sample lock message ready",
        isDemo = true,
    ),
    ProtectedDeviceUi(
        id = "demo-phone-b",
        name = "Demo Phone B",
        phoneNumber = "+1 202-555-0188",
        batteryPercent = 47,
        networkName = "Example Mobile · 5G",
        wifiName = null,
        lastLocation = "Golden Gate Park, San Francisco",
        lastSeen = "18 min ago",
        isOnline = false,
        liveLocationEnabled = false,
        latitude = 37.7694,
        longitude = -122.4862,
        locationUpdatedAt = "18 min ago · sample data",
        soundStatus = "No demo alarm active",
        lockMessageStatus = "No sample message sent",
        isDemo = true,
    ),
)

private val demoIncomingRequest = IncomingPairingRequest(
    sessionId = "demo-incoming-session",
    peerDeviceId = "demo-phone-b",
    peerLabel = "Demo Phone B",
    peerPhoneNumber = "+1 202-555-0188",
    receivedAtEpochMillis = 0L,
)

private const val DEMO_FINGERPRINT =
    "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"

private val demoFingerprintConfirmation = PairingFingerprintConfirmation(
    sessionId = "demo-fingerprint-session",
    pairingId = "demo-pairing",
    peerDeviceId = "demo-phone-a",
    peerLabel = "Demo Phone A",
    peerPhoneNumber = "+1 202-555-0142",
    fullFingerprint = DEMO_FINGERPRINT,
    shortFingerprint = DEMO_FINGERPRINT.take(12),
)
