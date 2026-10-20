package com.protectmyphone.app

import android.os.Bundle
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.protectmyphone.app.pairing.Pair1ReceiveResult
import com.protectmyphone.app.pairing.PairingAcceptResult
import com.protectmyphone.app.pairing.PairingConfirmationResult
import com.protectmyphone.app.pairing.PairingPreparationResult
import com.protectmyphone.app.pairing.PairingSmsRuntime
import com.protectmyphone.app.pairing.PairedPhone
import com.protectmyphone.app.pairing.PairedPhoneCatalog
import com.protectmyphone.app.platform.AndroidSmsCommandSender
import com.protectmyphone.app.platform.DeviceField
import com.protectmyphone.app.platform.DeviceLocation
import com.protectmyphone.app.platform.DeviceOperationResult
import com.protectmyphone.app.platform.PhoneAdminReceiver
import com.protectmyphone.app.platform.ProtectionState
import com.protectmyphone.app.platform.PairedDeviceStateStore
import com.protectmyphone.app.platform.DeviceActionStatus
import com.protectmyphone.app.platform.RealtimeLocationStatus
import com.protectmyphone.app.platform.AndroidDeviceController
import com.protectmyphone.app.platform.DeviceIdentity
import com.protectmyphone.app.platform.SmsCommand
import com.protectmyphone.app.platform.SmsReply
import com.protectmyphone.app.platform.SmsReplyEvents
import com.protectmyphone.app.platform.SmsReplyListener
import com.protectmyphone.app.platform.SmsReplyUpdate
import com.protectmyphone.app.platform.AndroidSmsTextSender
import com.protectmyphone.app.platform.SmsTransportResult
import com.protectmyphone.app.security.AndroidKeystorePairingKeyStore
import com.protectmyphone.app.security.AndroidSmsReplayGuard
import com.protectmyphone.app.security.SmsCommandSecurity
import com.protectmyphone.app.ui.DeviceCommand
import com.protectmyphone.app.ui.DeviceLocationPin
import com.protectmyphone.app.ui.ProtectedDeviceUi
import com.protectmyphone.app.ui.ProtectMyPhoneApp
import com.protectmyphone.app.ui.ProtectUiActions
import com.protectmyphone.app.ui.UiActionResult
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val protectedDevices = mutableStateListOf<ProtectedDeviceUi>()
    private val locationPins = mutableStateListOf<DeviceLocationPin>()
    private val remoteLockEnabled = mutableStateOf(false)
    private val pendingPairingRequests = mutableStateListOf<com.protectmyphone.app.pairing.IncomingPairingRequest>()
    private val pendingFingerprints = mutableStateListOf<com.protectmyphone.app.pairing.PairingFingerprintConfirmation>()
    private lateinit var localDeviceId: String
    private lateinit var phoneCatalog: PairedPhoneCatalog
    private lateinit var deviceStateStore: PairedDeviceStateStore
    private var pairingEventsSubscription: AutoCloseable? = null
    private var replyEventsSubscription: AutoCloseable? = null

    private val smsPermissionsRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hasSmsPermissions()) enableSmsMonitoring()
        else {
            ProtectionState.setSmsMonitoringEnabled(this, false)
            Toast.makeText(this, "SMS access is needed before this phone can receive and answer commands.", Toast.LENGTH_LONG).show()
        }
    }

    private val locationPermissionsRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hasForegroundLocationPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openBackgroundLocationSettings()
            else Toast.makeText(this, "Location features are enabled.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Allow location when you want this phone to answer location requests.", Toast.LENGTH_LONG).show()
        }
    }

    private val localLocationPermissionsRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hasForegroundLocationPermission()) locateThisPhone()
        else Toast.makeText(this, "Allow location to add this phone to the map.", Toast.LENGTH_LONG).show()
    }

    private val deviceAdminRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        val active = isRemoteLockEnabled()
        remoteLockEnabled.value = active
        Toast.makeText(
            this,
            if (active) "Remote screen lock is enabled."
            else "Device Admin was skipped. Remote lock will stay unavailable until you enable it.",
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        localDeviceId = DeviceIdentity.getOrCreate(this)
        phoneCatalog = PairedPhoneCatalog(this)
        deviceStateStore = PairedDeviceStateStore(this)
        remoteLockEnabled.value = isRemoteLockEnabled()
        pairingEventsSubscription = PairingSmsRuntime.subscribe {
            runOnUiThread { refreshPairingState() }
        }
        replyEventsSubscription = SmsReplyEvents.subscribe(SmsReplyListener { update ->
            runOnUiThread { applyReply(update) }
        })
        refreshPairingState()
        refreshProtectedDevices()
        refreshLocationPins()
        setContent {
            ProtectMyPhoneApp(
                devices = protectedDevices,
                actions = ProtectUiActions(
                    onShareLocationLink = { _, mapUrl -> shareLocation(mapUrl) },
                    onOpenLocationInMaps = ::openLocationInMaps,
                    onRefreshThisPhoneLocation = ::requestThisPhoneLocation,
                    locationPins = locationPins.toList(),
                    onRequestSetup = ::requestSetup,
                    onEnableLocationAccess = ::requestLocationAccess,
                    onEnableRemoteLock = ::requestDeviceAdminActivation,
                    remoteLockEnabled = remoteLockEnabled.value,
                    onExitApp = ::finish,
                    onCommand = ::sendDeviceCommand,
                    onLiveLocationChanged = { deviceId, enabled, interval ->
                        sendTypedCommand(deviceId, SmsCommand.SetRealtimeLocation(enabled, interval))
                    },
                    onSetLockScreenMessage = { deviceId, message ->
                        sendTypedCommand(deviceId, SmsCommand.ShowLockScreenMessage(message))
                    },
                    onRemovePairing = ::removePairedPhone,
                    onPairDevice = ::beginPairing,
                    pendingPairingRequests = pendingPairingRequests,
                    pendingFingerprintConfirmations = pendingFingerprints,
                    onAcceptPairing = ::acceptPairing,
                    onConfirmPairing = ::confirmPairing,
                    onCancelPairing = ::cancelPairing,
                ),
            )
        }
        requestSmsPermissionsOnFirstOpen()
    }

    override fun onResume() {
        super.onResume()
        remoteLockEnabled.value = isRemoteLockEnabled()
        if (::phoneCatalog.isInitialized) {
            refreshPairingState()
            refreshProtectedDevices()
        }
    }

    override fun onDestroy() {
        pairingEventsSubscription?.close()
        replyEventsSubscription?.close()
        super.onDestroy()
    }

    private fun requestSetup() {
        val missing = missingSmsPermissions()
        if (missing.isNotEmpty()) {
            if (missing.any { !shouldShowRequestPermissionRationale(it) } && hasRequestedCorePermissions()) {
                Toast.makeText(this, "Enable SMS access from App info → Permissions.", Toast.LENGTH_LONG).show()
                openAppPermissionSettings()
            } else smsPermissionsRequest.launch(missing)
        } else {
            enableSmsMonitoring()
        }
    }

    private fun requestSmsPermissionsOnFirstOpen() {
        if (hasSmsPermissions()) {
            enableSmsMonitoring(showConfirmation = false)
            return
        }
        val preferences = getSharedPreferences(PREFS_PERMISSION_FLOW, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_CORE_SMS_PROMPTED, false)) return
        preferences.edit().putBoolean(KEY_CORE_SMS_PROMPTED, true).apply()
        window.decorView.post {
            if (!isFinishing) smsPermissionsRequest.launch(missingSmsPermissions())
        }
    }

    private fun requestLocationAccess() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
        if (permissions.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openBackgroundLocationSettings()
            return
        }
        locationPermissionsRequest.launch(permissions)
    }

    private fun hasSmsPermissions(): Boolean = missingSmsPermissions().isEmpty()

    private fun missingSmsPermissions(): Array<String> = listOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
    ).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun hasForegroundLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasRequestedCorePermissions(): Boolean =
        getSharedPreferences(PREFS_PERMISSION_FLOW, MODE_PRIVATE).getBoolean(KEY_CORE_SMS_PROMPTED, false)

    private fun enableSmsMonitoring(showConfirmation: Boolean = true) {
        when (val state = ProtectionState.setSmsMonitoringEnabled(this, true)) {
            is com.protectmyphone.app.platform.DeviceOperationResult.Success -> if (showConfirmation) {
                Toast.makeText(this, "SMS monitoring is on. Only approved encrypted commands are accepted.", Toast.LENGTH_LONG).show()
            }
            is com.protectmyphone.app.platform.DeviceOperationResult.Unsupported -> Toast.makeText(this, state.reason, Toast.LENGTH_LONG).show()
            is com.protectmyphone.app.platform.DeviceOperationResult.PermissionRequired -> Toast.makeText(this, state.reason, Toast.LENGTH_LONG).show()
            is com.protectmyphone.app.platform.DeviceOperationResult.Failure -> Toast.makeText(this, state.reason, Toast.LENGTH_LONG).show()
            is com.protectmyphone.app.platform.DeviceOperationResult.Pending -> Unit
        }
    }

    private fun openAppPermissionSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun openBackgroundLocationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        Toast.makeText(
            this,
            "In App info, open Permissions → Location → Allow all the time. Android requires this separate choice for SMS location and movement updates.",
            Toast.LENGTH_LONG,
        ).show()
        openAppPermissionSettings()
    }

    private fun requestDeviceAdminActivation() {
        val component = ComponentName(this, PhoneAdminReceiver::class.java)
        val policy = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (policy?.isAdminActive(component) == true) {
            remoteLockEnabled.value = true
            Toast.makeText(this, "Remote screen lock is already enabled.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allows Protect My Phone to lock this phone after a verified command from a paired phone.")
        deviceAdminRequest.launch(intent)
    }

    private fun isRemoteLockEnabled(): Boolean {
        val policy = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        return policy?.isAdminActive(ComponentName(this, PhoneAdminReceiver::class.java)) == true
    }

    private companion object {
        const val PREFS_PERMISSION_FLOW = "permission_flow"
        const val KEY_CORE_SMS_PROMPTED = "core_sms_prompted"
        const val LOCAL_PAIRING_ID = "this_phone"
        const val LOCAL_DEVICE_KEY = "this_phone_location"
    }

    private fun shareLocation(mapUrl: String) {
        if (!mapUrl.startsWith("https://maps.google.com/?q=")) return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, mapUrl)
        }
        startActivity(Intent.createChooser(shareIntent, "Share latest location pin"))
    }

    private fun openLocationInMaps(mapUrl: String) {
        if (!mapUrl.startsWith("https://maps.google.com/?q=")) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(mapUrl))
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent.createChooser(intent, "Open location"))
        }
    }

    private fun requestThisPhoneLocation() {
        if (hasForegroundLocationPermission()) {
            locateThisPhone()
            return
        }
        val permissions = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }.toTypedArray()
        localLocationPermissionsRequest.launch(permissions)
    }

    private fun locateThisPhone() {
        val controller = AndroidDeviceController(this, ComponentName(this, PhoneAdminReceiver::class.java))
        controller.locateOnce { result ->
            when (result) {
                is DeviceOperationResult.Success -> {
                    val now = System.currentTimeMillis()
                    if (deviceStateStore.saveLocation(LOCAL_PAIRING_ID, localDeviceId, result.value, now)) {
                        refreshLocationPins()
                        Toast.makeText(this, "This phone’s location was updated.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "This phone’s location could not be saved securely.", Toast.LENGTH_LONG).show()
                    }
                }
                is DeviceOperationResult.PermissionRequired -> Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                is DeviceOperationResult.Unsupported -> Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                is DeviceOperationResult.Failure -> Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                is DeviceOperationResult.Pending -> Unit
            }
        }
    }

    private fun refreshPairingState() {
        val manager = PairingSmsRuntime.manager(this)
        pendingPairingRequests.clear()
        manager?.pendingRequests()?.forEach(pendingPairingRequests::add)
        pendingFingerprints.clear()
        pendingFingerprintConfirmations(manager).forEach(pendingFingerprints::add)
    }

    private fun pendingFingerprintConfirmations(manager: com.protectmyphone.app.pairing.SmsPairingManager?) =
        manager?.pendingFingerprintConfirmations().orEmpty()

    private fun refreshProtectedDevices() {
        val updated = phoneCatalog.all().map { phone ->
            val cachedReply = SmsReplyEvents.latest(phone.pairingId, phone.peerDeviceId)
            if (cachedReply != null) persistReplyState(phone, cachedReply)
            toUi(phone, cachedReply)
        }
        protectedDevices.clear()
        protectedDevices.addAll(updated)
        refreshLocationPins()
    }

    private fun toUi(phone: PairedPhone, update: SmsReplyUpdate?): ProtectedDeviceUi {
        var battery: Int? = null
        var network = "Not checked"
        var wifi: String? = null
        var lastSeen = "Not checked"
        var online = false
        if (update != null) {
            online = true
            lastSeen = relativeTime(update.receivedAtEpochMillis)
            when (val reply = update.reply) {
                is SmsReply.Status -> {
                    val snapshot = reply.snapshot
                    battery = (snapshot.batteryPercent as? DeviceField.Available)?.value
                    network = when ((snapshot.cellularConnected as? DeviceField.Available)?.value) {
                        true -> "Cellular connected"
                        false -> "No cellular service"
                        null -> "Unavailable"
                    }
                    val wifiConnected = (snapshot.wifiConnected as? DeviceField.Available)?.value
                    wifi = when {
                        wifiConnected == false -> null
                        else -> (snapshot.wifiSsid as? DeviceField.Available)?.value?.takeIf { it.isNotBlank() }
                    }
                }
                is SmsReply.Location, is SmsReply.Acknowledgement -> Unit
            }
        }
        val knownLocation = deviceStateStore.location(phone.pairingId, phone.peerDeviceId)
        val latitude = knownLocation?.latitude
        val longitude = knownLocation?.longitude
        val locationText = if (latitude != null && longitude != null) {
            String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
        } else "No location reported yet"
        val realtime = deviceStateStore.realtimeStatus(phone.pairingId, phone.peerDeviceId)
        val sound = deviceStateStore.soundStatus(phone.pairingId, phone.peerDeviceId)
        val lockMessage = deviceStateStore.lockMessageStatus(phone.pairingId, phone.peerDeviceId)
        return ProtectedDeviceUi(
            id = phone.catalogId,
            name = phone.label,
            phoneNumber = phone.phoneNumber,
            batteryPercent = battery,
            networkName = network,
            wifiName = wifi,
            lastLocation = locationText,
            lastSeen = lastSeen,
            isOnline = online,
            liveLocationEnabled = realtime?.enabled ?: false,
            updateIntervalMinutes = realtime?.intervalMinutes ?: 15,
            latitude = latitude,
            longitude = longitude,
            locationUpdatedAt = knownLocation?.let(::locationTimestamp) ?: "No location reported yet",
            soundStatus = sound?.message ?: "No sound command sent",
            lockMessageStatus = lockMessage?.message ?: "No lock-screen message command sent",
        )
    }

    private fun applyReply(update: SmsReplyUpdate) {
        val phone = phoneCatalog.all().firstOrNull {
            it.pairingId == update.pairingId && it.peerDeviceId == update.senderDeviceId
        } ?: return
        persistReplyState(phone, update)
        val index = protectedDevices.indexOfFirst { it.id == phone.catalogId }
        if (index >= 0) protectedDevices[index] = toUi(phone, update)
        refreshLocationPins()
    }

    private fun persistReplyState(phone: PairedPhone, update: SmsReplyUpdate) {
        when (val reply = update.reply) {
            is SmsReply.Location -> deviceStateStore.saveLocation(
                phone.pairingId,
                phone.peerDeviceId,
                reply.location,
                update.receivedAtEpochMillis,
            )
            is SmsReply.Status -> reply.location?.let { location ->
                val previous = deviceStateStore.location(phone.pairingId, phone.peerDeviceId)
                if (previous == null || location.capturedAtEpochMillis >= previous.capturedAtEpochMillis) {
                    deviceStateStore.saveLocation(
                        phone.pairingId,
                        phone.peerDeviceId,
                        location,
                        update.receivedAtEpochMillis,
                    )
                }
            }
            is SmsReply.Acknowledgement -> when (reply.commandName) {
                "REALTIME" -> {
                    val previous = deviceStateStore.realtimeStatus(phone.pairingId, phone.peerDeviceId)
                        ?: RealtimeLocationStatus(false, 15, "", update.receivedAtEpochMillis)
                    val message = if (reply.status == com.protectmyphone.app.platform.SmsReplyStatus.SUCCEEDED ||
                        reply.status == com.protectmyphone.app.platform.SmsReplyStatus.PENDING
                    ) reply.detail.ifBlank { "Live location request accepted." }
                    else "${reply.status.name.replace('_', ' ')} · ${reply.detail}"
                    deviceStateStore.saveRealtimeStatus(
                        phone.pairingId,
                        phone.peerDeviceId,
                        previous.copy(message = message, updatedAtEpochMillis = update.receivedAtEpochMillis),
                    )
                }
                "ALARM_START" -> {
                    val message = when (reply.status) {
                        com.protectmyphone.app.platform.SmsReplyStatus.SUCCEEDED -> "Sound is playing${reply.detail.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""}"
                        com.protectmyphone.app.platform.SmsReplyStatus.PENDING -> "Sound starting${reply.detail.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""}"
                        else -> "Sound failed · ${reply.detail.ifBlank { reply.status.name }}"
                    }
                    deviceStateStore.saveSoundStatus(phone.pairingId, phone.peerDeviceId, DeviceActionStatus(message, update.receivedAtEpochMillis))
                }
                "ALARM_STOP" -> {
                    val message = if (reply.status == com.protectmyphone.app.platform.SmsReplyStatus.SUCCEEDED) {
                        "Sound stopped${reply.detail.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""}"
                    } else "Stop sound failed · ${reply.detail.ifBlank { reply.status.name }}"
                    deviceStateStore.saveSoundStatus(phone.pairingId, phone.peerDeviceId, DeviceActionStatus(message, update.receivedAtEpochMillis))
                }
                "SHOW_MESSAGE" -> {
                    val message = if (reply.status == com.protectmyphone.app.platform.SmsReplyStatus.SUCCEEDED) {
                        reply.detail.ifBlank { "Lock-screen message was set." }
                    } else "${reply.status.name.replace('_', ' ')} · ${reply.detail}"
                    deviceStateStore.saveLockMessageStatus(phone.pairingId, phone.peerDeviceId, DeviceActionStatus(message, update.receivedAtEpochMillis))
                }
            }
        }
    }

    private fun refreshLocationPins() {
        val pins = mutableListOf<DeviceLocationPin>()
        deviceStateStore.location(LOCAL_PAIRING_ID, localDeviceId)?.let { location ->
            pins += DeviceLocationPin(
                id = LOCAL_DEVICE_KEY,
                name = "This phone",
                latitude = location.latitude,
                longitude = location.longitude,
                updatedAt = locationTimestamp(location),
                isThisPhone = true,
            )
        }
        phoneCatalog.all().forEach { phone ->
            deviceStateStore.location(phone.pairingId, phone.peerDeviceId)?.let { location ->
                pins += DeviceLocationPin(
                    id = phone.catalogId,
                    name = phone.label,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    updatedAt = locationTimestamp(location),
                    isThisPhone = false,
                )
            }
        }
        locationPins.clear()
        locationPins.addAll(pins)
    }

    private fun locationTimestamp(location: com.protectmyphone.app.platform.KnownDeviceLocation): String {
        val time = location.capturedAtEpochMillis.takeIf { it > 0L } ?: location.receivedAtEpochMillis
        return "${DateFormat.format("MMM d, h:mm a", time)} · ${relativeTime(time)}"
    }

    private fun sendDeviceCommand(deviceId: String, command: DeviceCommand): UiActionResult {
        val typed = when (command) {
            DeviceCommand.STATUS -> SmsCommand.GetStatus
            DeviceCommand.LOCATE -> SmsCommand.Locate
            DeviceCommand.LOCK -> SmsCommand.Lock
            DeviceCommand.SOUND -> SmsCommand.StartAlarm
            DeviceCommand.STOP_SOUND -> SmsCommand.StopAlarm
        }
        return sendTypedCommand(deviceId, typed)
    }

    private fun sendTypedCommand(deviceId: String, command: SmsCommand): UiActionResult {
        val phone = phoneCatalog.all().firstOrNull { it.catalogId == deviceId } ?: return UiActionResult.NOT_READY
        val priorRealtimeStatus = if (command is SmsCommand.SetRealtimeLocation) {
            deviceStateStore.realtimeStatus(phone.pairingId, phone.peerDeviceId)
        } else null
        savePendingCommandStatus(phone, command)
        val security = SmsCommandSecurity(AndroidKeystorePairingKeyStore(this), AndroidSmsReplayGuard(this))
        val result = AndroidSmsCommandSender(this, security).send(
            pairingId = phone.pairingId,
            peerDeviceId = phone.peerDeviceId,
            localDeviceId = localDeviceId,
            command = command,
        )
        return when (result) {
            is SmsTransportResult.Queued -> {
                refreshProtectedDevices()
                UiActionResult.QUEUED
            }
            else -> {
                val detail = when (result) {
                    is SmsTransportResult.PermissionRequired -> "Allow SMS sending in setup first."
                    is SmsTransportResult.Unsupported -> result.reason
                    is SmsTransportResult.Failed -> result.reason
                    is SmsTransportResult.Queued -> ""
                }
                saveFailedCommandStatus(phone, command, detail, priorRealtimeStatus)
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
                UiActionResult.FAILED
            }
        }
    }

    private fun savePendingCommandStatus(phone: PairedPhone, command: SmsCommand) {
        val now = System.currentTimeMillis()
        when (command) {
            is SmsCommand.SetRealtimeLocation -> deviceStateStore.saveRealtimeStatus(
                phone.pairingId,
                phone.peerDeviceId,
                RealtimeLocationStatus(
                    enabled = command.enabled,
                    intervalMinutes = command.intervalMinutes,
                    message = if (command.enabled) "Waiting for the phone to confirm location updates." else "Waiting for the phone to confirm updates are off.",
                    updatedAtEpochMillis = now,
                ),
            )
            SmsCommand.StartAlarm -> deviceStateStore.saveSoundStatus(
                phone.pairingId, phone.peerDeviceId, DeviceActionStatus("Sound request sent · waiting for the phone", now),
            )
            SmsCommand.StopAlarm -> deviceStateStore.saveSoundStatus(
                phone.pairingId, phone.peerDeviceId, DeviceActionStatus("Stop request sent · waiting for the phone", now),
            )
            is SmsCommand.ShowLockScreenMessage -> deviceStateStore.saveLockMessageStatus(
                phone.pairingId, phone.peerDeviceId, DeviceActionStatus("Message request sent · waiting for the phone", now),
            )
            else -> Unit
        }
    }

    private fun saveFailedCommandStatus(
        phone: PairedPhone,
        command: SmsCommand,
        reason: String,
        priorRealtimeStatus: RealtimeLocationStatus?,
    ) {
        val now = System.currentTimeMillis()
        val failure = "Not sent · ${reason.ifBlank { "SMS was not queued" }}"
        when (command) {
            is SmsCommand.SetRealtimeLocation -> {
                deviceStateStore.saveRealtimeStatus(
                    phone.pairingId,
                    phone.peerDeviceId,
                    (priorRealtimeStatus ?: RealtimeLocationStatus(!command.enabled, command.intervalMinutes, "", now))
                        .copy(message = failure, updatedAtEpochMillis = now),
                )
            }
            SmsCommand.StartAlarm, SmsCommand.StopAlarm -> deviceStateStore.saveSoundStatus(
                phone.pairingId, phone.peerDeviceId, DeviceActionStatus(failure, now),
            )
            is SmsCommand.ShowLockScreenMessage -> deviceStateStore.saveLockMessageStatus(
                phone.pairingId, phone.peerDeviceId, DeviceActionStatus(failure, now),
            )
            else -> Unit
        }
        refreshProtectedDevices()
    }

    private fun removePairedPhone(deviceId: String): UiActionResult {
        val phone = phoneCatalog.all().firstOrNull { it.catalogId == deviceId }
            ?: return UiActionResult.NOT_READY
        val keyRemoved = AndroidKeystorePairingKeyStore(this).removePairing(phone.pairingId, phone.peerDeviceId)
        if (!keyRemoved) {
            Toast.makeText(this, "The secure pairing key could not be removed.", Toast.LENGTH_LONG).show()
            return UiActionResult.FAILED
        }
        if (!phoneCatalog.remove(phone.pairingId, phone.peerDeviceId)) {
            Toast.makeText(this, "The secure key was revoked, but the device list could not be updated.", Toast.LENGTH_LONG).show()
            refreshProtectedDevices()
            return UiActionResult.FAILED
        }
        deviceStateStore.remove(phone.pairingId, phone.peerDeviceId)
        refreshProtectedDevices()
        return UiActionResult.REMOVED
    }

    private fun beginPairing(name: String, phoneNumber: String): UiActionResult {
        if (!ProtectionState.isSmsMonitoringEnabled(this)) {
            Toast.makeText(this, "Enable SMS permissions in Protection setup before pairing phones.", Toast.LENGTH_LONG).show()
            requestSetup()
            return UiActionResult.NOT_READY
        }
        val manager = PairingSmsRuntime.manager(this)
            ?: return pairingFailure("Android couldn't open secure pairing storage. Restart the phone and retry.")
        if (!manager.isStorageAvailable()) {
            return pairingFailure("Android couldn't save a secure pairing session. Check free phone storage and try again.")
        }
        return when (val result = manager.preparePair1(phoneNumber)) {
            is PairingPreparationResult.ReadyToSend -> {
                if (!phoneCatalog.savePendingLabel(result.message.sessionId, name)) {
                    manager.cancel(result.message.sessionId)
                    return pairingFailure("The phone name couldn't be saved, so the pairing request wasn't sent.")
                }
                when (val sent = AndroidSmsTextSender(this).send(result.message.destinationPhoneNumber, result.message.body)) {
                    is SmsTransportResult.Queued -> UiActionResult.QUEUED
                    else -> {
                        manager.cancel(result.message.sessionId)
                        phoneCatalog.removePendingLabel(result.message.sessionId)
                        pairingFailure(smsFailureMessage("Pairing request", sent))
                    }
                }
            }
            is PairingPreparationResult.Rejected -> {
                pairingFailure("Pairing couldn't start: ${pairingFailureReason(result.reason)}")
            }
        }
    }

    private fun acceptPairing(sessionId: String): UiActionResult {
        val manager = PairingSmsRuntime.manager(this) ?: return UiActionResult.FAILED
        if (!hasSmsPermissions()) {
            Toast.makeText(this, "SMS permission is needed to reply to the pairing request.", Toast.LENGTH_LONG).show()
            requestSetup()
            return UiActionResult.NOT_READY
        }
        return when (val result = manager.acceptPair1(sessionId)) {
            is PairingAcceptResult.Accepted -> {
                when (val sent = AndroidSmsTextSender(this).send(result.response.destinationPhoneNumber, result.response.body)) {
                    is SmsTransportResult.Queued -> {
                        refreshPairingState()
                        UiActionResult.QUEUED
                    }
                    else -> {
                        manager.cancel(sessionId)
                        refreshPairingState()
                        pairingFailure(smsFailureMessage("Pairing reply", sent))
                    }
                }
            }
            is PairingAcceptResult.Rejected -> {
                pairingFailure("Pairing couldn't continue: ${pairingFailureReason(result.reason)}")
            }
        }
    }

    private fun pairingFailure(message: String): UiActionResult {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        return UiActionResult.FAILED
    }

    private fun smsFailureMessage(subject: String, result: SmsTransportResult): String = when (result) {
        is SmsTransportResult.PermissionRequired -> "Allow SMS sending in Protection setup, then retry the $subject."
        is SmsTransportResult.Unsupported -> "$subject wasn't sent: ${result.reason}"
        is SmsTransportResult.Failed -> "$subject wasn't queued: ${result.reason}"
        is SmsTransportResult.Queued -> "$subject was queued."
    }

    private fun pairingFailureReason(reason: com.protectmyphone.app.pairing.PairingFailure): String = when (reason) {
        com.protectmyphone.app.pairing.PairingFailure.INVALID_PHONE_NUMBER -> "Enter a number with country code, such as +14155550123."
        com.protectmyphone.app.pairing.PairingFailure.PENDING_STORAGE_UNAVAILABLE -> "Android couldn't save a secure pairing session. Check free phone storage and try again."
        com.protectmyphone.app.pairing.PairingFailure.KEY_STORE_REJECTED -> "Android couldn't securely save the pairing key. Check that a screen lock is set, then try again."
        else -> reason.name.lowercase(Locale.ROOT).replace('_', ' ')
    }

    private fun confirmPairing(sessionId: String, localFingerprint: String, peerFingerprint: String): UiActionResult {
        val manager = PairingSmsRuntime.manager(this) ?: return UiActionResult.FAILED
        return when (val result = manager.confirmFingerprint(sessionId, localFingerprint, peerFingerprint, explicitlyConfirmedByUser = true)) {
            is PairingConfirmationResult.Paired -> {
                if (!phoneCatalog.upsert(result.pairing)) {
                    Toast.makeText(this, "The key was approved, but the device label could not be stored.", Toast.LENGTH_LONG).show()
                    UiActionResult.FAILED
                } else {
                    refreshPairingState()
                    refreshProtectedDevices()
                    UiActionResult.PAIRED
                }
            }
            is PairingConfirmationResult.Rejected -> {
                Toast.makeText(this, "Pairing wasn't approved: ${pairingFailureReason(result.reason)}", Toast.LENGTH_LONG).show()
                UiActionResult.FAILED
            }
        }
    }

    private fun cancelPairing(sessionId: String) {
        PairingSmsRuntime.manager(this)?.cancel(sessionId)
        refreshPairingState()
    }

    private fun relativeTime(epochMillis: Long): String =
        android.text.format.DateUtils.getRelativeTimeSpanString(epochMillis, System.currentTimeMillis(), 60_000L).toString()
}
