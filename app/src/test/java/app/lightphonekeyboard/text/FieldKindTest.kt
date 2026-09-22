package app.lightphonekeyboard.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldKindTest {
    // android.text.InputType values, as the platform defines them.
    private val text = 0x1
    private val capSentences = 0x4000
    private val autoCorrect = 0x8000
    private val uri = 0x10
    private val email = 0x20
    private val webEmail = 0xd0
    private val password = 0x80
    private val visiblePassword = 0x90
    private val webPassword = 0xe0
    private val filter = 0xb0
    private val noSuggestions = 0x80000
    private val number = 0x2
    private val phone = 0x3

    @Test
    fun `prose fields are corrected`() {
        assertTrue(FieldKind.correctable(text))
        assertTrue(FieldKind.correctable(text or capSentences or autoCorrect))
        assertTrue(FieldKind.correctable(0))   // an app that declared nothing
    }

    @Test
    fun `an address bar is not prose`() {
        // KeyboardType.Uri, which is what WebTools' address field declares.
        assertFalse(FieldKind.correctable(text or uri))
        assertFalse(FieldKind.correctable(text or email))
        assertFalse(FieldKind.correctable(text or webEmail))
    }

    @Test
    fun `secrets and filters are left alone`() {
        assertFalse(FieldKind.correctable(text or password))
        assertFalse(FieldKind.correctable(text or visiblePassword))
        assertFalse(FieldKind.correctable(text or webPassword))
        assertFalse(FieldKind.correctable(text or filter))
    }

    @Test
    fun `an explicit no-suggestions flag wins over prose`() {
        assertFalse(FieldKind.correctable(text or capSentences or noSuggestions))
    }

    @Test
    fun `non-text classes are never corrected`() {
        assertFalse(FieldKind.correctable(number))
        assertFalse(FieldKind.correctable(phone))
    }
}
