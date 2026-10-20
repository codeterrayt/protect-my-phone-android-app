package com.protectmyphone.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmsReplyCodecTest {
    private val nonce = "AAECAwQFBgcICQoL"
    private val snapshot = DeviceSnapshot(
        batteryPercent = DeviceField.Available(82),
        charging = DeviceField.Available(false),
        wifiEnabled = DeviceField.Available(true),
        wifiConnected = DeviceField.Available(true),
        wifiSsid = DeviceField.Available("Family Wi-Fi"),
        cellularConnected = DeviceField.Available(false),
        mobileDataEnabled = DeviceField.Unsupported("Unavailable"),
    )

    @Test
    fun statusCarriesCachedLocationInTheSameReply() {
        val location = DeviceLocation(
            latitude = 40.782914,
            longitude = -73.965414,
            accuracyMeters = 14.6f,
            capturedAtEpochMillis = 1_797_000_123_456L,
            precision = LocationPrecision.PRECISE,
        )

        val encoded = SmsReplyCodec.encode(SmsReply.Status(nonce, snapshot, location))
        assertTrue(encoded.orEmpty().toByteArray().size <= 512)
        val parsed = SmsReplyCodec.parseAuthenticatedPayload(encoded!!)

        assertTrue(parsed is SmsReplyParseResult.Parsed)
        val reply = (parsed as SmsReplyParseResult.Parsed).reply as SmsReply.Status
        assertEquals(82, (reply.snapshot.batteryPercent as DeviceField.Available).value)
        assertEquals("Family Wi-Fi", (reply.snapshot.wifiSsid as DeviceField.Available).value)
        assertEquals(40.78291, reply.location!!.latitude, 0.000001)
        assertEquals(-73.96541, reply.location.longitude, 0.000001)
        assertEquals(15f, reply.location.accuracyMeters)
        assertEquals(1_797_000_123_000L, reply.location.capturedAtEpochMillis)
        assertEquals(LocationPrecision.PRECISE, reply.location.precision)
    }

    @Test
    fun statusWithoutLocationAndOlderStatusPayloadRemainReadable() {
        val current = SmsReplyCodec.encode(SmsReply.Status(nonce, snapshot))!!
        val currentReply = (SmsReplyCodec.parseAuthenticatedPayload(current) as SmsReplyParseResult.Parsed).reply
        assertNull((currentReply as SmsReply.Status).location)

        val old = "R1|STATUS|$nonce|82|0|1|1|A|RmFtaWx5IFdpLUZp|0|X"
        val oldReply = (SmsReplyCodec.parseAuthenticatedPayload(old) as SmsReplyParseResult.Parsed).reply
        assertEquals("Family Wi-Fi", ((oldReply as SmsReply.Status).snapshot.wifiSsid as DeviceField.Available).value)
        assertNull(oldReply.location)
    }
}
