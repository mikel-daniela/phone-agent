package com.pocketagent.assistant.display

import android.graphics.Bitmap
import android.media.ImageReader
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 以 uid 0 运行的虚拟屏宿主进程的入口。
 *
 * 为什么需要它：可信虚拟屏（`FLAG_TRUSTED`）只允许 uid 0 / 系统进程创建。应用自己
 * 调 `DisplayManager.createVirtualDisplay` 带可信位会被 `DisplayManagerService`
 * 直接拒绝（RemoteException），不带可信位建出来的屏第三方应用拉不进去 —— 微信会
 * 被系统扔回主屏。所以"看不见的屏"这件事只能由一个 uid 0 的进程来做。
 *
 * 它由应用侧用 `su` 拉起：
 * ```
 * CLASSPATH=<base.apk> app_process /system/bin com.pocketagent.assistant.display.RootDisplayHelper
 * ```
 * 用应用自己的 APK 当 classpath，不额外产出 dex；进程里没有任何 Context，所以
 * 隐藏 API（IDisplayManager / VirtualDisplayConfig）全部走反射。
 *
 * 与应用之间用 abstract 命名空间本地 socket 通信，一行一条命令，二进制帧单独回。
 */
object RootDisplayHelper {

    /** 应用侧连这个名字。abstract 命名空间（不以 / 开头）不需要文件权限。 */
    const val SOCKET_NAME = "pocketagent.display.helper"

    /** `DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED`（隐藏常量，= 1 shl 10）。 */
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_PUBLIC = 1 shl 0
    /**
 * ⚠️ 建"把别的应用搬进去"的屏时**不能**带这个。
 *
 * 它的语义是"这块屏只显示创建者自己的内容"（同 UID），于是微信这种第三方应用
 * 明明把任务挂在这块屏上、窗口也在，画面却一帧都不会画上来 —— 取到的图永远是
 * 纯白，日志里却一切正常。真机踩过一次，别再戴回去。
 */
@Suppress("unused")
private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3

    private var reader: ImageReader? = null
    private var displayToken: Any? = null
    private var displayId: Int = -1

    /**
     * 最新一帧。
     *
     * ⚠️ 必须有一个线程**持续**把 ImageReader 里的帧取走：SurfaceFlinger 那边
     *    只认缓冲区数量（这里 3 个），攒满之后就不再出新帧 —— 表现出来的症状是
     *    "应用明明在屏上跑着，取帧却永远是这张空白"。
     */
    @Volatile
    private var lastFrame: Bitmap? = null

    @Volatile
    private var lastFrameAt = 0L

    @Volatile
    private var draining = false

    @JvmStatic
    fun main(args: Array<String>) {
        log("启动：uid=" + android.os.Process.myUid())
        val server = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (t: Throwable) {
            log("已有实例在跑（$t），退出")
            return
        }
        log("监听 $SOCKET_NAME")
        while (true) {
            val client = try {
                server.accept()
            } catch (t: Throwable) {
                log("accept 失败：$t")
                break
            }
            try {
                serve(client)
            } catch (t: Throwable) {
                log("会话异常：$t")
            } finally {
                runCatching { client.close() }
            }
            if (displayId < 0 && args.firstOrNull() == "oneshot") break
        }
        release()
        runCatching { server.close() }
        log("退出")
    }

    private fun serve(client: LocalSocket) {
        val input = BufferedReader(InputStreamReader(client.inputStream, Charsets.UTF_8))
        val out = client.outputStream
        while (true) {
            val line = input.readLine() ?: return
            val parts = line.trim().split(' ')
            val cmd = parts.firstOrNull()?.uppercase() ?: continue
            when (cmd) {
                "PING" -> reply(out, "OK pong")
                "START" -> {
                    val w = parts.getOrNull(1)?.toIntOrNull() ?: 720
                    val h = parts.getOrNull(2)?.toIntOrNull() ?: 1280
                    val dpi = parts.getOrNull(3)?.toIntOrNull() ?: 320
                    val err = start(w, h, dpi)
                    reply(out, err ?: "OK $displayId")
                }
                "LAUNCH" -> {
                    val id = parts.getOrNull(1)?.toIntOrNull() ?: displayId
                    val component = parts.drop(2).joinToString(" ")
                    reply(out, shell("am", "start", "--display", id.toString(),
                        "--windowingMode", "1", "-n", component))
                }
                "STOP" -> {
                    val pkg = parts.getOrNull(1) ?: ""
                    reply(out, shell("am", "force-stop", pkg))
                }
                "SHOT" -> {
                    val png = shot()
                    if (png == null) {
                        reply(out, "NONE")
                    } else {
                        out.write("PNG ".toByteArray()); out.write(png.size.toString().toByteArray())
                        out.write('\n'.code); out.write(png); out.flush()
                    }
                }
                "INPUT" -> {
                    val id = parts.getOrNull(1) ?: displayId.toString()
                    val rest = parts.drop(2)
                    reply(out, shell("input", "-d", id, *rest.toTypedArray()))
                }
                "DESTROY" -> {
                    release()
                    reply(out, "OK")
                }
                "QUIT" -> {
                    reply(out, "OK")
                    return
                }
                else -> reply(out, "ERR 未知命令")
            }
        }
    }

    private fun reply(out: java.io.OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.write('\n'.code)
        out.flush()
    }

    private var frames = 0

    private fun log(msg: String) {
        // ⚠️ 辅助进程的 stdout/stderr 被重定向到 /dev/null，排查只能走 logcat。
        runCatching { android.util.Log.i("RootDisplayHelper", msg) }
        System.err.println("RootDisplayHelper: $msg")
    }

    /** 建屏。成功返回 null。 */
    private fun start(width: Int, height: Int, density: Int): String? {
        if (reader != null) return null
        return try {
            val irClass = Class.forName("android.media.ImageReader")
            val ir = irClass.getMethod("newInstance", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(null, width, height, 1, 3)
            val surface = irClass.getMethod("getSurface").invoke(ir)

            val cfgBuilder = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val builder = cfgBuilder.getConstructor(String::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance("pocketagent-root", width, height, density)
            cfgBuilder.getMethod("setFlags", Int::class.javaPrimitiveType)
                .invoke(builder, FLAG_PUBLIC or FLAG_TRUSTED)
            cfgBuilder.getMethod("setSurface", Class.forName("android.view.Surface"))
                .invoke(builder, surface)
            val config = cfgBuilder.getMethod("build").invoke(builder)

            val cbClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            val callback = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(cbClass),
                { _, method: Method, _ -> if (method.name == "asBinder") Binder() else null })

            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "display") as IBinder
            val dm = Class.forName("android.hardware.display.IDisplayManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!

            val create = dm.javaClass.methods.first { it.name == "createVirtualDisplay" }
            val id = when (create.parameterCount) {
                4 -> create.invoke(dm, config, callback, null, "com.pocketagent.debug")
                else -> create.invoke(dm, config, callback, null, "com.pocketagent.debug", null)
            } as Int

            reader = ir as ImageReader
            displayId = id
            startDraining(ir as ImageReader)
            log("建屏成功 displayId=$id ${width}x$height@$density")
            null
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            log("建屏失败：$cause")
            "ERR " + cause.javaClass.simpleName + ": " + (cause.message ?: "没有更多信息")
        }
    }

    /** 后台收帧：只留最新一张，90ms 的节流够"看一眼界面"。 */
    private fun startDraining(r: ImageReader) {
        draining = true
        val thread = Thread {
            while (draining && reader === r) {
                val image = try {
                    r.acquireLatestImage()
                } catch (t: Throwable) {
                    null
                }
                if (image == null) {
                    Thread.sleep(60)
                    continue
                }
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameAt >= 90) {
                        val bitmap = image.toBitmap()
                        frames++
                        if (frames % 10 == 1) log("收帧 #$frames stride=${image.planes[0].rowStride} ok=${bitmap != null}")
                        if (bitmap != null) {
                            val old = lastFrame
                            lastFrame = bitmap
                            lastFrameAt = now
                            old?.recycle()
                        }
                    }
                } finally {
                    runCatching { image.close() }
                }
            }
        }
        thread.isDaemon = true
        thread.name = "root-display-frames"
        thread.start()
    }

    /** RGBA_8888 的 Image → Bitmap（rowStride 可能大于 width×4，先按 stride 建再裁）。 */
    private fun android.media.Image.toBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val strideWidth = plane.rowStride / plane.pixelStride
        if (strideWidth <= 0) return null
        return try {
            val wide = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(plane.buffer)
            if (strideWidth == width) wide
            else Bitmap.createBitmap(wide, 0, 0, width, height).also { wide.recycle() }
        } catch (t: Throwable) {
            log("转 Bitmap 失败：$t")
            null
        }
    }

    private fun release() {
        draining = false
        runCatching {
            val r = reader
            if (r != null) {
                runCatching { r.setOnImageAvailableListener(null, null) }
                runCatching { r.close() }
            }
        }
        reader = null
        displayId = -1
        displayToken = null
        log("已释放")
    }

    /** 取当前最新一帧，压成 PNG。 */
    private fun shot(): ByteArray? {
        if (reader == null) return null
        // 冷启动时第一帧可能还没到，给一点余地（与应有屏侧同样的理由：
        // "还没出帧"和"没有画面"是两件事）。
        var frame = lastFrame
        var waited = 0
        while (frame == null && waited < 12) {
            Thread.sleep(120)
            frame = lastFrame
            waited++
        }
        val bitmap = frame ?: return null
        return try {
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            bytes.toByteArray()
        } catch (t: Throwable) {
            log("编 PNG 失败：$t")
            null
        }
    }

    private fun shell(vararg argv: String): String {
        return try {
            val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            if (code == 0) "OK" else "ERR 退出码 $code：${text.take(200)}"
        } catch (t: Throwable) {
            "ERR 执行失败：$t"
        }
    }
}
