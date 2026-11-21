package com.pocketagent.ui.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `takePicture` 的缓冲区裁剪。
 *
 * ══════════════════════════════════════════════════════════════
 *  ★ 这个测试防的是哪一种 bug
 * ══════════════════════════════════════════════════════════════
 *
 * `Camera.takePicture` 回给你的 `ByteArray` 是**预分配的缓冲区**，
 * 里面 JPEG 结束后还剩一堆 0。原样发给模型侧，字节数会虚高 5–10 倍，
 * 而且有些服务端会以"图片损坏"拒掉 —— 排查起来会以为是编码问题。
 *
 * 判据只认 JPEG 的结束标记 `FF D9`。
 */
class CameraStillTest {

    @Test
    fun `缓冲区尾部的一串零会被切掉`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())
        val raw = jpeg + ByteArray(64)  // 预分配剩下的零
        assertArrayEquals(jpeg, CameraStill.trimJpeg(raw))
    }

    @Test
    fun `正好以结束标记收尾时原样返回`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x0A, 0xFF.toByte(), 0xD9.toByte())
        assertArrayEquals(jpeg, CameraStill.trimJpeg(jpeg))
    }

    @Test
    fun `找不到结束标记就原样返回，不猜`() {
        val garbage = ByteArray(32) { 0x11 }
        assertEquals(garbage.size, CameraStill.trimJpeg(garbage).size)
    }

    @Test
    fun `太短的输入直接返回`() {
        val tiny = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertEquals(2, CameraStill.trimJpeg(tiny).size)
    }

    @Test
    fun `中间还有别的 FF D9 时切在最后一个`() {
        // 真实 JPEG 内部可能有 FF D9 片段（重启标记），只有**最后**那个是结尾。
        val data = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),  // SOI
            0xFF.toByte(), 0xD9.toByte(),  // 内部（异常但暴力构造）
            0x42,
            0xFF.toByte(), 0xD9.toByte(),  // 真正的结尾
            0, 0, 0,
        )
        assertEquals(7, CameraStill.trimJpeg(data).size)
    }
}
