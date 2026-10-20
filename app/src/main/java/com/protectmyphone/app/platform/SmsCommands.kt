package com.protectmyphone.app.platform

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Commands that are understood only after their SMS envelope has been authenticated. */
sealed class SmsCommand {
    object GetStatus : SmsCommand()
    object Locate : SmsCommand()
    object Lock : SmsCommand()
    object StartAlarm : SmsCommand()
    object StopAlarm : SmsCommand()
    data class SetRealtimeLocation(val enabled: Boolean, val intervalMinutes: Int = 15) : SmsCommand()
    data class ShowLockScreenMessage(val message: String) : SmsCommand()
}

sealed class SmsCommandParseResult {
    data class Parsed(val command: SmsCommand) : SmsCommandParseResult()
    data class Rejected(val reason: SmsCommandParseFailure) : SmsCommandParseResult()
}

enum class SmsCommandParseFailure {
    EMPTY,
    TOO_LARGE,
    MALFORMED,
    UNKNOWN_COMMAND,
    INVALID_ARGUMENT,
}

/**
 * Strict codec for the plaintext inside an authenticated envelope. Do not call this on an
 * unauthenticated SMS and do not execute a parsed command until authentication succeeds.
 * The fixed grammar avoids ambiguity and SHOW_MESSAGE uses base64url so message text cannot
 * alter command framing.
 */
object SmsCommandParser {
    private const val MAX_PAYLOAD_BYTES = 512
    private const val MAX_MESSAGE_BYTES = 280
    private const val MAX_MESSAGE_CHARACTERS = 140
    private const val SHOW_MESSAGE_PREFIX = "SHOW_MESSAGE|"
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun parseAuthenticatedPayload(payload: String): SmsCommandParseResult {
        if (payload.isEmpty()) return rejected(SmsCommandParseFailure.EMPTY)
        if (payload.toByteArray(StandardCharsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
            return rejected(SmsCommandParseFailure.TOO_LARGE)
        }

        val command = when (payload) {
            "STATUS" -> SmsCommand.GetStatus
            "LOCATE" -> SmsCommand.Locate
            "LOCK" -> SmsCommand.Lock
            "ALARM_START" -> SmsCommand.StartAlarm
            "ALARM_STOP" -> SmsCommand.StopAlarm
            "REALTIME_ON" -> SmsCommand.SetRealtimeLocation(true, DEFAULT_REALTIME_INTERVAL_MINUTES)
            "REALTIME_OFF" -> SmsCommand.SetRealtimeLocation(false)
            else -> {
                if (payload.startsWith("REALTIME_ON|")) {
                    val interval = payload.removePrefix("REALTIME_ON|").toIntOrNull()
                    if (interval == null || interval !in MIN_REALTIME_INTERVAL_MINUTES..MAX_REALTIME_INTERVAL_MINUTES) {
                        return rejected(SmsCommandParseFailure.INVALID_ARGUMENT)
                    }
                    return SmsCommandParseResult.Parsed(SmsCommand.SetRealtimeLocation(true, interval))
                }
                if (!payload.startsWith(SHOW_MESSAGE_PREFIX)) {
                    return rejected(SmsCommandParseFailure.UNKNOWN_COMMAND)
                }
                decodeMessage(payload.removePrefix(SHOW_MESSAGE_PREFIX))
                    ?: return rejected(SmsCommandParseFailure.INVALID_ARGUMENT)
            }
        }
        return SmsCommandParseResult.Parsed(command)
    }

    /** Returns null if the command cannot be represented by the bounded wire grammar. */
    fun encode(command: SmsCommand): String? = when (command) {
        SmsCommand.GetStatus -> "STATUS"
        SmsCommand.Locate -> "LOCATE"
        SmsCommand.Lock -> "LOCK"
        SmsCommand.StartAlarm -> "ALARM_START"
        SmsCommand.StopAlarm -> "ALARM_STOP"
        is SmsCommand.SetRealtimeLocation -> if (command.enabled) {
            if (command.intervalMinutes !in MIN_REALTIME_INTERVAL_MINUTES..MAX_REALTIME_INTERVAL_MINUTES) null
            else "REALTIME_ON|${command.intervalMinutes}"
        } else "REALTIME_OFF"
        is SmsCommand.ShowLockScreenMessage -> {
            val bytes = command.message.toByteArray(StandardCharsets.UTF_8)
            if (command.message.isBlank() || command.message.length > MAX_MESSAGE_CHARACTERS ||
                bytes.size > MAX_MESSAGE_BYTES || command.message.any { it.isISOControl() }
            ) {
                null
            } else {
                SHOW_MESSAGE_PREFIX + Base64.encodeToString(bytes, BASE64_FLAGS)
            }
        }
    }

    private fun decodeMessage(encoded: String): SmsCommand.ShowLockScreenMessage? {
        if (encoded.isEmpty() || encoded.length > MAX_MESSAGE_BYTES * 2) return null
        val decoded = try {
            Base64.decode(encoded, BASE64_FLAGS)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.isEmpty() || decoded.size > MAX_MESSAGE_BYTES) return null

        val message = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        if (message.isBlank() || message.length > MAX_MESSAGE_CHARACTERS || message.any { it.isISOControl() }) {
            return null
        }
        return SmsCommand.ShowLockScreenMessage(message)
    }

    private fun rejected(reason: SmsCommandParseFailure) = SmsCommandParseResult.Rejected(reason)

    const val MIN_REALTIME_INTERVAL_MINUTES = 5
    const val MAX_REALTIME_INTERVAL_MINUTES = 24 * 60
    const val DEFAULT_REALTIME_INTERVAL_MINUTES = 15
}

data class InboundSms(
    val senderAddress: String,
    val body: String,
    val receivedAtEpochMillis: Long,
)

sealed class SmsTransportResult {
    /** SmsManager queued the request; this does not mean the carrier delivered it. */
    data class Queued(val segmentCount: Int) : SmsTransportResult()
    data class PermissionRequired(val permission: String) : SmsTransportResult()
    data class Unsupported(val reason: String) : SmsTransportResult()
    data class Failed(val reason: String) : SmsTransportResult()
}

sealed class SmsCommandAuthenticationResult {
    data class Authorized(
        val senderDeviceId: String,
        val senderPhoneNumber: String,
        val pairingId: String,
        /** The authenticated request nonce; replies must carry this exact correlation value. */
        val requestNonce: String,
        val command: SmsCommand,
        val issuedAtEpochMillis: Long,
    ) : SmsCommandAuthenticationResult()

    data class Rejected(val reason: SmsAuthenticationFailure) : SmsCommandAuthenticationResult()
}

/** One-pass classification prevents trying separate authenticators against the same replay nonce. */
sealed class SmsInboundAuthenticationResult {
    data class CommandAuthorized(val authorization: SmsCommandAuthenticationResult.Authorized) : SmsInboundAuthenticationResult()
    data class ReplyAuthorized(val authorization: SmsReplyAuthenticationResult.Authorized) : SmsInboundAuthenticationResult()
    data class Rejected(val reason: SmsAuthenticationFailure) : SmsInboundAuthenticationResult()
}

enum class SmsAuthenticationFailure {
    MALFORMED_ENVELOPE,
    UNSUPPORTED_VERSION,
    UNPAIRED_SENDER,
    WRONG_TARGET,
    EXPIRED_OR_FUTURE,
    INVALID_AUTHENTICATOR,
    REPLAYED_OR_REPLAY_STORE_UNAVAILABLE,
    INVALID_COMMAND,
    WRONG_CORRELATION,
}

/** Boundary for the SMS adapter/receiver. Implementations must authenticate before dispatch. */
interface SmsCommandGateway {
    fun send(destinationAddress: String, body: String): SmsTransportResult
    fun receive(message: InboundSms): SmsCommandAuthenticationResult
}
