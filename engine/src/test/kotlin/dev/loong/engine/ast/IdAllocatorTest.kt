package dev.loong.engine.ast

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdAllocatorTest {

    @Test
    fun `allocates monotonically from 1`() {
        val alloc = IdAllocator()
        assertEquals(NodeId(1), alloc.allocate())
        assertEquals(NodeId(2), alloc.allocate())
        assertEquals(NodeId(3), alloc.allocate())
        assertEquals(3, alloc.watermark)
    }

    @Test
    fun `restores from persisted watermark and never reuses`() {
        val first = IdAllocator()
        repeat(10) { first.allocate() }

        // 模拟落盘后恢复：以旧水位重建
        val restored = IdAllocator(first.watermark)
        val next = restored.allocate()
        assertTrue(next.value > first.watermark, "restored allocator must continue past watermark")
        assertEquals(first.watermark + 1, next.value)
    }

    @Test
    fun `rejects negative watermark`() {
        assertThrows<IllegalArgumentException> { IdAllocator(-1) }
    }
}
