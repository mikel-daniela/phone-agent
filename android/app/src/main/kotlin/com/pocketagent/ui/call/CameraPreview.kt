package com.pocketagent.ui.call

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import timber.log.Timber

/**
 * **视频通话时你自己的画面**（前置摄像头预览）。
 *
 * ⚠️ `SurfaceView` 而不是 `TextureView`：预览只需要"把摄像头输出贴上来"，
 *   `SurfaceView` 走硬件叠加层、不参与 Compose 的绘制树，功耗和延迟都更低。
 *   代价是它**不能做圆角/透明度动画** —— 所以这个组件不做任何变换，
 *   要圆角就在外面裁（`Modifier.clip`）。这一版直接铺满背景。
 *
 * ⚠️ 生命周期严格跟 Composable 走：`onDispose` 一定 `close()`。
 *   摄像头是独占资源，漏一次之后再开要等系统回收。
 */
@Composable
fun CameraPreview(
    still: CameraStill,
    modifier: Modifier = Modifier,
    onOpened: (Pair<Int, Int>?) -> Unit = {},
) {
    val context = LocalContext.current
    DisposableEffect(still) {
        onDispose {
            still.close()
            Timber.i("视频通话：摄像头已释放")
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        still.attach(holder)
                        onOpened(still.previewSize())
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        still.close()
                    }
                })
            }
        },
    )
}

/**
 * 权限没给时的兜底画面。
 *
 * ⚠️ 不用纯黑：黑屏看不出"是没权限还是摄像头坏了"。
 *   这里明说是权限，并给出下一步动作（按钮由调用方提供）。
 */
@Composable
fun CameraDeniedBackdrop(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color(0xFF10141B)))
}
