package com.protectmyphone.app.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsMessage
import com.protectmyphone.app.R
import com.protectmyphone.app.pairing.PairingSmsRuntime
import com.protectmyphone.app.security.AndroidKeystorePairingKeyStore
import com.protectmyphone.app.security.AndroidSmsReplayGuard
import com.protectmyphone.app.security.SmsCommandSecurity
import java.util.concurrent.Executors

/** User must explicitly enable SMS monitoring in setup after SMS permissions are granted. */
class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val appContext = context.applicationContext
        if (!ProtectionState.isSmsMonitoringEnabled(appContext) || !hasSmsPermissions(appContext)) return

        val pending = goAsync()
        inboundExecutor.execute {
            try {
                handleInbound(appContext, intent)
            } catch (_: Exception) {
                // Malformed SMS, system API failures and corrupt state must never run a command.
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleInbound(context: Context, intent: Intent) {
        if (!ProtectionState.isSmsMonitoringEnabled(context) || !hasSmsPermissions(context)) return
        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (_: RuntimeException) {
            return
        } ?: return
        if (messages.isEmpty()) return
        val originatingAddress = messages.firstOrNull()?.originatingAddress?.trim().orEmpty()
        if (originatingAddress.isBlank() || messages.any { it.originatingAddress?.trim() != originatingAddress }) return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isBlank() || body.length > 4096) return

        // Pairing SMS is staged for the UI only; it never bypasses explicit owner approval.
        if (PairingSmsRuntime.receive(context, originatingAddress, body) != null) return

        val localDeviceId = try {
            DeviceIdentity.getOrCreate(context)
        } catch (_: RuntimeException) {
            return
        }
        val security = security(context)
        val pendingRequests = PendingSmsRequestStore(context)
        val authentication = security.authenticateInbound(
            body = body,
            smsOriginatingAddress = originatingAddress,
            expectedTargetDeviceId = localDeviceId,
            nowEpochMillis = System.currentTimeMillis(),
            isExpectedReply = { pairingId, peerDeviceId, requestNonce ->
                pendingRequests.expectsReply(pairingId, peerDeviceId, requestNonce)
            },
        )
        when (authentication) {
            is SmsInboundAuthenticationResult.CommandAuthorized ->
                dispatchAuthorized(context, security, localDeviceId, authentication.authorization)
            is SmsInboundAuthenticationResult.ReplyAuthorized -> {
                val authorization = authentication.authorization
                if (pendingRequests.acceptReply(
                        authorization.pairingId,
                        authorization.senderDeviceId,
                        authorization.reply,
                    )
                ) {
                    SmsReplyEvents.publish(
                        SmsReplyUpdate(
                            pairingId = authorization.pairingId,
                            senderDeviceId = authorization.senderDeviceId,
                            reply = authorization.reply,
                            receivedAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            is SmsInboundAuthenticationResult.Rejected -> Unit
        }
    }

    private fun dispatchAuthorized(
        context: Context,
        security: SmsCommandSecurity,
        localDeviceId: String,
        authorization: SmsCommandAuthenticationResult.Authorized,
    ) {
        val controller = AndroidDeviceController(context, ComponentName(context, PhoneAdminReceiver::class.java))
        when (val command = authorization.command) {
            SmsCommand.GetStatus -> {
                val result = controller.readSnapshot()
                if (result is DeviceOperationResult.Success) {
                    sendReply(
                        context, security, localDeviceId, authorization,
                        SmsReply.Status(
                            authorization.requestNonce,
                            result.value,
                            controller.lastKnownLocation(),
                        ),
                    )
                } else {
                    sendOperationAck(context, security, localDeviceId, authorization, commandName(command), result)
                }
            }
            SmsCommand.Locate -> {
                when (val start = TrackingService.startOneShotLocation(context, authorization)) {
                    is DeviceOperationResult.Pending -> Unit // The service sends a correlated reply after getting a fix.
                    else -> sendOperationAck(context, security, localDeviceId, authorization, commandName(command), start)
                }
            }
            SmsCommand.Lock -> sendOperationAck(
                context, security, localDeviceId, authorization, commandName(command), controller.lockNow(),
            )
            SmsCommand.StartAlarm -> sendOperationAck(
                context, security, localDeviceId, authorization, commandName(command), controller.startAlarm(),
            )
            SmsCommand.StopAlarm -> sendOperationAck(
                context, security, localDeviceId, authorization, commandName(command), controller.stopAlarm(),
            )
            is SmsCommand.ShowLockScreenMessage -> sendOperationAck(
                context, security, localDeviceId, authorization, commandName(command), controller.setLockScreenMessage(command.message),
            )
            is SmsCommand.SetRealtimeLocation -> handleRealtimeCommand(
                context, security, localDeviceId, authorization, command,
            )
        }
    }

    private fun handleRealtimeCommand(
        context: Context,
        security: SmsCommandSecurity,
        localDeviceId: String,
        authorization: SmsCommandAuthenticationResult.Authorized,
        command: SmsCommand.SetRealtimeLocation,
    ) {
        if (!command.enabled) {
            if (!ProtectionState.applyAuthenticatedRealtimeCommand(context, authorization)) {
                sendAck(context, security, localDeviceId, authorization, "REALTIME", SmsReplyStatus.FAILED, "Could not save tracking state.")
                return
            }
            context.stopService(Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
            sendAck(context, security, localDeviceId, authorization, "REALTIME", SmsReplyStatus.SUCCEEDED, "Live location is off.")
            return
        }

        val locationPermission = realtimeLocationPermissionFailure(context)
        if (locationPermission != null) {
            sendOperationAck(context, security, localDeviceId, authorization, "REALTIME", locationPermission)
            return
        }
        if (!ProtectionState.applyAuthenticatedRealtimeCommand(context, authorization)) {
            sendAck(context, security, localDeviceId, authorization, "REALTIME", SmsReplyStatus.FAILED, "Could not save tracking state.")
            return
        }
        when (val start = TrackingService.startRealtime(context)) {
            is DeviceOperationResult.Pending -> sendOperationAck(context, security, localDeviceId, authorization, "REALTIME", start)
            else -> {
                ProtectionState.clearRealtimeTracking(context)
                sendOperationAck(context, security, localDeviceId, authorization, "REALTIME", start)
            }
        }
    }

    private fun sendOperationAck(
        context: Context,
        security: SmsCommandSecurity,
        localDeviceId: String,
        authorization: SmsCommandAuthenticationResult.Authorized,
        commandName: String,
        result: DeviceOperationResult<*>,
    ) {
        val (status, detail) = when (result) {
            is DeviceOperationResult.Success -> SmsReplyStatus.SUCCEEDED to "Action completed."
            is DeviceOperationResult.Pending -> SmsReplyStatus.PENDING to result.reason
            is DeviceOperationResult.PermissionRequired -> SmsReplyStatus.PERMISSION_REQUIRED to result.reason
            is DeviceOperationResult.Unsupported -> SmsReplyStatus.UNSUPPORTED to result.reason
            is DeviceOperationResult.Failure -> SmsReplyStatus.FAILED to result.reason
        }
        sendAck(context, security, localDeviceId, authorization, commandName, status, detail)
    }

    private fun sendAck(
        context: Context,
        security: SmsCommandSecurity,
        localDeviceId: String,
        authorization: SmsCommandAuthenticationResult.Authorized,
        commandName: String,
        status: SmsReplyStatus,
        detail: String,
    ) {
        sendReply(
            context, security, localDeviceId, authorization,
            SmsReply.Acknowledgement(
                authorization.requestNonce,
                commandName,
                status,
                detail.take(140),
                System.currentTimeMillis(),
            ),
        )
    }

    private fun sendReply(
        context: Context,
        security: SmsCommandSecurity,
        localDeviceId: String,
        authorization: SmsCommandAuthenticationResult.Authorized,
        reply: SmsReply,
    ) {
        val sealed = security.sealReply(
            pairingId = authorization.pairingId,
            peerDeviceId = authorization.senderDeviceId,
            senderDeviceId = localDeviceId,
            reply = reply,
            issuedAtEpochMillis = System.currentTimeMillis(),
        ) ?: return
        AndroidSmsReplySender(context).send(sealed)
    }

    private fun commandName(command: SmsCommand): String = when (command) {
        SmsCommand.GetStatus -> "STATUS"
        SmsCommand.Locate -> "LOCATE"
        SmsCommand.Lock -> "LOCK"
        SmsCommand.StartAlarm -> "ALARM_START"
        SmsCommand.StopAlarm -> "ALARM_STOP"
        is SmsCommand.SetRealtimeLocation -> "REALTIME"
        is SmsCommand.ShowLockScreenMessage -> "SHOW_MESSAGE"
    }

    companion object {
        private val inboundExecutor = Executors.newSingleThreadExecutor()

        private fun hasSmsPermissions(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }
}

/** Device Owner enrollment is managed by Android and cannot be granted as a runtime permission. */
class PhoneAdminReceiver : DeviceAdminReceiver()

/** Resumes only a previously authenticated live-location session. */
class BootStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!ProtectionState.isSmsMonitoringEnabled(context) || !ProtectionState.isRealtimeTrackingEnabled(context)) return
        if (realtimeLocationPermissionFailure(context) != null || ProtectionState.realtimeTrackingSession(context) == null) return
        try {
            context.startForegroundService(Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_RESUME))
        } catch (_: RuntimeException) {
            // Android can reject background foreground-service starts. Keep the state visible for setup to explain.
        }
    }
}

/** Visible, bounded foreground service for one-shot and explicitly enabled live location. */
class TrackingService : Service() {
    private var locationManager: LocationManager? = null
    private var listener: LocationListener? = null
    private var startIdForOneShot: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLocationUpdates()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_LOCATE) {
            startIdForOneShot = startId
            if (realtimeLocationPermissionFailure(this) != null) {
                sendLocationFailure(intent, "Background location permission is required to locate by SMS.", SmsReplyStatus.PERMISSION_REQUIRED)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            if (!startVisibleForeground()) {
                sendLocationFailure(intent, "Android did not allow the location service to start.", SmsReplyStatus.FAILED)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            performOneShot(intent)
            return START_NOT_STICKY
        }

        val session = ProtectionState.realtimeTrackingSession(this)
        if (!ProtectionState.isSmsMonitoringEnabled(this) || session == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (realtimeLocationPermissionFailure(this) != null) {
            ProtectionState.clearRealtimeTracking(this)
            sendTrackingAck(session, SmsReplyStatus.PERMISSION_REQUIRED, "Background location permission is no longer available.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!startVisibleForeground()) {
            ProtectionState.clearRealtimeTracking(this)
            sendTrackingAck(session, SmsReplyStatus.FAILED, "Android did not allow the location service to start.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!requestRealtimeUpdates(session)) {
            ProtectionState.clearRealtimeTracking(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }

    private fun startVisibleForeground(): Boolean = try {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Phone protection", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Visible location protection status" },
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Location protection is active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun performOneShot(intent: Intent) {
        val pairingId = intent.getStringExtra(EXTRA_PAIRING_ID) ?: return stopSelf()
        val controllerId = intent.getStringExtra(EXTRA_CONTROLLER_ID) ?: return stopSelf()
        val requestNonce = intent.getStringExtra(EXTRA_REQUEST_NONCE) ?: return stopSelf()
        val localId = intent.getStringExtra(EXTRA_LOCAL_DEVICE_ID) ?: return stopSelf()
        val security = security(this)
        AndroidDeviceController(this, ComponentName(this, PhoneAdminReceiver::class.java)).locateOnce { result ->
            when (result) {
                is DeviceOperationResult.Success -> sendServiceReply(
                    security, localId, pairingId, controllerId,
                    SmsReply.Location(requestNonce, result.value, realtimeUpdate = false),
                )
                else -> {
                    val (status, detail) = operationReply(result)
                    sendServiceReply(
                        security, localId, pairingId, controllerId,
                        SmsReply.Acknowledgement(requestNonce, "LOCATE", status, detail.take(140), System.currentTimeMillis()),
                    )
                }
            }
            startIdForOneShot?.let { stopSelf(it) }
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun requestRealtimeUpdates(session: RealtimeTrackingSession): Boolean {
        // A new authenticated REALTIME_ON can change the interval. Replace the old listener
        // instead of registering duplicate providers and sending duplicate movement reports.
        stopLocationUpdates()
        val manager = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return false
        val providers = try {
            manager.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        } catch (_: SecurityException) {
            emptyList()
        }
        if (providers.isEmpty()) {
            sendTrackingAck(session, SmsReplyStatus.UNSUPPORTED, "No enabled location provider is available.")
            return false
        }
        val security = security(this)
        val localId = try { DeviceIdentity.getOrCreate(this) } catch (_: RuntimeException) { return false }
        val updateListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!ProtectionState.isRealtimeTrackingEnabled(this@TrackingService)) return
                if (!ProtectionState.shouldReportLocation(this@TrackingService, location.latitude, location.longitude)) return
                val reply = SmsReply.Location(
                    session.requestNonce,
                    DeviceLocation(
                        location.latitude,
                        location.longitude,
                        location.accuracy,
                        location.time,
                        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) LocationPrecision.PRECISE
                        else LocationPrecision.APPROXIMATE,
                    ),
                    realtimeUpdate = true,
                )
                val sealed = security.sealReply(
                    session.pairingId,
                    session.controllerDeviceId,
                    localId,
                    reply,
                    System.currentTimeMillis(),
                ) ?: return
                val sendResult = AndroidSmsReplySender(this@TrackingService).send(sealed)
                if (sendResult is SmsTransportResult.Queued) {
                    ProtectionState.markLocationReported(this@TrackingService, location.latitude, location.longitude)
                }
            }

            @Deprecated("Deprecated Android callback; not used for location updates.")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        return try {
            val intervalMillis = session.intervalMinutes.coerceIn(
                SmsCommandParser.MIN_REALTIME_INTERVAL_MINUTES,
                SmsCommandParser.MAX_REALTIME_INTERVAL_MINUTES,
            ) * 60_000L
            providers.forEach { provider ->
                manager.requestLocationUpdates(
                    provider,
                    intervalMillis,
                    ProtectionState.MOVEMENT_THRESHOLD_METERS.toFloat(),
                    updateListener,
                    Looper.getMainLooper(),
                )
            }
            locationManager = manager
            listener = updateListener
            true
        } catch (_: SecurityException) {
            sendTrackingAck(session, SmsReplyStatus.PERMISSION_REQUIRED, "Android denied location updates.")
            false
        } catch (_: RuntimeException) {
            sendTrackingAck(session, SmsReplyStatus.FAILED, "Could not start location updates.")
            false
        }
    }

    private fun sendLocationFailure(intent: Intent, detail: String, status: SmsReplyStatus) {
        val session = sessionFromIntent(intent) ?: return
        sendSessionAck(session, "LOCATE", status, detail)
    }

    private fun sessionFromIntent(intent: Intent): RealtimeTrackingSession? {
        val pairingId = intent.getStringExtra(EXTRA_PAIRING_ID) ?: return null
        val controllerId = intent.getStringExtra(EXTRA_CONTROLLER_ID) ?: return null
        val requestNonce = intent.getStringExtra(EXTRA_REQUEST_NONCE) ?: return null
        val interval = intent.getIntExtra(EXTRA_INTERVAL_MINUTES, SmsCommandParser.DEFAULT_REALTIME_INTERVAL_MINUTES)
        return RealtimeTrackingSession(pairingId, controllerId, requestNonce, interval)
    }

    private fun sendTrackingAck(session: RealtimeTrackingSession, status: SmsReplyStatus, detail: String) =
        sendSessionAck(session, "REALTIME", status, detail)

    private fun sendSessionAck(session: RealtimeTrackingSession, command: String, status: SmsReplyStatus, detail: String) {
        val localId = try { DeviceIdentity.getOrCreate(this) } catch (_: RuntimeException) { return }
        sendServiceReply(
            security(this), localId, session.pairingId, session.controllerDeviceId,
            SmsReply.Acknowledgement(session.requestNonce, command, status, detail.take(140), System.currentTimeMillis()),
        )
    }

    private fun sendServiceReply(
        security: SmsCommandSecurity,
        localDeviceId: String,
        pairingId: String,
        peerDeviceId: String,
        reply: SmsReply,
    ) {
        val sealed = security.sealReply(pairingId, peerDeviceId, localDeviceId, reply, System.currentTimeMillis()) ?: return
        AndroidSmsReplySender(this).send(sealed)
    }

    private fun stopLocationUpdates() {
        val manager = locationManager
        val currentListener = listener
        if (manager != null && currentListener != null) {
            try { manager.removeUpdates(currentListener) } catch (_: RuntimeException) { }
        }
        locationManager = null
        listener = null
    }

    private fun hasPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun operationReply(result: DeviceOperationResult<*>): Pair<SmsReplyStatus, String> = when (result) {
        is DeviceOperationResult.Success -> SmsReplyStatus.SUCCEEDED to "Location received."
        is DeviceOperationResult.Pending -> SmsReplyStatus.PENDING to result.reason
        is DeviceOperationResult.PermissionRequired -> SmsReplyStatus.PERMISSION_REQUIRED to result.reason
        is DeviceOperationResult.Unsupported -> SmsReplyStatus.UNSUPPORTED to result.reason
        is DeviceOperationResult.Failure -> SmsReplyStatus.FAILED to result.reason
    }

    companion object {
        const val ACTION_RESUME = "com.protectmyphone.app.action.RESUME_TRACKING"
        const val ACTION_LOCATE = "com.protectmyphone.app.action.LOCATE_ONCE"
        const val ACTION_STOP = "com.protectmyphone.app.action.STOP_TRACKING"
        private const val EXTRA_PAIRING_ID = "pairing_id"
        private const val EXTRA_CONTROLLER_ID = "controller_id"
        private const val EXTRA_REQUEST_NONCE = "request_nonce"
        private const val EXTRA_LOCAL_DEVICE_ID = "local_device_id"
        private const val EXTRA_INTERVAL_MINUTES = "interval_minutes"
        private const val CHANNEL_ID = "phone_protection_location"
        private const val NOTIFICATION_ID = 4192

        fun startRealtime(context: Context): DeviceOperationResult<Unit> = try {
            context.startForegroundService(Intent(context, TrackingService::class.java).setAction(ACTION_RESUME))
            DeviceOperationResult.Pending("Location monitoring was requested. Android may pause or stop background work.")
        } catch (exception: RuntimeException) {
            DeviceOperationResult.Failure("Android did not allow location monitoring to start: ${exception.message ?: "service start blocked"}")
        }

        fun startOneShotLocation(
            context: Context,
            authorization: SmsCommandAuthenticationResult.Authorized,
        ): DeviceOperationResult<Unit> {
            val permissionFailure = realtimeLocationPermissionFailure(context)
            if (permissionFailure != null) return permissionFailure
            val interval = SmsCommandParser.DEFAULT_REALTIME_INTERVAL_MINUTES
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_LOCATE)
                .putExtra(EXTRA_PAIRING_ID, authorization.pairingId)
                .putExtra(EXTRA_CONTROLLER_ID, authorization.senderDeviceId)
                .putExtra(EXTRA_REQUEST_NONCE, authorization.requestNonce)
                .putExtra(EXTRA_LOCAL_DEVICE_ID, DeviceIdentity.getOrCreate(context))
                .putExtra(EXTRA_INTERVAL_MINUTES, interval)
            return try {
                context.startForegroundService(intent)
                DeviceOperationResult.Pending("A location request is running in the foreground.")
            } catch (exception: RuntimeException) {
                DeviceOperationResult.Failure("Android did not allow a location request to start: ${exception.message ?: "service start blocked"}")
            }
        }
    }
}

private fun security(context: Context) = SmsCommandSecurity(
    AndroidKeystorePairingKeyStore(context),
    AndroidSmsReplayGuard(context),
)

private fun realtimeLocationPermissionFailure(context: Context): DeviceOperationResult.PermissionRequired? {
    val hasLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!hasLocation) {
        return DeviceOperationResult.PermissionRequired(
            Manifest.permission.ACCESS_FINE_LOCATION,
            runtimePermission = true,
            reason = "Grant location access before using remote location commands.",
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) {
        return DeviceOperationResult.PermissionRequired(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            runtimePermission = true,
            reason = "Allow background location so the phone can answer an SMS while its app is not open.",
        )
    }
    return null
}
