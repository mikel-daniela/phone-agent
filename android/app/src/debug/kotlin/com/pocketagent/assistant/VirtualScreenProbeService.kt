package com.pocketagent.assistant

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * **虚拟屏验证服务** —— 在没有界面、不打扰用户主屏的前提下把整条链路跑一遍。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是 Service 而不是 Activity
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的要求：**验证本身也不能打扰主屏**。
 *
 * 用 Activity 做验证，等于先把我们的界面摆到用户眼前 —— 那本身就已经
 * 违反了"不打扰"。而 Service 可以把界面完全留空：
 * 用户看到的是他自己的桌面（或者他正在用的应用），一动不动。
 *
 * 要验证的四件事，逐条打日志：
 *
 * | # | 断言 | 判据 |
 * |---|---|---|
 * | 1 | 屏建起来了 | `createVirtualDisplay` 返回 id > 0 |
 * | 2 | 目标应用**真的在虚拟屏上** | `dumpsys activity activities` 里它的 Task 落在 `mDisplayId=<id>` 下 |
 * | 3 | 这块屏上**有画面** | `ImageReader` 取到帧，且非零像素采样 > 0 |
 * | 4 | **用户主屏没被影响** | `dumpsys window` 的 `mCurrentFocus` 不含目标应用、不含本应用 |
 *
 * 用法（不产生任何可见界面）：
 * ```
 * adb shell am startservice -n com.pocketagent.debug/.assistant.VirtualScreenProbeService \
 *     --es target com.tencent.mobileqq
 * ```
 */
class VirtualScreenProbeService : Service() {

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getStringExtra(EXTRA_TARGET) ?: DEFAULT_TARGET
        // ⚠️ 必须走前台：Android 8 之后禁止从后台 `am startservice` 起普通服务
        //    （真机报错：`Error: app is in background uid null`）。
        //    这一条通知是**不打扰屏幕**的代价 —— 它不弹窗、不抢焦点。
        goForeground()
        if (running) {
            Log.i(TAG, "已有一次验证在跑，忽略本次")
            return START_NOT_STICKY
        }
        running = true
        // ⚠️ 必须离开主线程：`su` + `am start` + 取帧都是阻塞的，
        //    在主线程上做会 ANR。
        val forceResizable = intent?.getStringExtra(EXTRA_MODE) == MODE_RESIZABLE
        if (intent?.getStringExtra(EXTRA_ACTION) == ACTION_SHOT) {
            Thread({ takeShotOfExisting() }, "virtual-screen-shot").start()
        } else {
            val settle = intent?.getIntExtra(EXTRA_WAIT, 12) ?: 12
        Thread({ runVerification(target, forceResizable, settle) }, "virtual-screen-probe").start()
        }
        return START_NOT_STICKY
    }

    /**
     * **只取一帧**，不新建屏。
     *
     * ★ 用途：屏幕共享/排查时把"那块屏上现在是什么样"拿到手。
     *   它刻意复用 `VirtualScreenHost.current` —— 新建一个实例会再建一块
     *   虚拟屏，而排查工具不该引入它要排查的那类问题。
     */
    private fun takeShotOfExisting() {
        try {
            val host = VirtualScreenHost.current
            if (host == null) {
                Log.e(TAG, "shot：当前没有虚拟屏（还没有人 start 过）")
                return
            }
            val path = host.saveSnapshot()
            if (path == null) {
                Log.e(TAG, "shot：取帧失败（这块屏上还没有画面）")
            } else {
                Log.i(TAG, "shot：已保存到 $path")
            }
        } finally {
            running = false
            stopSelf()
        }
    }

    private fun runVerification(
        target: String,
        forceResizable: Boolean = false,
        settleSeconds: Int = 12,
    ) {
        val host = VirtualScreenHost(applicationContext)
        try {
            // ① 建屏
            val id = host.start()
            if (id == null) {
                Log.e(TAG, "[1] 建屏失败 —— 整条链路不成立")
                return
            }
            Log.i(TAG, "[1] 建屏 OK displayId=$id")

            // 记下用户主屏此刻的焦点，最后对比
            val focusBefore = sh("dumpsys window | grep mCurrentFocus")
            Log.i(TAG, "主屏焦点（开始前）：$focusBefore")

            // ② 把应用拉进虚拟屏
            if (forceResizable) {
                // 实验：不可 resize 的应用（微信这类）会被系统踢回主屏。
                sh("settings put global enable_freeform_support 1")
                sh("settings put global force_resizable_activities 1")
                Log.i(TAG, "[实验] 已临时打开 freeform / 强制可 resize")
            }
            if (forceResizable) {
                // ★ 诊断模式：不调 host.launch，自己发一次 am start，
                //   把 stdout/stderr 原样打出来 —— "am start 成功但落在主屏"
                //   这件事只能靠输出与 dumpsys 对照才看得见。
                sh("am force-stop $target")
                Thread.sleep(700)
                val comp = sh("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $target | tail -1")
                Log.i(TAG, "[诊断] component=$comp")
                val out = sh("am start --display $id --windowingMode 1 -n ${comp.trim()}")
                Log.i(TAG, "[诊断] am start 输出：$out")
                Thread.sleep(6_000)
                val tasks = sh("dumpsys activity activities | grep -E 'Display: mDisplayId|$target' | head -20")
                Log.i(TAG, "[诊断] task 归属：\n$tasks")
            }
            val launchErr = if (forceResizable) null else host.launch(target)
            if (launchErr != null) {
                Log.e(TAG, "[2] 拉进虚拟屏失败：$launchErr")
                return
            }
            Log.i(TAG, "[2] 已请求把 $target 拉进 display=$id")

            // 给冷启动留时间（QQ 闪屏就 3 秒起）
            Thread.sleep(settleSeconds * 1_000L)

            // ②' 用 dumpsys 证明它真的落在那块屏上
            val onDisplay = sh(
                "dumpsys activity activities | grep -B3 'A=[0-9]*:$target' | grep -E 'Display: mDisplayId=' | head -2",
            )
            val landed = onDisplay.contains("mDisplayId=$id")
            Log.i(TAG, "[2'] 该应用的 Display 归属 = ${onDisplay.ifBlank { "（没读到）" }}")
            Log.i(TAG, "[2'] 落在虚拟屏上 = $landed")

            // ③ 取帧
            val png = host.snapshotPng()
            if (png == null) {
                Log.e(TAG, "[3] 取帧失败 —— 这块屏上没有画面")
            } else {
                Log.i(TAG, "[3] 取帧 OK：${png.size} 字节 PNG（有画面）")
                val saved = host.saveSnapshot()
                Log.i(TAG, "[3'] 这一帧已落盘：$saved")
            }

            // ④ 用户主屏有没有被打扰
            val focusAfter = sh("dumpsys window | grep mCurrentFocus")
            Log.i(TAG, "主屏焦点（结束后）：$focusAfter")
            val disturbed = focusAfter.contains(target) || focusAfter.contains(ME_PACKAGE)
            Log.i(
                TAG,
                if (disturbed) {
                    "[4] ✗ 主屏被打扰了 —— 焦点跑到目标应用或本应用上了"
                } else {
                    "[4] ✓ 主屏未被打扰（焦点仍是用户自己的应用）"
                },
            )
            Log.i(TAG, "焦点是否变化 = ${focusBefore.trim() != focusAfter.trim()}")
        } catch (t: Throwable) {
            Log.e(TAG, "验证过程抛异常", t)
        } finally {
            // ⚠️ 收工：先清应用，再释放屏。顺序反了会把应用挪到用户主屏上。
            runCatching { host.stop() }
            Log.i(TAG, "════ 验证结束（已释放虚拟屏）════")
            running = false
            stopSelf()
        }
    }

    private fun sh(cmd: String): String = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            "（超时）"
        } else {
            p.inputStream.bufferedReader().readText().trim()
        }
    }.getOrElse { "（失败：${it.message}）" }

    /**
     * 升成前台服务。
     *
     * ⚠️ 这一条通知是"验证也不打扰屏幕"的代价：它不弹窗、不抢焦点、
     *    不出现在用户正在用的应用之上。比摆一个 Activity 上去好得多。
     */
    private fun goForeground() {
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_ID,
                        "虚拟屏验证",
                        android.app.NotificationManager.IMPORTANCE_MIN,
                    ),
                )
            }
            val n = android.app.Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("虚拟屏验证")
                .setContentText("正在后台验证虚拟屏，不会影响你正在用的界面")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .build()
            startForeground(NOTIF_ID, n)
        }.onFailure { Log.w(TAG, "升前台失败：${it.message}") }
    }

    companion object {
        private const val TAG = "VirtualScreenProbe"
        private const val CHANNEL_ID = "virtual_screen_probe"
        private const val NOTIF_ID = 0x7611
        const val EXTRA_TARGET = "target"
        const val EXTRA_ACTION = "action"

        /** 只取一帧当前画面（不新建屏、不启动应用）。 */
        const val ACTION_SHOT = "shot"
    const val EXTRA_MODE = "mode"
    const val EXTRA_WAIT = "wait"

    /** 实验：临时打开 freeform / 强制可 resize，看不可 resize 的应用能不能落进虚拟屏。 */
    const val MODE_RESIZABLE = "resizable"
        private const val DEFAULT_TARGET = "com.tencent.mobileqq"
        private const val ME_PACKAGE = "com.pocketagent"
    }
}
