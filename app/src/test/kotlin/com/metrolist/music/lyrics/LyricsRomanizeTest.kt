/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsRomanizeTest {
    private val all = listOf(
        "Japanese", "Korean", "Chinese", "Hindi", "Ukrainian",
        "Russian", "Serbian", "Bulgarian", "Belarusian", "Kyrgyz", "Macedonian",
    )

    @Test
    fun `kana text resolves to japanese`() {
        assertEquals("Japanese", LyricsUtils.detectLanguage("こんにちは世界", all))
    }

    @Test
    fun `han text without kana resolves to chinese rather than japanese`() {
        assertEquals("Chinese", LyricsUtils.detectLanguage("世界你好", all))
    }

    @Test
    fun `hangul text resolves to korean`() {
        assertEquals("Korean", LyricsUtils.detectLanguage("안녕하세요", all))
    }

    @Test
    fun `russian cyrillic resolves to russian`() {
        assertEquals("Russian", LyricsUtils.detectLanguage("Сыр эхо", all))
    }

    @Test
    fun `cyrillic shared with ukrainian still takes the ukrainian branch first`() {
        assertEquals("Ukrainian", LyricsUtils.detectLanguage("Привет мир", all))
    }

    @Test
    fun `latin text resolves to nothing`() {
        assertNull(LyricsUtils.detectLanguage("hello world", all))
    }

    @Test
    fun `empty text resolves to nothing`() {
        assertNull(LyricsUtils.detectLanguage("", all))
    }

    @Test
    fun `a language the user disabled is never detected`() {
        assertNull(LyricsUtils.detectLanguage("こんにちは世界", listOf("Korean")))
        assertNull(LyricsUtils.detectLanguage("世界你好", emptyList()))
    }

    @Test
    fun `no resolved language means no romanization`() = runBlocking {
        assertNull(LyricsUtils.romanizeLine("hello world", null))
    }

    @Test
    fun `a resolved language romanizes the line`() = runBlocking {
        val out = LyricsUtils.romanizeLine("世界", "Chinese")
        assertEquals("shi jie", out?.trim()?.replace(Regex("\\s+"), " "))
    }
}