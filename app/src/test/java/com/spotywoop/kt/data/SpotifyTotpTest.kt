package com.spotywoop.kt.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyTotpTest {
    @Test
    fun base32DecodesRfc4648Vector() {
        assertEquals("foobar", String(SpotifyTotp.base32Decode("MZXW6YTBOI======")))
    }

    @Test
    fun codeIsSixDigitsAndStableWithinWindow() {
        val t = 1_700_000_010_000L
        val code = SpotifyTotp.generate(t)
        assertEquals(6, code.length)
        assertEquals(code, SpotifyTotp.generate(t + 5_000))
    }
}
