package com.protectmyphone.app.platform

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import java.util.concurrent.atomic.AtomicBoolean

sealed class DeviceOperationResult<out T> {
    data class Success<T>(val value: T) : DeviceOperationResult<T>()
    data class Pending(val reason: String) : DeviceOperationResult<Nothing>()
    data class PermissionRequired(
        val permission: String,
        val runtimePermission: Boolean,
        val reason: String,
    ) : DeviceOperationResult<Nothing>()
    data class Unsupported(val reason: String) : DeviceOperationResult<Nothing>()
    data class Failure(val reason: String) : DeviceOperationResult<Nothing>()
}

sealed class DeviceField<out T> {
    data class Available<T>(val value: T) : DeviceField<T>()
    data class PermissionRequired(val permission: String) : DeviceField<Nothing>()
    data class Unsupported(val reason: String) : DeviceField<Nothing>()
}

data class DeviceSnapshot(
    val batteryPercent: DeviceField<Int>,
    val charging: DeviceField<Boolean>,
    val wifiEnabled: DeviceField<Boolean>,
    val wifiConnected: DeviceField<Boolean>,
    val wifiSsid: DeviceField<String>,
    val cellularConnected: DeviceField<Boolean>,
    /** The public SDK does not expose a generally usable mobile-data enabled state. */
    val mobileDataEnabled: DeviceField<Boolean>,
)

data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAtEpochMillis: Long,
    val precision: LocationPrecision,
) {
    /** A shareable map link that contains coordinates and needs no app backend. */
    val mapsUrl: String get() = "https://maps.google.com/?q=$latitude,$longitude"
}

enum class LocationPrecision { PRECISE, APPROXIMATE }

/**
 * Thin Android adapter for actions that normal public Android APIs permit. The methods report
 * policy/permission limits explicitly; accepted asynchronous requests are returned as Pending.
 */
class AndroidDeviceController(
    context: Context,
    private val adminComponent: ComponentName? = null,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    fun readSnapshot(): DeviceOperationResult<DeviceSnapshot> {
        return try {
            val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val battery = if (level >= 0 && scale > 0) {
                DeviceField.Available(((level * 100f) / scale).toInt().coerceIn(0, 100))
            } else {
                DeviceField.Unsupported("Battery level is not available on this device.")
            }
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = if (status >= 0) {
                DeviceField.Available(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            } else {
                DeviceField.Unsupported("Charging state is not available.")
            }

            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiEnabled = if (wifi == null) {
                DeviceField.Unsupported("Wi-Fi service is unavailable.")
            } else {
                DeviceField.Available(wifi.isWifiEnabled)
            }
            val activeNetwork = activeNetworkCapabilities()
            val wifiConnected = activeNetwork?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val cellularConnected = activeNetwork?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val ssid = readWifiSsid(wifi, wifiConnected)

            DeviceOperationResult.Success(
                DeviceSnapshot(
                    batteryPercent = battery,
                    charging = charging,
                    wifiEnabled = wifiEnabled,
                    wifiConnected = DeviceField.Available(wifiConnected),
                    wifiSsid = ssid,
                    cellularConnected = DeviceField.Available(cellularConnected),
                    mobileDataEnabled = DeviceField.Unsupported(
                        "Android does not expose a generally available mobile-data toggle state to ordinary apps.",
                    ),
                ),
            )
        } catch (exception: SecurityException) {
            DeviceOperationResult.Failure("Android denied access while reading device status: ${exception.message ?: "permission denied"}")
        } catch (exception: RuntimeException) {
            DeviceOperationResult.Failure("Could not read device status: ${exception.message ?: "system service error"}")
        }
    }

    /**
     * Returns Android's cached fix without requesting a new location update. This keeps a status
     * SMS quick and low power; the captured timestamp lets the controller show how old it is.
     */
    fun lastKnownLocation(): DeviceLocation? {
        if (locationPermission() == null) return null
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            manager.getProviders(true)
                .asSequence()
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull(Location::getTime)
                ?.let(::toDeviceLocation)
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Requests a fresh one-shot location. Callback is delivered on the main thread. */
    fun locateOnce(
        timeoutMillis: Long = DEFAULT_LOCATION_TIMEOUT_MS,
        callback: (DeviceOperationResult<DeviceLocation>) -> Unit,
    ) {
        val permission = locationPermission()
        if (permission == null) {
            handler.post { callback(
                DeviceOperationResult.PermissionRequired(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    runtimePermission = true,
                    reason = "Grant location access to locate this phone.",
                ),
            ) }
            return
        }

        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            handler.post { callback(DeviceOperationResult.Unsupported("Location service is unavailable.")) }
            return
        }

        val providers = try {
            manager.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        } catch (exception: SecurityException) {
            handler.post { callback(DeviceOperationResult.PermissionRequired(permission, true, "Location permission is required.")) }
            return
        }
        if (providers.isEmpty()) {
            handler.post { callback(DeviceOperationResult.Unsupported("Location providers are disabled. Turn on Location in Android settings.")) }
            return
        }

        val done = AtomicBoolean(false)
        var bestLocation: Location? = null
        var timeout: Runnable? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done.get()) return
                if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) bestLocation = location
                if (location.accuracy <= GOOD_LOCATION_ACCURACY_METERS) finishLocation(
                    manager, this, timeout, done, callback, DeviceOperationResult.Success(toDeviceLocation(location)),
                )
            }

            @Deprecated("Deprecated by Android; the callback is not used for one-shot updates.")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        timeout = Runnable {
            val selected = bestLocation
            val result = if (selected != null) {
                DeviceOperationResult.Success(toDeviceLocation(selected))
            } else {
                DeviceOperationResult.Failure("Location request timed out without a fix.")
            }
            finishLocation(manager, listener, timeout, done, callback, result)
        }
        try {
            providers.forEach { provider ->
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
            handler.postDelayed(timeout!!, timeoutMillis.coerceIn(1_000L, MAX_LOCATION_TIMEOUT_MS))
        } catch (exception: SecurityException) {
            finishLocation(
                manager,
                listener,
                timeout,
                done,
                callback,
                DeviceOperationResult.PermissionRequired(permission, true, "Android denied location access."),
            )
        } catch (exception: RuntimeException) {
            finishLocation(
                manager,
                listener,
                timeout,
                done,
                callback,
                DeviceOperationResult.Failure("Could not start location request: ${exception.message ?: "provider error"}"),
            )
        }
    }

    /** Requires the user to activate the declared Device Admin receiver with force-lock policy. */
    fun lockNow(): DeviceOperationResult<Unit> {
        val component = adminComponent
            ?: return DeviceOperationResult.Unsupported("Set up the app's Device Admin permission before remote lock can be used.")
        val policy = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return DeviceOperationResult.Unsupported("Device Policy service is unavailable.")
        if (!policy.isAdminActive(component)) {
            return DeviceOperationResult.PermissionRequired(
                "android.app.admin.DEVICE_ADMIN_ENABLED",
                runtimePermission = false,
                reason = "The user must explicitly activate the app as a Device Admin.",
            )
        }
        return try {
            policy.lockNow()
            DeviceOperationResult.Success(Unit)
        } catch (exception: SecurityException) {
            DeviceOperationResult.Unsupported("This Device Admin has no active force-lock policy: ${exception.message ?: "policy denied"}")
        } catch (exception: RuntimeException) {
            DeviceOperationResult.Failure("Android could not lock the device: ${exception.message ?: "device policy error"}")
        }
    }

    /** Only a fully managed device owner can set owner information shown on the lock screen. */
    fun setLockScreenMessage(message: String): DeviceOperationResult<Unit> {
        if (message.isBlank() || message.length > MAX_LOCK_SCREEN_MESSAGE_CHARS || message.any { it.isISOControl() }) {
            return DeviceOperationResult.Failure("Enter a non-empty lock-screen message of at most $MAX_LOCK_SCREEN_MESSAGE_CHARS characters.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return DeviceOperationResult.Unsupported("Android 7.0 or later is required to set device-owner lock-screen information.")
        }
        if (!isDeviceOwner()) {
            return DeviceOperationResult.Unsupported("Only a fully managed Device Owner app can set lock-screen owner information.")
        }
        val policy = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return DeviceOperationResult.Unsupported("Device Policy service is unavailable.")
        val component = adminComponent
            ?: return DeviceOperationResult.Unsupported("Set up the app's Device Admin component before setting lock-screen information.")
        return try {
            policy.setDeviceOwnerLockScreenInfo(component, message)
            DeviceOperationResult.Success(Unit)
        } catch (exception: SecurityException) {
            DeviceOperationResult.Unsupported("Android denied lock-screen owner information: ${exception.message ?: "device owner required"}")
        } catch (exception: RuntimeException) {
            DeviceOperationResult.Failure("Could not set lock-screen information: ${exception.message ?: "device policy error"}")
        }
    }

    /** Plays the system alarm ringtone on API 28+, where looping is supported. */
    fun startAlarm(): DeviceOperationResult<Unit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return DeviceOperationResult.Unsupported("Continuous looping alarm playback requires Android 9 or later.")
        }
        synchronized(alarmLock) {
            if (activeAlarm?.isPlaying == true) {
                return DeviceOperationResult.Success(Unit)
            }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: return DeviceOperationResult.Unsupported("No default alarm ringtone is configured.")
            val ringtone = RingtoneManager.getRingtone(appContext, uri)
                ?: return DeviceOperationResult.Unsupported("The default alarm ringtone is unavailable.")
            return try {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtone.isLooping = true
                ringtone.play()
                activeAlarm = ringtone
                DeviceOperationResult.Pending("Alarm playback was requested. Keep the app's foreground service alive while it rings.")
            } catch (exception: SecurityException) {
                DeviceOperationResult.Unsupported("Android denied alarm playback: ${exception.message ?: "audio access denied"}")
            } catch (exception: RuntimeException) {
                DeviceOperationResult.Failure("Could not start the alarm: ${exception.message ?: "audio error"}")
            }
        }
    }

    fun stopAlarm(): DeviceOperationResult<Unit> = synchronized(alarmLock) {
        val ringtone = activeAlarm ?: return@synchronized DeviceOperationResult.Success(Unit)
        return@synchronized try {
            ringtone.stop()
            activeAlarm = null
            DeviceOperationResult.Success(Unit)
        } catch (exception: RuntimeException) {
            DeviceOperationResult.Failure("Could not stop the alarm: ${exception.message ?: "audio error"}")
        }
    }

    private fun finishLocation(
        manager: LocationManager,
        listener: LocationListener,
        timeout: Runnable?,
        done: AtomicBoolean,
        callback: (DeviceOperationResult<DeviceLocation>) -> Unit,
        result: DeviceOperationResult<DeviceLocation>,
    ) {
        if (!done.compareAndSet(false, true)) return
        timeout?.let(handler::removeCallbacks)
        try {
            manager.removeUpdates(listener)
        } catch (_: SecurityException) {
            // The original result remains explicit; cleanup failure does not fabricate a fix.
        } catch (_: RuntimeException) {
            // A provider may disappear between request and cleanup.
        }
        handler.post { callback(result) }
    }

    private fun readWifiSsid(wifi: WifiManager?, connected: Boolean): DeviceField<String> {
        if (wifi == null) return DeviceField.Unsupported("Wi-Fi service is unavailable.")
        if (!connected) return DeviceField.Available("")
        val hasFineLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasNearbyWifi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (!hasFineLocation && !hasNearbyWifi) {
            return DeviceField.PermissionRequired(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return try {
            @Suppress("DEPRECATION")
            val ssid = wifi.connectionInfo?.ssid
            if (ssid.isNullOrBlank() || ssid == WifiManager.UNKNOWN_SSID || ssid == "<unknown ssid>") {
                DeviceField.Unsupported("Android withheld the Wi-Fi network name. Check Location and nearby-device access.")
            } else {
                DeviceField.Available(ssid.removeSurrounding("\""))
            }
        } catch (exception: SecurityException) {
            DeviceField.PermissionRequired(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES
                else Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }

    private fun activeNetworkCapabilities(): NetworkCapabilities? {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    }

    private fun locationPermission(): String? = when {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> Manifest.permission.ACCESS_FINE_LOCATION
        hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) -> Manifest.permission.ACCESS_COARSE_LOCATION
        else -> null
    }

    private fun toDeviceLocation(location: Location) = DeviceLocation(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyMeters = location.accuracy,
        capturedAtEpochMillis = location.time,
        precision = if (locationPermission() == Manifest.permission.ACCESS_FINE_LOCATION) {
            LocationPrecision.PRECISE
        } else {
            LocationPrecision.APPROXIMATE
        },
    )

    private fun isDeviceOwner(): Boolean {
        val policy = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return try {
            policy.isDeviceOwnerApp(appContext.packageName)
        } catch (_: SecurityException) {
            false
        }
    }

    private fun hasPermission(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val DEFAULT_LOCATION_TIMEOUT_MS = 20_000L
        const val MAX_LOCATION_TIMEOUT_MS = 60_000L
        const val GOOD_LOCATION_ACCURACY_METERS = 75f
        const val MAX_LOCK_SCREEN_MESSAGE_CHARS = 200
        val alarmLock = Any()
        var activeAlarm: Ringtone? = null
    }
}
