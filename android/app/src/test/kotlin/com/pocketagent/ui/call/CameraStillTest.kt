package com.pocketagent.ui.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ══════════════════════════════════════════════════════════════
    //  预览方向与比例
    // ══════════════════════════════════════════════════════════════
    //
    // ★ 这几条防的是什么
    //
    // 真机上第一次进视频通话，画面是**横躺着**的（老 Camera API 不知道手机
    // 是竖的，不调 setDisplayOrientation 就歪 90°）；补了旋转之后窗口又是横
    // 的，人脸被横向压扁 —— 因为旋转只改画面内容、不改 Surface 比例。
    //
    // 这两个公式以前直接读 CameraInfo，只能在手机上看；现在抽成纯函数，
    // 下面把常见机型的两组传感器角度都钉住。

    @Test
    fun `竖屏前置摄像头转 90 度`() {
        // 常见前置：sensor=270。竖屏（0）→ 镜像后是 90。
        assertEquals(90, CameraStill.displayOrientation(270, frontFacing = true, screenDegrees = 0))
    }

    @Test
    fun `竖屏后置摄像头转 90 度`() {
        assertEquals(90, CameraStill.displayOrientation(90, frontFacing = false, screenDegrees = 0))
    }

    @Test
    fun `横屏时后置不需要旋转`() {
        assertEquals(0, CameraStill.displayOrientation(90, frontFacing = false, screenDegrees = 90))
    }

    @Test
    fun `倒过来拿的后置是 180 度`() {
        assertEquals(180, CameraStill.displayOrientation(90, frontFacing = false, screenDegrees = 270))
    }

    @Test
    fun `角度永远落在 0 到 359 之间`() {
        val angles = listOf(0, 90, 180, 270)
        for (sensor in angles) {
            for (screen in angles) {
                for (front in listOf(true, false)) {
                    val got = CameraStill.displayOrientation(sensor, front, screen)
                    assertTrue("传感器 $sensor 屏幕 $screen 前置 $front 算出 $got", got in 0..359)
                }
            }
        }
    }

    @Test
    fun `转过 90 度之后比例要倒过来`() {
        // 640x480 横向预览，转 90° 后占位应该是 3:4（竖的）。
        val aspect = CameraStill.aspectOf(640, 480, displayOrientation = 90)
        assertEquals(0.75f, aspect!!, 0.0001f)
    }

    @Test
    fun `没转的时候比例是正的`() {
        val aspect = CameraStill.aspectOf(640, 480, displayOrientation = 0)
        assertEquals(4f / 3f, aspect!!, 0.0001f)
    }

    @Test
    fun `转 180 度比例不变`() {
        assertEquals(
            CameraStill.aspectOf(640, 480, displayOrientation = 0)!!,
            CameraStill.aspectOf(640, 480, displayOrientation = 180)!!,
            0.0001f,
        )
    }

    @Test
    fun `尺寸非法时不给比例`() {
        assertEquals(null, CameraStill.aspectOf(0, 480, displayOrientation = 90))
        assertEquals(null, CameraStill.aspectOf(640, -1, displayOrientation = 90))
    }
}
