package com.pocketagent.ui.call

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.view.SurfaceHolder
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * **前置摄像头的一只眼睛** —— 视频通话时让角色看见你。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么用 `android.hardware.Camera`（已废弃）而不是 CameraX
 * ═══════════════════════════════════════════════════════════════
 *
 * CameraX 是更好的选择，但它要往构建里加四个 aar（camera-core /
 * camera-camera2 / camera-lifecycle / camera-view），而本机 Gradle 缓存里
 * 一个都没有 —— 也就是说这个功能一旦依赖它，**离线构建就不再成立**，
 * 谁 clone 下来都得先联网。这一版只需要"开前置、看画面、抓一张 JPEG"，
 * 老 API 三件事都能做，代价是过时（编译会有 deprecation 警告）。
 *
 * ⇒ 取舍写在这里：**先用老 API 把功能打通**；等要接实时视频编码
 *   （把连续帧喂给模型）时，再连依赖一起换成 CameraX。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三个必须做对的地方
 * ═══════════════════════════════════════════════════════════════
 *
 *  1. **`release()` 必须成对**。摄像头是独占资源，漏一次之后
 *     再开就 `Camera.open` 拿不到（要等系统回收，通常几秒）。
 *     这里用 [close] 统一收口，`open` 前先关旧的。
 *  2. **`takePicture` 要有预览在跑**。没有预览时回调永远不来，
 *     表现是"点了抓帧但什么都不发生"。
 *  3. **回调可能永远不来**（设备被别的应用占用）。所以抓帧带超时，
 *     超时就返回 null，让调用方走"看不见"那条路，而不是卡住。
 */
class CameraStill(private val context: Context) {

    private var camera: Camera? = null

    /** 当前是否已经开着（界面用来决定要不要画"正在看"）。 */
    val isOpen: Boolean get() = camera != null

    /**
     * 把预览贴到一个 `SurfaceHolder` 上（界面里的 `SurfaceView`）。
     *
     * ⚠️ 尺寸不在这里设：`Camera.Parameters` 的预览尺寸必须与 Surface
     *   的实际尺寸匹配，差太多会拉伸或直接黑屏。调用方拿到
     *   [previewSize] 之后自己调整 View 的比例。
     */
    fun attach(holder: SurfaceHolder) {
        val cam = ensureOpen() ?: return
        runCatching {
            cam.setPreviewDisplay(holder)
            cam.startPreview()
        }.onFailure {
            Timber.w(it, "摄像头预览挂载失败")
            close()
        }
    }

    /**
     * 不挂界面时也能抓帧（用一块离屏 `SurfaceTexture` 骗过"必须有预览"）。
     *
     * ★ 视频通话里其实用不到这条 —— 界面本身就挂着预览。它给"没开预览
     *   也要看一眼"的场景留着（例如用户在通话页把画面收起来了）。
     */
    fun attachOffscreen(): SurfaceTexture? {
        val cam = ensureOpen() ?: return null
        val texture = SurfaceTexture(0)
        return runCatching {
            cam.setPreviewTexture(texture)
            cam.startPreview()
            texture
        }.getOrElse {
            Timber.w(it, "离屏预览挂载失败")
            texture.release()
            null
        }
    }

    /** 当前摄像头的预览分辨率；没开就是 null。 */
    fun previewSize(): Pair<Int, Int>? {
        val cam = camera ?: return null
        val size = runCatching { cam.parameters.previewSize }.getOrNull() ?: return null
        return size.width to size.height
    }

    /**
     * 抓一张 JPEG。
     *
     * ⚠️ 回调式 API 包成 suspend，**并且带超时**：`takePicture` 的回调
     *   在设备被占用、预览没起来时可能永远不来，不设超时就是永久挂起。
     */
    suspend fun captureJpeg(quality: Int = 80): ByteArray? {
        val cam = ensureOpen() ?: return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            var answered = false

            fun finish(bytes: ByteArray?) {
                if (answered) return
                answered = true
                if (cont.isActive) cont.resumeWith(Result.success(bytes))
            }

            runCatching {
                cam.parameters = cam.parameters.apply {
                    jpegQuality = quality.coerceIn(30, 95)
                    // 抓帧只用 YUV/JPEG，不设的话部分机型给的是原始帧。
                    pictureFormat = ImageFormat.JPEG
                }
                cam.takePicture(null, null) { data, _ ->
                    val bytes = data?.let { raw ->
                        // `data` 的长度常常大于实际 JPEG（缓冲区是预分配的），
                        // 直接整段发出去会在末尾带一堆 0，模型侧可能解码失败。
                        trimJpeg(raw)
                    }
                    finish(bytes)
                }
            }.onFailure {
                Timber.w(it, "抓帧失败")
                finish(null)
            }

            cont.invokeOnCancellation { finish(null) }
        }
    }

    /** 关摄像头（可重复调）。 */
    fun close() {
        val cam = camera ?: return
        camera = null
        runCatching { cam.stopPreview() }.onFailure { Timber.d(it, "停预览失败") }
        runCatching { cam.release() }.onFailure { Timber.w(it, "释放摄像头失败") }
    }

    private fun ensureOpen(): Camera? {
        camera?.let { return it }
        val opened = runCatching {
            // ⚠️ 先找前置；找不到就退回后置 —— 有摄像头总比"看不见"好。
            val index = frontCameraIndex() ?: 0
            Camera.open(index)
        }.onFailure { Timber.w(it, "打开摄像头失败") }.getOrNull() ?: return null
        camera = opened
        // 预览格式必须是 NV21，否则部分机型 `setPreviewDisplay` 直接抛。
        runCatching {
            opened.parameters = opened.parameters.apply { previewFormat = ImageFormat.NV21 }
        }.onFailure { Timber.d(it, "设置预览格式失败（继续）") }
        return opened
    }

    private fun frontCameraIndex(): Int? {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i
        }
        return null
    }

    companion object {
        /**
         * 从 `takePicture` 的缓冲区里切出真正的 JPEG。
         *
         * 判据是 **EOI 标记 `FF D9`**：JPEG 到那里就结束了，后面是缓冲区
         * 的残留。不切的话字节数会虚高（常见 5–10 倍），而且末尾多出的 0
         * 会让某些服务端判定"图片损坏"。
         */
        fun trimJpeg(raw: ByteArray): ByteArray {
            if (raw.size < 4) return raw
            for (i in raw.size - 2 downTo 2) {
                if (raw[i] == 0xFF.toByte() && raw[i + 1] == 0xD9.toByte()) {
                    if (i + 2 == raw.size) return raw
                    return raw.copyOfRange(0, i + 2)
                }
            }
            return raw
        }
    }
}
