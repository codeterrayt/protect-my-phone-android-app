package com.protectmyphone.app.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Base64
import com.protectmyphone.app.security.SmsCommandSecurity
import com.protectmyphone.app.security.SealedSmsMessage
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

sealed class SmsReply(open val requestNonce: String) {
    data class Acknowledgement(
        override val requestNonce: String,
        val commandName: String,
        val status: SmsReplyStatus,
        val detail: String,
        val createdAtEpochMillis: Long,
    ) : SmsReply(requestNonce)

    data class Status(
        override val requestNonce: String,
        val snapshot: DeviceSnapshot,
        /** Cached location only; reading status never starts a GPS scan. */
        val location: DeviceLocation? = null,
    ) : SmsReply(requestNonce)

    data class Location(
        override val requestNonce: String,
        val location: DeviceLocation,
        val realtimeUpdate: Boolean,
    ) : SmsReply(requestNonce)
}

enum class SmsReplyStatus { SUCCEEDED, PENDING, PERMISSION_REQUIRED, UNSUPPORTED, FAILED }

sealed class SmsReplyParseResult {
    data class Parsed(val reply: SmsReply) : SmsReplyParseResult()
    object Rejected : SmsReplyParseResult()
}

sealed class SmsReplyAuthenticationResult {
    data class Authorized(
        val senderDeviceId: String,
        val pairingId: String,
        val reply: SmsReply,
    ) : SmsReplyAuthenticationResult()

    data class Rejected(val reason: SmsAuthenticationFailure) : SmsReplyAuthenticationResult()
}

/** Strict, bounded reply payload; the enclosing SMS must already pass AES-GCM authentication. */
object SmsReplyCodec {
    private const val MAX_REPLY_BYTES = 512
    private const val MAX_DETAIL_BYTES = 280
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun encode(reply: SmsReply): String? {
        if (!validNonce(reply.requestNonce)) return null
        val fields = when (reply) {
            is SmsReply.Acknowledgement -> {
                if (!reply.commandName.matches(Regex("[A-Z_]{1,32}")) || reply.createdAtEpochMillis <= 0L ||
                    reply.detail.toByteArray(StandardCharsets.UTF_8).size > MAX_DETAIL_BYTES ||
                    reply.detail.any { it.isISOControl() }
                ) return null
                listOf(
                    "R1", "ACK", reply.requestNonce, reply.commandName, reply.status.name,
                    reply.createdAtEpochMillis.toString(), encodeText(reply.detail),
                )
            }
            is SmsReply.Status -> {
                val snapshot = reply.snapshot
                val ssid = fieldToken(snapshot.wifiSsid)
                val location = reply.location
                if (location != null && !location.isValid()) return null
                listOf(
                    "R1", "STATUS", reply.requestNonce,
                    intField(snapshot.batteryPercent), boolField(snapshot.charging),
                    boolField(snapshot.wifiEnabled), boolField(snapshot.wifiConnected),
                    fieldAvailability(snapshot.wifiSsid), ssid,
                    boolField(snapshot.cellularConnected), boolField(snapshot.mobileDataEnabled),
                    if (location == null) "0" else "1",
                    location?.let { (it.latitude * LOCATION_SCALE).roundToInt().toString() } ?: "-",
                    location?.let { (it.longitude * LOCATION_SCALE).roundToInt().toString() } ?: "-",
                    location?.let { it.accuracyMeters.roundToInt().coerceAtMost(MAX_LOCATION_ACCURACY_METERS).toString() } ?: "-",
                    location?.let { (it.capturedAtEpochMillis / 1000L).toString() } ?: "-",
                    location?.let { if (it.precision == LocationPrecision.PRECISE) "P" else "A" } ?: "-",
                )
            }
            is SmsReply.Location -> {
                val location = reply.location
                if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
                    !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ||
                    !location.accuracyMeters.isFinite() || location.accuracyMeters < 0f ||
                    location.capturedAtEpochMillis <= 0L
                ) return null
                listOf(
                    "R1", if (reply.realtimeUpdate) "TRACKING" else "LOCATION", reply.requestNonce,
                    location.latitude.toString(), location.longitude.toString(),
                    location.accuracyMeters.toString(), location.capturedAtEpochMillis.toString(),
                    location.precision.name,
                )
            }
        }
        val payload = fields.joinToString("|")
        return payload.takeIf { it.toByteArray(StandardCharsets.UTF_8).size <= MAX_REPLY_BYTES }
    }

    fun parseAuthenticatedPayload(payload: String): SmsReplyParseResult {
        if (payload.toByteArray(StandardCharsets.UTF_8).size > MAX_REPLY_BYTES) return SmsReplyParseResult.Rejected
        val fields = payload.split('|', limit = 17)
        if (fields.size < 3 || fields[0] != "R1" || !validNonce(fields[2])) return SmsReplyParseResult.Rejected
        val requestNonce = fields[2]
        return when (fields[1]) {
            "ACK" -> {
                if (fields.size != 7 || !fields[3].matches(Regex("[A-Z_]{1,32}"))) return SmsReplyParseResult.Rejected
                val status = SmsReplyStatus.entries.firstOrNull { it.name == fields[4] } ?: return SmsReplyParseResult.Rejected
                val createdAt = fields[5].toLongOrNull()?.takeIf { it > 0L } ?: return SmsReplyParseResult.Rejected
                val detail = decodeText(fields[6]) ?: return SmsReplyParseResult.Rejected
                SmsReplyParseResult.Parsed(SmsReply.Acknowledgement(requestNonce, fields[3], status, detail, createdAt))
            }
            "STATUS" -> {
                if (fields.size != 11 && fields.size != 17) return SmsReplyParseResult.Rejected
                val battery = parseIntField(fields[3]) ?: return SmsReplyParseResult.Rejected
                val charging = parseBoolField(fields[4]) ?: return SmsReplyParseResult.Rejected
                val wifiEnabled = parseBoolField(fields[5]) ?: return SmsReplyParseResult.Rejected
                val wifiConnected = parseBoolField(fields[6]) ?: return SmsReplyParseResult.Rejected
                val ssid = parseStringField(fields[7], fields[8]) ?: return SmsReplyParseResult.Rejected
                val cellular = parseBoolField(fields[9]) ?: return SmsReplyParseResult.Rejected
                val mobileData = parseBoolField(fields[10]) ?: return SmsReplyParseResult.Rejected
                val location = if (fields.size == 11 || fields[11] == "0") {
                    if (fields.size == 17 && fields.drop(12).any { it != "-" }) return SmsReplyParseResult.Rejected
                    null
                } else {
                    if (fields[11] != "1") return SmsReplyParseResult.Rejected
                    val latitudeE5 = fields[12].toIntOrNull()?.takeIf { it in -9_000_000..9_000_000 }
                        ?: return SmsReplyParseResult.Rejected
                    val longitudeE5 = fields[13].toIntOrNull()?.takeIf { it in -18_000_000..18_000_000 }
                        ?: return SmsReplyParseResult.Rejected
                    val accuracy = fields[14].toIntOrNull()?.takeIf { it in 0..MAX_LOCATION_ACCURACY_METERS }
                        ?: return SmsReplyParseResult.Rejected
                    val capturedAt = fields[15].toLongOrNull()?.takeIf { it > 0L && it <= Long.MAX_VALUE / 1000L }
                        ?: return SmsReplyParseResult.Rejected
                    val precision = when (fields[16]) {
                        "P" -> LocationPrecision.PRECISE
                        "A" -> LocationPrecision.APPROXIMATE
                        else -> return SmsReplyParseResult.Rejected
                    }
                    DeviceLocation(
                        latitude = latitudeE5 / LOCATION_SCALE,
                        longitude = longitudeE5 / LOCATION_SCALE,
                        accuracyMeters = accuracy.toFloat(),
                        capturedAtEpochMillis = capturedAt * 1000L,
                        precision = precision,
                    )
                }
                SmsReplyParseResult.Parsed(
                    SmsReply.Status(
                        requestNonce,
                        DeviceSnapshot(battery, charging, wifiEnabled, wifiConnected, ssid, cellular, mobileData),
                        location,
                    ),
                )
            }
            "LOCATION", "TRACKING" -> {
                if (fields.size != 8) return SmsReplyParseResult.Rejected
                val latitude = fields[3].toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
                    ?: return SmsReplyParseResult.Rejected
                val longitude = fields[4].toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
                    ?: return SmsReplyParseResult.Rejected
                val accuracy = fields[5].toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
                    ?: return SmsReplyParseResult.Rejected
                val capturedAt = fields[6].toLongOrNull()?.takeIf { it > 0L } ?: return SmsReplyParseResult.Rejected
                val precision = LocationPrecision.entries.firstOrNull { it.name == fields[7] }
                    ?: return SmsReplyParseResult.Rejected
                SmsReplyParseResult.Parsed(
                    SmsReply.Location(
                        requestNonce,
                        DeviceLocation(latitude, longitude, accuracy, capturedAt, precision),
                        realtimeUpdate = fields[1] == "TRACKING",
                    ),
                )
            }
            else -> SmsReplyParseResult.Rejected
        }
    }

    private fun validNonce(value: String): Boolean {
        if (value.isEmpty() || value.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) return false
        val decoded = try { Base64.decode(value, BASE64_FLAGS) } catch (_: IllegalArgumentException) { return false }
        return decoded.size == 12 && Base64.encodeToString(decoded, BASE64_FLAGS) == value
    }

    private fun DeviceLocation.isValid(): Boolean = latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 && accuracyMeters.isFinite() &&
        accuracyMeters >= 0f && capturedAtEpochMillis > 0L

    private fun encodeText(value: String) = Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), BASE64_FLAGS)

    private fun decodeText(encoded: String): String? {
        val bytes = try { Base64.decode(encoded, BASE64_FLAGS) } catch (_: IllegalArgumentException) { return null }
        if (bytes.size > MAX_DETAIL_BYTES || Base64.encodeToString(bytes, BASE64_FLAGS) != encoded) return null
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) { null }
    }

    private fun intField(field: DeviceField<Int>): String = when (field) {
        is DeviceField.Available -> field.value.takeIf { it in 0..100 }?.toString() ?: "X"
        is DeviceField.PermissionRequired -> "X"
        is DeviceField.Unsupported -> "X"
    }

    private fun boolField(field: DeviceField<Boolean>): String = when (field) {
        is DeviceField.Available -> if (field.value) "1" else "0"
        is DeviceField.PermissionRequired -> "X"
        is DeviceField.Unsupported -> "X"
    }

    private fun fieldAvailability(field: DeviceField<String>): String = when (field) {
        is DeviceField.Available -> "A"
        is DeviceField.PermissionRequired -> "P"
        is DeviceField.Unsupported -> "X"
    }

    private fun fieldToken(field: DeviceField<String>): String = when (field) {
        is DeviceField.Available -> encodeText(field.value)
        is DeviceField.PermissionRequired -> encodeText(field.permission)
        is DeviceField.Unsupported -> "-"
    }

    private fun parseIntField(value: String): DeviceField<Int>? = when (value) {
        "X" -> DeviceField.Unsupported("Unavailable")
        else -> value.toIntOrNull()?.takeIf { it in 0..100 }?.let { DeviceField.Available<Int>(it) }
    }

    private fun parseBoolField(value: String): DeviceField<Boolean>? = when (value) {
        "1" -> DeviceField.Available<Boolean>(true)
        "0" -> DeviceField.Available<Boolean>(false)
        "X" -> DeviceField.Unsupported("Unavailable")
        else -> null
    }

    private fun parseStringField(availability: String, encoded: String): DeviceField<String>? = when (availability) {
        "A" -> decodeText(encoded)?.let { DeviceField.Available<String>(it) }
        "P" -> decodeText(encoded)?.let { DeviceField.PermissionRequired(it) }
        "X" -> if (encoded == "-") DeviceField.Unsupported("Unavailable") else null
        else -> null
    }

    private const val LOCATION_SCALE = 100_000.0
    private const val MAX_LOCATION_ACCURACY_METERS = 999_999
}

/** Sends an app-originated command only to the phone number stored with its approved key. */
class AndroidSmsCommandSender(
    context: Context,
    private val security: SmsCommandSecurity,
) {
    private val appContext = context.applicationContext

    fun send(
        pairingId: String,
        peerDeviceId: String,
        localDeviceId: String,
        command: SmsCommand,
        issuedAtEpochMillis: Long = System.currentTimeMillis(),
    ): SmsTransportResult {
        if (appContext.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SmsTransportResult.PermissionRequired(android.Manifest.permission.SEND_SMS)
        }
        val payload = SmsCommandParser.encode(command)
            ?: return SmsTransportResult.Failed("This command contains invalid or oversized data.")
        val sealed = security.sealCommand(pairingId, peerDeviceId, localDeviceId, payload, issuedAtEpochMillis)
            ?: return SmsTransportResult.Unsupported("No usable approved pairing is available for this command.")
        val longLived = command is SmsCommand.SetRealtimeLocation && command.enabled
        val pending = PendingSmsRequestStore(appContext)
        if (!pending.record(pairingId, peerDeviceId, sealed.messageNonce, issuedAtEpochMillis, longLived)) {
            return SmsTransportResult.Failed("Could not save secure reply correlation state; command was not sent.")
        }
        val result = AndroidSmsTransmitter(appContext).send(sealed)
        if (result !is SmsTransportResult.Queued) {
            pending.remove(pairingId, peerDeviceId, sealed.messageNonce)
        } else if (command is SmsCommand.SetRealtimeLocation) {
            pending.clearLongLived(
                pairingId,
                peerDeviceId,
                retainRequestNonce = sealed.messageNonce.takeIf { command.enabled },
            )
        }
        return result
    }
}

/** Nonces for commands sent from this phone; replies without a matching request are rejected. */
class PendingSmsRequestStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun record(
        pairingId: String,
        peerDeviceId: String,
        requestNonce: String,
        createdAtEpochMillis: Long,
        keepForTracking: Boolean,
    ): Boolean = synchronized(processLock) {
        if (!validIdentifier(pairingId) || !validIdentifier(peerDeviceId) || !validNonce(requestNonce) ||
            createdAtEpochMillis <= 0L
        ) return@synchronized false
        val key = key(pairingId, peerDeviceId, requestNonce)
        if (preferences.contains(key)) return@synchronized false
        val now = System.currentTimeMillis()
        val expired = expiredEntries(now)
        val count = preferences.all.keys.count { it.startsWith(ENTRY_PREFIX) } - expired.size
        if (count >= MAX_ENTRIES) return@synchronized false
        val value = listOf(
            pairingId,
            peerDeviceId,
            requestNonce,
            createdAtEpochMillis.toString(),
            if (keepForTracking) "1" else "0",
        ).joinToString("|")
        val editor = preferences.edit()
        expired.forEach { editor.remove(it) }
        editor.putString(key, value).commit()
    }

    fun expectsReply(
        pairingId: String,
        peerDeviceId: String,
        requestNonce: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(processLock) {
        val key = key(pairingId, peerDeviceId, requestNonce)
        val entry = read(key) ?: return@synchronized false
        if (entry.pairingId != pairingId || entry.peerDeviceId != peerDeviceId || entry.requestNonce != requestNonce) {
            return@synchronized false
        }
        if (!entry.keepForTracking && nowEpochMillis - entry.createdAtEpochMillis > NORMAL_REQUEST_TTL_MS) {
            preferences.edit().remove(key).commit()
            false
        } else {
            true
        }
    }

    /** Retain the live-location correlation for update SMS; remove completed or failed requests. */
    fun acceptReply(pairingId: String, peerDeviceId: String, reply: SmsReply): Boolean = synchronized(processLock) {
        val key = key(pairingId, peerDeviceId, reply.requestNonce)
        val entry = read(key) ?: return@synchronized false
        if (entry.pairingId != pairingId || entry.peerDeviceId != peerDeviceId) return@synchronized false
        val isLiveUpdate = reply is SmsReply.Location && reply.realtimeUpdate
        val isTrackingStarted = reply is SmsReply.Acknowledgement && reply.commandName == "REALTIME" &&
            reply.status == SmsReplyStatus.PENDING
        if (entry.keepForTracking && (isLiveUpdate || isTrackingStarted)) return@synchronized true
        preferences.edit().remove(key).commit()
    }

    fun remove(pairingId: String, peerDeviceId: String, requestNonce: String): Boolean = synchronized(processLock) {
        preferences.edit().remove(key(pairingId, peerDeviceId, requestNonce)).commit()
    }

    /** Stop an old streaming reply session after the corresponding REALTIME_OFF is queued. */
    fun clearLongLived(
        pairingId: String,
        peerDeviceId: String,
        retainRequestNonce: String? = null,
    ): Boolean = synchronized(processLock) {
        val editor = preferences.edit()
        preferences.all.forEach { (entryKey, value) ->
            if (!entryKey.startsWith(ENTRY_PREFIX)) return@forEach
            val entry = parse(value as? String) ?: return@forEach
            if (entry.keepForTracking && entry.pairingId == pairingId && entry.peerDeviceId == peerDeviceId &&
                entry.requestNonce != retainRequestNonce
            ) {
                editor.remove(entryKey)
            }
        }
        editor.commit()
    }

    private fun expiredEntries(nowEpochMillis: Long): List<String> = preferences.all.mapNotNull { (entryKey, value) ->
        if (!entryKey.startsWith(ENTRY_PREFIX)) return@mapNotNull null
        val entry = parse(value as? String) ?: return@mapNotNull entryKey
        if (!entry.keepForTracking && nowEpochMillis - entry.createdAtEpochMillis > NORMAL_REQUEST_TTL_MS) entryKey else null
    }

    private fun read(key: String): Entry? = parse(preferences.getString(key, null))

    private fun parse(value: String?): Entry? {
        val fields = value?.split('|') ?: return null
        if (fields.size != 5 || !validIdentifier(fields[0]) || !validIdentifier(fields[1]) ||
            !validNonce(fields[2]) || fields[4] !in setOf("0", "1")
        ) return null
        val createdAt = fields[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return Entry(fields[0], fields[1], fields[2], createdAt, fields[4] == "1")
    }

    private fun key(pairingId: String, peerDeviceId: String, nonce: String): String {
        val data = "$pairingId\u0000$peerDeviceId\u0000$nonce".toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return ENTRY_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    private fun validIdentifier(value: String) = value.length in 1..64 && value.all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
    }

    private fun validNonce(value: String): Boolean {
        if (value.isEmpty() || value.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) return false
        val decoded = try { Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        catch (_: IllegalArgumentException) { return false }
        return decoded.size == 12
    }

    private data class Entry(
        val pairingId: String,
        val peerDeviceId: String,
        val requestNonce: String,
        val createdAtEpochMillis: Long,
        val keepForTracking: Boolean,
    )

    private companion object {
        private const val PREFERENCES_NAME = "pmp_pending_sms_requests_v1"
        private const val ENTRY_PREFIX = "request_"
        private const val MAX_ENTRIES = 256
        private const val NORMAL_REQUEST_TTL_MS = 24 * 60 * 60 * 1000L
        private val processLock = Any()
    }
}

data class SmsReplyUpdate(
    val pairingId: String,
    val senderDeviceId: String,
    val reply: SmsReply,
    val receivedAtEpochMillis: Long,
)

fun interface SmsReplyListener {
    fun onReply(update: SmsReplyUpdate)
}

/** In-process event hook for an open UI; decrypted reply contents are not stored on disk. */
object SmsReplyEvents {
    private val listeners = CopyOnWriteArraySet<SmsReplyListener>()
    private val latestReplies = ConcurrentHashMap<String, SmsReplyUpdate>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun subscribe(listener: SmsReplyListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    fun latest(pairingId: String, senderDeviceId: String): SmsReplyUpdate? =
        latestReplies[eventKey(pairingId, senderDeviceId)]

    internal fun publish(update: SmsReplyUpdate) {
        latestReplies[eventKey(update.pairingId, update.senderDeviceId)] = update
        val notifyListeners = {
            listeners.forEach { listener ->
                try { listener.onReply(update) } catch (_: RuntimeException) { }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) notifyListeners()
        else mainHandler.post { notifyListeners() }
    }

    private fun eventKey(pairingId: String, senderDeviceId: String) = "$pairingId\u0000$senderDeviceId"
}

/** Sends only the exact body and approved destination returned by [SmsCommandSecurity]. */
class AndroidSmsReplySender(context: Context) {
    private val appContext = context.applicationContext

    fun send(message: SealedSmsMessage): SmsTransportResult = AndroidSmsTransmitter(appContext).send(message)
}

/** Sends an owner-requested pairing SMS without handing it to a separate composer app. */
class AndroidSmsTextSender(context: Context) {
    private val appContext = context.applicationContext

    fun send(destinationPhoneNumber: String, body: String): SmsTransportResult =
        AndroidSmsTransmitter(appContext).send(destinationPhoneNumber, body)
}

private class AndroidSmsTransmitter(private val context: Context) {
    fun send(message: SealedSmsMessage): SmsTransportResult =
        send(message.destinationPhoneNumber, message.body)

    fun send(destinationPhoneNumber: String, body: String): SmsTransportResult {
        if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SmsTransportResult.PermissionRequired(android.Manifest.permission.SEND_SMS)
        }
        if (body.isBlank() || destinationPhoneNumber.isBlank()) {
            return SmsTransportResult.Failed("The SMS is missing its body or destination.")
        }
        return try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(body)
            if (parts.isEmpty() || parts.size > MAX_PARTS) {
                return SmsTransportResult.Unsupported("The SMS is too large to send safely.")
            }
            if (parts.size == 1) {
                smsManager.sendTextMessage(destinationPhoneNumber, null, parts[0], null, null)
            } else {
                smsManager.sendMultipartTextMessage(destinationPhoneNumber, null, parts, null, null)
            }
            SmsTransportResult.Queued(parts.size)
        } catch (exception: SecurityException) {
            SmsTransportResult.PermissionRequired(android.Manifest.permission.SEND_SMS)
        } catch (exception: IllegalArgumentException) {
            SmsTransportResult.Failed("Android rejected the SMS destination or message: ${exception.message ?: "invalid SMS"}")
        } catch (exception: RuntimeException) {
            SmsTransportResult.Failed("Android could not queue the SMS: ${exception.message ?: "SMS service error"}")
        }
    }

    private companion object {
        const val MAX_PARTS = 10
    }
}
