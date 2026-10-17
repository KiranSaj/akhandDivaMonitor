package com.kiransaj.diyamonitor.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class MessageStampTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val at0214 = (2 * 60 + 14) * 60_000L

    @Test
    fun `time goes on the second line`() {
        assertEquals(
            "Title\n🕒 02:14\nBody\nMore",
            MessageStamp.stamp("Title\nBody\nMore", at0214, at0214 + 5_000, utc),
        )
    }

    @Test
    fun `single line message gets the time appended`() {
        assertEquals("Hello\n🕒 02:14", MessageStamp.stamp("Hello", at0214, at0214, utc))
    }

    @Test
    fun `late delivery is marked as delayed`() {
        assertEquals(
            "Title\n🕒 02:14 (delayed 23 min)\nBody",
            MessageStamp.stamp("Title\nBody", at0214, at0214 + 23 * 60_000L + 10_000, utc),
        )
    }

    @Test
    fun `short delay is not mentioned`() {
        assertEquals("Title\n🕒 02:14", MessageStamp.stamp("Title", at0214, at0214 + 90_000, utc))
    }
}
