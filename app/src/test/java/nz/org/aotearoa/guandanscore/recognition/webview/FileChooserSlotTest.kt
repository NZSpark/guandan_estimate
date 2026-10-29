package nz.org.aotearoa.guandanscore.recognition.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-073：文件选择回调唯一所有者的释放语义。 */
class FileChooserSlotTest {

    private class Cb(val id: String)

    private val released = mutableListOf<String>()

    private fun slot() = FileChooserSlot<Cb>()

    @Test
    fun `offer then take transfers ownership`() {
        val s = slot()
        val a = Cb("a")
        assertFalse(s.offer(a) { released += it.id })
        assertTrue(s.isPending)
        assertSame(a, s.take())
        assertFalse(s.isPending)
        assertTrue(released.isEmpty())
    }

    @Test
    fun `offer replaces and releases previous`() {
        val s = slot()
        val a = Cb("a")
        val b = Cb("b")
        s.offer(a) { released += it.id }
        assertTrue(s.offer(b) { released += it.id })
        assertEquals(listOf("a"), released)
        assertSame(b, s.peek())
    }

    @Test
    fun `repeated offer of same instance does not release`() {
        val s = slot()
        val a = Cb("a")
        s.offer(a) { released += it.id }
        assertFalse(s.offer(a) { released += it.id })
        assertTrue(released.isEmpty())
        assertSame(a, s.peek())
    }

    @Test
    fun `offer null is ignored`() {
        val s = slot()
        val a = Cb("a")
        s.offer(a) { released += it.id }
        assertFalse(s.offer(null) { released += it.id })
        assertSame(a, s.peek())
    }

    @Test
    fun `release is idempotent`() {
        val s = slot()
        s.offer(Cb("a")) { released += it.id }
        assertTrue(s.release { released += it.id })
        assertFalse(s.release { released += it.id })
        assertEquals(listOf("a"), released)
        assertNull(s.take())
    }
}
