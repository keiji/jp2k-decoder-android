package dev.keiji.jp2k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExceptionTest {

    @Test
    fun testRegionOutOfBoundsException() {
        val exception = RegionOutOfBoundsException("Message")
        assertEquals("Message", exception.message)

        val exceptionNull = RegionOutOfBoundsException()
        assertNull(exceptionNull.message)
    }

    @Test
    fun testJp2kError() {
        assertEquals(Jp2kError.None, Jp2kError.fromInt(0))
        assertEquals(Jp2kError.Header, Jp2kError.fromInt(-1))
        assertEquals(Jp2kError.Unknown, Jp2kError.fromInt(-999))
    }

    @Test
    fun testJp2kException() {
        val exception = Jp2kException(Jp2kError.Header)
        assertEquals("Error code: -1", exception.message)
        assertEquals(Jp2kError.Header, exception.error)

        val exceptionWithMsg = Jp2kException(Jp2kError.Decode, "Custom Message")
        assertEquals("Custom Message", exceptionWithMsg.message)
        assertEquals(Jp2kError.Decode, exceptionWithMsg.error)
    }
}
