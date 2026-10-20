package com.protectmyphone.app.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrCodeTest {
    private val fingerprint = "A1B2C3D4".repeat(8)

    @Test
    fun versionedPayloadRoundTripsTheFingerprint() {
        val payload = PairingQrCode.payload(fingerprint.lowercase())

        assertEquals("PROTECT-MY-PHONE:PAIRING-FINGERPRINT:1:$fingerprint", payload)
        assertEquals(fingerprint, PairingQrCode.parse(payload))
    }

    @Test
    fun parserRejectsOtherQrCodesAndMalformedFingerprints() {
        assertNull(PairingQrCode.parse("https://example.com"))
        assertNull(PairingQrCode.parse("PROTECT-MY-PHONE:PAIRING-FINGERPRINT:1:ABC"))
        assertNull(PairingQrCode.parse("PROTECT-MY-PHONE:PAIRING-FINGERPRINT:2:$fingerprint"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun payloadRejectsMalformedFingerprint() {
        PairingQrCode.payload("not-a-fingerprint")
    }
}
