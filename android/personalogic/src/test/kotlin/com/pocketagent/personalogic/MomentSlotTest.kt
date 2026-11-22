package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **环境小事真的到了提示词里**。
 *
 * 2026-11-21 审计发现：`DirectorOutput.smallMoments` 从 `DirectorGate` 出来之后
 * `AppContainer` 一句都没用 —— 导演写了"窗外在下雨"，前台模型看不到。
 * 这个文件钉三件事：Slot 自己会不会过期、装配器会不会渲染、空值会不会乱写。
 */
class MomentSlotTest {

    private var now = 1_795_000_000_000L
    private val slot = MomentSlot { now }
    private val assembler = PersonaAssembler()

    @Test
    fun `收下之后就取得到`() {
        assertTrue(slot.offer(listOf("窗外在下雨")))
        assertEquals(listOf("窗外在下雨"), slot.currentLines())
    }

    @Test
    fun `空列表不会清掉旧的`() {
        slot.offer(listOf("粥在锅里咕嘟"))
        assertFalse("空不代表要擦掉", slot.offer(emptyList()))
        assertEquals(listOf("粥在锅里咕嘟"), slot.currentLines())
    }

    @Test
    fun `最多留两条，多的丢掉`() {
        slot.offer(listOf("一", "二", "三"))
        assertEquals(2, slot.currentLines().size)
    }

    @Test
    fun `过期之后就不再给`() {
        slot.offer(listOf("窗外在下雨"))
        now += MomentSlot.TTL_MS + 1
        assertNull(slot.current())
        assertTrue(slot.currentLines().isEmpty())
    }

    @Test
    fun `过期之后可以再收一批`() {
        slot.offer(listOf("窗外在下雨"))
        now += MomentSlot.TTL_MS + 1
        assertTrue(slot.offer(listOf("天晴了")))
        assertEquals(listOf("天晴了"), slot.currentLines())
    }

    @Test
    fun `装配器会把小事写成一段`() {
        val line = com.pocketagent.personalogic.PersonaAssembler.let {
            assembler.stateSection(
                state = PersonaState(physiology = Physiology(lastActiveAtMs = now)),
                moments = listOf("窗外在下雨", "粥在锅里咕嘟"),
            )
        }
        assertTrue("该有这一段，实际：$line", line.contains("## 这会儿周围"))
        assertTrue(line.contains("窗外在下雨"))
        assertTrue(line.contains("粥在锅里咕嘟"))
    }

    @Test
    fun `没有小事时状态中性就什么都不写`() {
        val line = assembler.stateSection(
            state = PersonaState(physiology = Physiology(lastActiveAtMs = now)),
        )
        assertEquals("", line)
    }

    @Test
    fun `小事不该冒充心情`() {
        val line = assembler.stateSection(
            state = PersonaState(physiology = Physiology(lastActiveAtMs = now)),
            moments = listOf("窗外在下雨"),
        )
        assertFalse("窗外下雨不是她的心情", line.contains("## 此刻的状态"))
    }
}
