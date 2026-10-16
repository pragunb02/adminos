package dev.adminos.api.domain.ingestion.sms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SenderIdTest {
    @Test
    fun `parses TRAI headers with any operator prefix`() {
        assertEquals(SenderId("HDFCBK", 'S'), SenderId.parse("AX-HDFCBK-S"))
        assertEquals(SenderId("HDFCBK", null), SenderId.parse("VM-HDFCBK"))
        assertEquals(SenderId("SBIUPI", 'T'), SenderId.parse("ad-sbiupi-t"))
        assertEquals(SenderId("HDFCBK", null), SenderId.parse("HDFCBK"))
    }

    @Test
    fun `personal numbers and garbage are not business senders`() {
        assertNull(SenderId.parse("+919876543210"))
        assertNull(SenderId.parse("98765 43210"))
        assertNull(SenderId.parse("hello world"))
    }
}
