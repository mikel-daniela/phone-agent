package com.pocketagent.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.VoiceCall
import com.pocketagent.data.AppContainer
import com.pocketagent.modelrouter.EffortScene
import com.pocketagent.personalogic.ChatMode
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.ui.design.PaMotion
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.tasks.ChatResult
import com.pocketagent.ui.tasks.ContainerChatPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import timber.log.Timber

/**
 * **通话页宿主** —— 状态机、录音、模型、TTS 在这里编在一起。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这一层的职责边界
 * ═══════════════════════════════════════════════════════════════
 *
 * · `agentui/VoiceCall`  —— **状态**（含打断的原子转换，有 30 条测试）
 * · `CallScreen`          —— **画**（三种不同形状的动效）
 * · 本文件                —— **编排**（谁在什么时候调录音 / 请求 / TTS）
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-21：接上模型
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前这一页**只说不动脑**：`LaunchedEffect(phase)` 里只调了
 * `speakAsync`，没有录音、没有识别、没有模型请求 —— 也就是它念的
 * 永远是既有的那串文本。用户原话（2026-11-21）：
 *
 * > "打电话的时候为了提高响应速度 …… 同时也要接好语音模型"
 *
 * 现在的循环是一条直线，每一格都对应状态机的一个相位：
 *
 * ```
 *  LISTENING  录音 → 识别出文字
 *      ↓      （没听清就留在这一格继续听）
 *  THINKING   把文字给模型（思考档固定走 EffortScene.CALL = 最低）
 *      ↓
 *  SPEAKING   合成并播报，**等它念完**才回上一格
 * ```
 *
 * ⚠️ 三个必须做对的地方（都不是"能不能跑"的问题）：
 *
 *  1. **说话时必须关麦**。否则它听见自己的声音（回声）——
 *     `VoiceCall.shouldMicBeOpen` 是唯一判据，这里不另存布尔。
 *  2. **播报要等完**。用 [AppContainer.speakBlocking] 而不是
 *     `speakAsync`：后者 fire-and-forget，会在它还在说话时就把
 *     相位推回 LISTENING，于是麦克风立刻打开。
 *  3. **离开页面必须挂断**。只 `popBackStack` 不 stop 的话，
 *     声音会继续播（用户以为挂了，其实还在说）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 已知边界（如实说明，不写成"实时通话"）
 * ═══════════════════════════════════════════════════════════════
 *
 * 识别是**按段**的（系统 `SpeechRecognizer`，见 `AndroidAsrPort`），
 * 不是流式边说边出字；所以体验是"你说完一句 → 它接一句"，
 * 而微信语音通话那种"随时插话"需要流式 STT + 双工回声消除。
 */
@Composable
fun CallRouteHost(
    container: AppContainer,
    onHangUp: () -> Unit,
    /**
     * 视频通话：额外开前置摄像头，**每一轮说话前抓一帧**给模型看。
     *
     * ⚠️ 不是实时视频流。把连续帧喂给模型需要流式视觉接口（多数厂商没有），
     *   而且代价是每轮都上传图片。这里的做法是"你说话的那一刻，它看得见你"。
     */
    video: Boolean = false,
) {
    // ⚠️ 状态机是**唯一事实来源**。界面与副作用都从它派生，
    //    不在这里另存一份"isPlaying"之类的布尔 —— 那必然漂移。
    var state by remember { mutableStateOf(VoiceCall.start()) }
    var spoken by remember { mutableStateOf("") }
    var heard by remember { mutableStateOf("") }
    var trouble by remember { mutableStateOf("") }
    var turn by remember { mutableStateOf(0) }

    // ⚠️ 端口要复用：每一轮新建一个等于每轮都重新解析 Key、重新选模型。
    val chat = remember(container) { ContainerChatPort(container) }
    // 这一通电话的上下文。不落库 —— 通话是"临时插进来的一段"，
    // 落库要么污染角色会话，要么多出一堆只说过一句的会话。
    var dialogue by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }

    val personaName = remember { container.currentCustomPersona()?.name.orEmpty() }

    // ══════════════════════════════════════════════════════════
    //  ★ 视频通话：摄像头
    // ══════════════════════════════════════════════════════════
    val context = androidx.compose.ui.platform.LocalContext.current
    val camera = remember(video) { if (video) CameraStill(context) else null }
    var cameraGranted by remember(video) {
        mutableStateOf(!video || container.hasCameraPermission())
    }
    var cameraTrouble by remember { mutableStateOf("") }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (!granted) cameraTrouble = "没给相机权限，视频通话只能用语音；要开就去系统设置里允许「相机」"
    }

    // ══════════════════════════════════════════════════════════
    //  ★★ 挂断 / 离开页面时**真的停**
    // ══════════════════════════════════════════════════════════
    //
    // ⚠️ 不用 `LaunchedEffect`，用 `DisposableEffect`：它的 `onDispose`
    //    在页面被销毁时一定执行（包括按返回键、被系统回收）。
    DisposableEffect(Unit) {
        onDispose {
            runCatching { container.stopSpeaking() }
                .onFailure { Timber.w(it, "离开通话页时停止播报失败") }
            runCatching { camera?.close() }
                .onFailure { Timber.w(it, "离开通话页时释放摄像头失败") }
            Timber.i("通话页销毁：已停止播报，摄像头已释放")
        }
    }

    // ══════════════════════════════════════════════════════════
    //  主循环：一个人的一轮通话 = listen → think → speak
    // ══════════════════════════════════════════════════════════
    LaunchedEffect(Unit) {
        while (isActive) {
            when (state.phase) {
                VoiceCall.Phase.LISTENING -> {
                    if (!container.hasMicPermission()) {
                        trouble = "还没给麦克风权限 —— 去设置里允许「录音」才能听见你"
                        delay(600)
                        continue
                    }
                    trouble = ""
                    val text = runCatching { container.listenOnce(LISTEN_MS) }
                        .onFailure { Timber.w(it, "通话识别失败") }
                        .getOrNull()
                        .orEmpty()
                    // 没听清（或用户没说话）：留在这一格，接着听。
                    // ⚠️ 不推进相位 —— 推进了界面会显示"在想"，
                    //    可实际上什么事都没发生。
                    if (text.isBlank()) continue
                    heard = text
                    dialogue = dialogue + ChatMessage.user(text)
                    state = VoiceCall.userFinishedTalking(state)
                    turn += 1
                }

                VoiceCall.Phase.THINKING -> {
                    // ★ 视频通话：说话的那一刻抓一帧。
                    //   ⚠️ 抓不到（权限没给/设备占用）就退化成纯语音，
                    //      不因为"看不见"就把这一轮卡住。
                    val frame = if (video && cameraGranted) {
                        runCatching { camera?.captureJpeg() }
                            .onFailure { Timber.w(it, "抓帧失败") }
                            .getOrNull()
                    } else {
                        null
                    }
                    val reply = runCatching {
                        chat.send(
                            history = dialogue,
                            // ★ 通话固定走最低思考档：这一格的等待是用户
                            //   亲耳在听的，多一秒都很明显。
                            effortScene = EffortScene.CALL,
                            mode = ChatMode.ROLEPLAY,
                            image = frame,
                            imageMimeType = "image/jpeg",
                        )
                    }.onFailure { Timber.w(it, "通话请求失败") }.getOrNull()

                    when (reply) {
                        is ChatResult.Ok -> {
                            val body = reply.text
                            if (body.isBlank()) {
                                trouble = "它这一轮没说出话，再说一句试试"
                                state = VoiceCall.interrupt(state).state
                                delay(200)
                            } else {
                                dialogue = dialogue + ChatMessage.assistant(body)
                                state = VoiceCall.beginSpeaking(state, body)
                            }
                        }

                        is ChatResult.NoCredential -> {
                            trouble = "还没有可用的模型 Key —— 去「设置 → API Key」加一个"
                            state = VoiceCall.hangUp(state)
                            delay(1_200)
                            onHangUp()
                            return@LaunchedEffect
                        }

                        is ChatResult.Failed -> {
                            trouble = reply.reason
                            state = VoiceCall.interrupt(state).state
                            delay(400)
                        }

                        null -> {
                            trouble = "请求没走通，等下再试一句"
                            state = VoiceCall.interrupt(state).state
                            delay(400)
                        }
                    }
                }

                VoiceCall.Phase.SPEAKING -> {
                    val body = state.pendingText.ifBlank { spoken }
                    spoken = body
                    state = VoiceCall.appendSpoken(state, body)
                    val ok = runCatching { container.speakBlocking(body) }
                        .onFailure { Timber.w(it, "通话播报失败") }
                        .getOrDefault(false)
                    if (!ok) {
                        // 没有语音 Key / 播放失败：别让用户对着哑巴说话，
                        // 但也不挂断 —— 文字稿还在屏幕上。
                        trouble = "这一句没能出声（检查语音 Key 与媒体音量）；文字在这里"
                    }
                    // ⚠️ 只有相位**还是** SPEAKING 才收尾：
                    //    期间用户可能已经按了打断，那时状态由打断那条路决定。
                    if (state.phase == VoiceCall.Phase.SPEAKING) {
                        state = VoiceCall.finishSpeaking(state)
                    }
                }

                VoiceCall.Phase.IDLE -> delay(120)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CallScreen(
            state = state,
            personaName = personaName,
            spokenText = spoken.ifBlank { heard },
            subtitle = trouble,
            onInterrupt = {
                // ══════════════════════════════════════════════════
                //  ★★ **用户打断** —— 三件事一起做
                // ══════════════════════════════════════════════════
                val result = VoiceCall.interrupt(state)
                if (result.didInterrupt) {
                    runCatching { container.stopSpeaking() }
                        .onFailure { Timber.w(it, "打断时停止播报失败") }
                    // ⚠️ 已播出的话**保留**（`result.keepText`）——
                    //    直接清空会让那段话从历史里凭空消失，而用户明明听见了。
                    spoken = result.keepText
                    state = result.state
                    trouble = ""
                    Timber.i("通话被打断，保留已播 %d 字", result.keepText.length)
                }
                turn += 1
            },
            onReplay = {
                val keep = spoken.ifBlank { state.spokenText }
                if (keep.isNotBlank()) {
                    state = VoiceCall.replay(state, keep)
                    spoken = ""
                }
            },
            onHangUp = {
                runCatching { container.stopSpeaking() }
                state = VoiceCall.hangUp(state)
                onHangUp()
            },
        )

        // ══════════════════════════════════════════════════════
        //  视频通话的两块：自己的画面（小窗）+ 授权按钮
        // ══════════════════════════════════════════════════════
        if (video && cameraGranted) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 56.dp, start = PaSpace.m)
                    .size(96.dp, 128.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
            ) {
                if (camera != null) {
                    CameraPreview(still = camera, modifier = Modifier.fillMaxSize())
                }
            }
        }
        if (video && !cameraGranted) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 120.dp, start = PaSpace.l, end = PaSpace.l),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.Text(
                    text = cameraTrouble.ifBlank { "视频通话要能看见你，需要相机权限" },
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color(0xFFE5A0A0),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(PaSpace.s))
                androidx.compose.material3.TextButton(
                    onClick = { cameraLauncher.launch(android.Manifest.permission.CAMERA) },
                ) {
                    androidx.compose.material3.Text("允许使用相机", color = androidx.compose.ui.graphics.Color(0xFFB9D4FF))
                }
            }
        }

        AnimatedVisibility(
            visible = state.phase != VoiceCall.Phase.IDLE,
            enter = fadeIn(PaMotion.fast()),
            exit = fadeOut(PaMotion.fast()),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 56.dp, end = PaSpace.m),
        ) {
            MicBadge(open = VoiceCall.shouldMicBeOpen(state))
        }
    }
}

/** 一格最多听多久 —— 到点还没说完就当这一格结束。 */
private const val LISTEN_MS = 12_000L
