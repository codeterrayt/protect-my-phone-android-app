package com.protectmyphone.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmsCommandParserTest {
    @Test
    fun everySupportedCommandRoundTripsThroughTheCanonicalGrammar() {
        val commands = listOf(
            SmsCommand.GetStatus,
            SmsCommand.Locate,
            SmsCommand.Lock,
            SmsCommand.StartAlarm,
            SmsCommand.StopAlarm,
            SmsCommand.SetRealtimeLocation(true),
            SmsCommand.SetRealtimeLocation(false),
            SmsCommand.ShowLockScreenMessage("Please call +1 555 0100 📍"),
        )

        commands.forEach { command ->
            val encoded = SmsCommandParser.encode(command)
            assertTrue("Command should encode: $command", encoded != null)
            assertEquals(
                "Command should parse back: $command",
                SmsCommandParseResult.Parsed(command),
                SmsCommandParser.parseAuthenticatedPayload(encoded!!),
            )
        }
    }

    @Test
    fun parserRejectsAmbiguousOrUnexpectedSpellings() {
        val invalidCommands = listOf(
            " status",
            "STATUS ",
            "status",
            "STATUS|LOCK",
            "LOCK;ALARM_START",
            "WIFI_ON",
            "WIFI_OFF",
            "DATA_ON",
            "DATA_OFF",
            "UNKNOWN",
            "",
        )

        invalidCommands.forEach { payload ->
            assertTrue(
                "Unexpected payload should be rejected: '$payload'",
                SmsCommandParser.parseAuthenticatedPayload(payload) is SmsCommandParseResult.Rejected,
            )
        }
        assertEquals(
            SmsCommandParseFailure.EMPTY,
            (SmsCommandParser.parseAuthenticatedPayload("") as SmsCommandParseResult.Rejected).reason,
        )
    }

    @Test
    fun parserRejectsPayloadsOverTheWireLimit() {
        val result = SmsCommandParser.parseAuthenticatedPayload("A".repeat(513))
        assertEquals(
            SmsCommandParseFailure.TOO_LARGE,
            (result as SmsCommandParseResult.Rejected).reason,
        )
    }

    @Test
    fun lockScreenMessageIsUtf8SafeAndCannotBreakCommandFraming() {
        val message = "Lost phone — call me at 555 0123 📍"
        val encoded = SmsCommandParser.encode(SmsCommand.ShowLockScreenMessage(message))!!
        assertFalse("Message must not appear in clear text", encoded.contains("555 0123"))
        assertEquals(
            SmsCommandParseResult.Parsed(SmsCommand.ShowLockScreenMessage(message)),
            SmsCommandParser.parseAuthenticatedPayload(encoded),
        )

        val malformed = listOf(
            "SHOW_MESSAGE|%%%",
            "SHOW_MESSAGE|",
            "SHOW_MESSAGE|" + "A".repeat(600),
            "SHOW_MESSAGE|_w", // Invalid one-byte UTF-8 sequence.
        )
        malformed.forEach { payload ->
            assertTrue(
                "Malformed lock-screen message should be rejected",
                SmsCommandParser.parseAuthenticatedPayload(payload) is SmsCommandParseResult.Rejected,
            )
        }
    }

    @Test
    fun lockScreenMessageEncoderRejectsBlankControlAndOversizedText() {
        assertNull(SmsCommandParser.encode(SmsCommand.ShowLockScreenMessage(" \t ")))
        assertNull(SmsCommandParser.encode(SmsCommand.ShowLockScreenMessage("hello\nworld")))
        assertNull(SmsCommandParser.encode(SmsCommand.ShowLockScreenMessage("x".repeat(141))))
        assertNull(SmsCommandParser.encode(SmsCommand.ShowLockScreenMessage("📍".repeat(71))))
    }
}
