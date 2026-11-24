package com.pocketagent.assistant.display

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream

/**
 * 应用侧到 root 辅助进程的客户端。
 *
 * 人手不足的地方：这块屏必须由 uid 0 建（`FLAG_TRUSTED` 只有 root 拿得到），
 * 所以真正的宿主是 [RootDisplayHelper]，跑在 `app_process` 里。本类只负责
 * "拉起来 + 说话"：一条命令一行，帧是二进制块。
 *
 * 设计上的取舍：
 * - 每次命令都新建一条连接（本地 socket 建链很便宜），避免长期持有的连接在
 *   辅助进程被系统杀掉后变成半死状态；
 * - 辅助进程用应用自己的 APK 当 classpath，所以 [apkPath] 必须指向已安装的
 *   base.apk（不是内存里的 dex）；
 * - 拉起用 `&` 放到后台，`su` 立刻返回，不占调用方的线程。
 */
class RootDisplayClient(private val apkPath: String) {

    /** 拉起辅助进程。已经有实例在跑时它会自己退出，所以重复调用是安全的。 */
    fun ensureRunning(): Boolean {
        if (ping()) return true
        runCatching {
            ProcessBuilder(
                "su", "-c",
                "CLASSPATH=" + apkPath + " app_process /system/bin " + ENTRY + " >/dev/null 2>&1 &",
            ).start().waitFor()
        }
        repeat(12) {
            Thread.sleep(120)
            if (ping()) return true
        }
        return false
    }

    fun ping(): Boolean = runCatching {
        open().use { socket ->
            writeLine(socket, "PING")
            readLineRaw(socket.inputStream)?.startsWith("OK") == true
        }
    }.getOrDefault(false)

    /** 建屏，返回 displayId；失败返回原因。 */
    fun start(width: Int, height: Int, density: Int): Result<Int> = runCatching {
        open().use { socket ->
            writeLine(socket, "START $width $height $density")
            val answer = readLineRaw(socket.inputStream) ?: error("辅助进程没有回话")
            if (answer.startsWith("OK ")) answer.removePrefix("OK ").trim().toInt()
            else error(answer.removePrefix("ERR "))
        }
    }

    fun launch(displayId: Int, component: String): String? = command("LAUNCH $displayId $component")

    fun stopPackage(packageName: String): String? = command("STOP $packageName")

    fun input(displayId: Int, vararg args: Any): String? =
        command("INPUT $displayId " + args.joinToString(" "))

    /** 取一帧 PNG；没有画面时返回 null。 */
    fun shot(): ByteArray? = runCatching {
        open().use { socket ->
            writeLine(socket, "SHOT")
            val head = readLineRaw(socket.inputStream) ?: return null
            if (!head.startsWith("PNG ")) return null
            val size = head.removePrefix("PNG ").trim().toInt()
            val bytes = ByteArray(size)
            var read = 0
            val input = socket.inputStream
            while (read < size) {
                val n = input.read(bytes, read, size - read)
                if (n <= 0) return null
                read += n
            }
            bytes
        }
    }.getOrNull()

    fun destroy() {
        command("DESTROY", quiet = true)
    }

    private fun command(line: String, quiet: Boolean = false): String? = runCatching {
        open().use { socket ->
            writeLine(socket, line)
            val answer = readLineRaw(socket.inputStream) ?: return null
            if (!quiet && answer.startsWith("ERR")) answer.removePrefix("ERR ").trim() else null
        }
    }.getOrNull()

    private fun open(): LocalSocket {
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
        socket.soTimeout = TIMEOUT_MS
        return socket
    }

    private fun writeLine(socket: LocalSocket, line: String) {
        socket.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
    }

    /**
     * 逐字节读到换行为止。
     *
     * ⚠️ 不能用 `BufferedReader.readLine()`：它会一次读进好几 KB，把紧随换行之后的
     * **二进制帧数据**一起吞进它自己的缓冲区，调用方再从 socket 读剩下的字节就少了
     * 一截（表现是取帧永远失败，明明辅助进程那边已经收到帧了）。
     */
    private fun readLineRaw(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) return out.toString()
            if (b != '\r'.code) out.append(b.toChar())
        }
    }

    companion object {
        const val SOCKET_NAME = "pocketagent.display.helper"
        const val ENTRY = "com.pocketagent.assistant.display.RootDisplayHelper"
        private const val TIMEOUT_MS = 8_000
    }
}
