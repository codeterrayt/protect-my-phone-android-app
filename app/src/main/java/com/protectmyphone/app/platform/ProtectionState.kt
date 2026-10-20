package com.protectmyphone.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.FEATURE_TELEPHONY
import java.util.UUID

/** Stable local identifier; it contains no phone number, account ID, or hardware identifier. */
object DeviceIdentity {
    private const val PREFS = "pmp_local_identity_v1"
    private const val KEY_DEVICE_ID = "device_id"

    @Synchronized
    fun getOrCreate(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = preferences.getString(KEY_DEVICE_ID, null)
        if (existing != null && isValidDeviceId(existing)) return existing
        val created = UUID.randomUUID().toString()
        if (!preferences.edit().putString(KEY_DEVICE_ID, created).commit()) {
            throw IllegalStateException("Could not persist the local device identifier.")
        }
        return created
    }

    private fun isValidDeviceId(value: String) =
        value.length in 1..64 && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
}

data class RealtimeTrackingSession(
    val pairingId: String,
    val controllerDeviceId: String,
    val requestNonce: String,
    val intervalMinutes: Int,
)

/** Explicitly user-enabled SMS monitoring and authenticated live-location state. */
object ProtectionState {
    private const val PREFS = "pmp_protection_state_v1"
    private const val KEY_SMS_MONITORING_ENABLED = "sms_monitoring_enabled"
    private const val KEY_TRACKING_ENABLED = "tracking_enabled"
    private const val KEY_TRACKING_PAIRING_ID = "tracking_pairing_id"
    private const val KEY_TRACKING_CONTROLLER_ID = "tracking_controller_id"
    private const val KEY_TRACKING_REQUEST_NONCE = "tracking_request_nonce"
    private const val KEY_TRACKING_INTERVAL_MINUTES = "tracking_interval_minutes"
    private const val KEY_LAST_LATITUDE = "last_location_latitude"
    private const val KEY_LAST_LONGITUDE = "last_location_longitude"
    const val MOVEMENT_THRESHOLD_METERS = 50.0

    fun isSmsMonitoringEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_SMS_MONITORING_ENABLED, false)

    /** Call only after an explicit local user action in setup. */
    fun setSmsMonitoringEnabled(context: Context, enabled: Boolean): DeviceOperationResult<Unit> {
        if (!enabled) {
            return if (preferences(context).edit().putBoolean(KEY_SMS_MONITORING_ENABLED, false).commit()) {
                clearRealtimeTracking(context)
                context.applicationContext.stopService(Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
                DeviceOperationResult.Success(Unit)
            } else {
                DeviceOperationResult.Failure("Could not save SMS monitoring state.")
            }
        }
        if (!context.packageManager.hasSystemFeature(FEATURE_TELEPHONY)) {
            return DeviceOperationResult.Unsupported("This device does not have telephony support for SMS commands.")
        }
        if (context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            return DeviceOperationResult.PermissionRequired(
                Manifest.permission.RECEIVE_SMS,
                runtimePermission = true,
                reason = "Grant SMS access before enabling command monitoring.",
            )
        }
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return DeviceOperationResult.PermissionRequired(
                Manifest.permission.SEND_SMS,
                runtimePermission = true,
                reason = "Grant SMS sending before enabling monitoring so this phone can return encrypted replies.",
            )
        }
        return if (preferences(context).edit().putBoolean(KEY_SMS_MONITORING_ENABLED, true).commit()) {
            DeviceOperationResult.Success(Unit)
        } else {
            DeviceOperationResult.Failure("Could not save SMS monitoring state.")
        }
    }

    fun isRealtimeTrackingEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_TRACKING_ENABLED, false)

    fun clearRealtimeTracking(context: Context): Boolean = preferences(context).edit()
        .putBoolean(KEY_TRACKING_ENABLED, false)
        .remove(KEY_TRACKING_PAIRING_ID)
        .remove(KEY_TRACKING_CONTROLLER_ID)
        .remove(KEY_TRACKING_REQUEST_NONCE)
        .remove(KEY_LAST_LATITUDE)
        .remove(KEY_LAST_LONGITUDE)
        .commit()

    /** Persisted only after an authenticated command arrives from an approved pairing. */
    fun applyAuthenticatedRealtimeCommand(
        context: Context,
        authorization: SmsCommandAuthenticationResult.Authorized,
    ): Boolean {
        val command = authorization.command as? SmsCommand.SetRealtimeLocation ?: return false
        val prefs = preferences(context)
        val editor = prefs.edit()
        return if (command.enabled) {
            if (command.intervalMinutes !in SmsCommandParser.MIN_REALTIME_INTERVAL_MINUTES..SmsCommandParser.MAX_REALTIME_INTERVAL_MINUTES) {
                return false
            }
            val sameTrackingController = prefs.getBoolean(KEY_TRACKING_ENABLED, false) &&
                prefs.getString(KEY_TRACKING_PAIRING_ID, null) == authorization.pairingId &&
                prefs.getString(KEY_TRACKING_CONTROLLER_ID, null) == authorization.senderDeviceId
            editor.putBoolean(KEY_TRACKING_ENABLED, true)
                .putString(KEY_TRACKING_PAIRING_ID, authorization.pairingId)
                .putString(KEY_TRACKING_CONTROLLER_ID, authorization.senderDeviceId)
                .putString(KEY_TRACKING_REQUEST_NONCE, authorization.requestNonce)
                .putInt(KEY_TRACKING_INTERVAL_MINUTES, command.intervalMinutes)
            if (!sameTrackingController) {
                editor.remove(KEY_LAST_LATITUDE).remove(KEY_LAST_LONGITUDE)
            }
            editor
                .commit()
        } else {
            editor.putBoolean(KEY_TRACKING_ENABLED, false)
                .remove(KEY_TRACKING_PAIRING_ID)
                .remove(KEY_TRACKING_CONTROLLER_ID)
                .remove(KEY_TRACKING_REQUEST_NONCE)
                .remove(KEY_LAST_LATITUDE)
                .remove(KEY_LAST_LONGITUDE)
                .commit()
        }
    }

    fun realtimeTrackingSession(context: Context): RealtimeTrackingSession? {
        val prefs = preferences(context)
        if (!prefs.getBoolean(KEY_TRACKING_ENABLED, false)) return null
        val pairingId = prefs.getString(KEY_TRACKING_PAIRING_ID, null) ?: return null
        val controllerId = prefs.getString(KEY_TRACKING_CONTROLLER_ID, null) ?: return null
        val nonce = prefs.getString(KEY_TRACKING_REQUEST_NONCE, null) ?: return null
        val interval = prefs.getInt(KEY_TRACKING_INTERVAL_MINUTES, SmsCommandParser.DEFAULT_REALTIME_INTERVAL_MINUTES)
        if (interval !in SmsCommandParser.MIN_REALTIME_INTERVAL_MINUTES..SmsCommandParser.MAX_REALTIME_INTERVAL_MINUTES) return null
        return RealtimeTrackingSession(pairingId, controllerId, nonce, interval)
    }

    fun lastReportedLocation(context: Context): Pair<Double, Double>? {
        val prefs = preferences(context)
        if (!prefs.contains(KEY_LAST_LATITUDE) || !prefs.contains(KEY_LAST_LONGITUDE)) return null
        val latitude = prefs.getString(KEY_LAST_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_LAST_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return if (latitude in -90.0..90.0 && longitude in -180.0..180.0) latitude to longitude else null
    }

    /** Store the comparison point only after SMS was queued; failed sends are retried on next fix. */
    fun markLocationReported(context: Context, latitude: Double, longitude: Double): Boolean {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0) {
            return false
        }
        return preferences(context).edit()
            .putString(KEY_LAST_LATITUDE, latitude.toString())
            .putString(KEY_LAST_LONGITUDE, longitude.toString())
            .commit()
    }

    fun shouldReportLocation(context: Context, latitude: Double, longitude: Double): Boolean {
        val previous = lastReportedLocation(context) ?: return true
        return distanceMeters(previous.first, previous.second, latitude, longitude) >= MOVEMENT_THRESHOLD_METERS
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * earthRadiusMeters * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }
}
