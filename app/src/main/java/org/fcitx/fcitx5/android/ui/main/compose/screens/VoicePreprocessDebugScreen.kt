/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 语音预处理测试页（**仅 debug 构建**从语音输入设置页进入）。
 *
 * 用途：在真正接入识别链路之前，先用真人录音判断「VAD + 静音切除」的效果——
 * 录一段带静音的话，页面把原始与处理后两段音频并排画出来，可以分别试听；
 * 参数（切除方式、门限余量、留白等）改动后立即重算，所见即所得。
 *
 * 试听音频只写 cacheDir 下的临时 WAV，不导出、不持久化：进入页面先清理上次残留，离开页面再清一次。
 *
 * 这里的录音刻意**不加门限、不加增益**（与 [VoiceUtteranceRecorder] 一致），
 * 否则静音段被采集侧的预滚/增益改写，前后对比就没有意义了。
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.voice.VoicePermissionHelper
import org.fcitx.fcitx5.android.data.voice.VoicePermissionState
import org.fcitx.fcitx5.android.data.voice.preprocess.VoiceEmptyPolicy
import org.fcitx.fcitx5.android.data.voice.preprocess.VoicePcmPlayer
import org.fcitx.fcitx5.android.data.voice.preprocess.VoicePreprocessConfig
import org.fcitx.fcitx5.android.data.voice.preprocess.VoicePreprocessFallback
import org.fcitx.fcitx5.android.data.voice.preprocess.VoicePreprocessResult
import org.fcitx.fcitx5.android.data.voice.preprocess.VoiceSilenceTrimmer
import org.fcitx.fcitx5.android.data.voice.preprocess.VoiceSpan
import org.fcitx.fcitx5.android.data.voice.preprocess.VoiceTrimMode
import org.fcitx.fcitx5.android.data.voice.preprocess.VoiceUtteranceRecorder
import org.fcitx.fcitx5.android.data.voice.preprocess.VoiceWaveform
import org.fcitx.fcitx5.android.data.voice.preprocess.VoiceWavWriter
import org.fcitx.fcitx5.android.ui.main.compose.settings.NumberSliderInput
import org.fcitx.fcitx5.android.utils.toast
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/** 波形包络的桶数：与常见屏宽（≈1080px）下的像素密度相当。 */
private const val PEAK_BUCKETS = 480

/** 录音状态轮询间隔（电平条与时长显示）。 */
private const val RECORD_POLL_MS = 100L

/** 落盘目录（cache 下的临时试听文件 / 导出目录）。 */
private const val AUDIO_DIR = "voice-preprocess"

/** 试听目标。 */
private enum class PlayTarget { None, Original, Processed }

/** 试听用的落盘文件。 */
private data class AudioFiles(val original: File, val processed: File)

@Composable
fun VoicePreprocessDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sampleRate = VoicePreprocessConfig.SAMPLE_RATE

    var config by remember { mutableStateOf(VoicePreprocessConfig()) }
    var raw by remember { mutableStateOf<FloatArray?>(null) }
    var result by remember { mutableStateOf<VoicePreprocessResult?>(null) }
    var processing by remember { mutableStateOf(false) }

    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var recordedMs by remember { mutableIntStateOf(0) }
    var limitReached by remember { mutableStateOf(false) }

    var files by remember { mutableStateOf<AudioFiles?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var playTarget by remember { mutableStateOf(PlayTarget.None) }
    var progress by remember { mutableFloatStateOf(0f) }

    val permissionGranted by VoicePermissionState.granted.collectAsState()
    LaunchedEffect(Unit) { VoicePermissionState.refresh(context) }

    val recorder = remember {
        VoiceUtteranceRecorder(onLevel = { level = it }, onError = { context.toast(it) })
    }
    val player = remember { VoicePcmPlayer() }
    DisposableEffect(Unit) {
        // 只做临时试听：进页面清掉上次残留，离开页面再清一次，不留任何持久文件
        File(context.cacheDir, AUDIO_DIR).deleteRecursively()
        onDispose {
            player.stop()
            recorder.release()
            File(context.cacheDir, AUDIO_DIR).deleteRecursively()
        }
    }

    fun startRecording() {
        if (!VoicePermissionState.has(context)) {
            VoicePermissionHelper.requestRecordAudioPermission(context)
            return
        }
        player.stop()
        playTarget = PlayTarget.None
        raw = null
        result = null
        files = null
        limitReached = false
        recordedMs = 0
        level = 0f
        if (recorder.start()) recording = true
    }

    fun finishRecording() {
        if (!recording) return
        recording = false
        // stop() 会等待读线程收尾（≤400ms），放 IO 线程，避免卡住界面
        scope.launch {
            val samples = withContext(Dispatchers.IO) { recorder.stop() }
            level = 0f
            recordedMs = samples.size * 1000 / sampleRate
            raw = samples
            if (samples.isEmpty()) context.toast(R.string.voice_preprocess_no_audio)
        }
    }

    // 录音期间轮询时长/电平；到达上限时录音器会自行停止采集
    LaunchedEffect(recording) {
        while (recording) {
            recordedMs = recorder.recordedMs
            if (!recorder.isRecording) {
                limitReached = recorder.isReachedLimit
                finishRecording()
                break
            }
            delay(RECORD_POLL_MS)
        }
    }

    // 参数或录音变化 → 重算（纯计算，放 Default 线程）
    LaunchedEffect(raw, config) {
        val samples = raw
        if (samples == null) {
            result = null
            return@LaunchedEffect
        }
        processing = true
        result = withContext(Dispatchers.Default) { VoiceSilenceTrimmer.trim(samples, config) }
        processing = false
    }

    // 结果变化 → 重新落盘试听文件（原始 + 处理后）
    LaunchedEffect(result) {
        val current = result
        val samples = raw
        if (current == null || samples == null) {
            files = null
            preparing = false
            return@LaunchedEffect
        }
        player.stop()
        playTarget = PlayTarget.None
        progress = 0f
        preparing = true
        files = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, AUDIO_DIR).apply { mkdirs() }
            val original = File(dir, "original.wav")
            VoiceWavWriter.write(original, samples, sampleRate)
            val processed = File(dir, "processed.wav")
            VoiceWavWriter.write(processed, current.processed, sampleRate)
            AudioFiles(original, processed)
        }
        preparing = false
    }

    fun play(target: PlayTarget, file: File) {
        playTarget = target
        progress = 0f
        player.play(
            file = file,
            onProgress = { progress = it },
            onFinished = {
                playTarget = PlayTarget.None
                progress = 0f
            },
            onError = {
                playTarget = PlayTarget.None
                context.toast(it)
            },
        )
    }

    val stats = result?.stats
    val rawSamples = raw
    // 常量空数组：避免每次重组都换掉 WaveformBlock 的 remember 键，白白重算包络
    val noSamples = remember { FloatArray(0) }
    val rawMs = if (rawSamples != null) rawSamples.size * 1000 / sampleRate else 0
    val maxPeak = remember(rawSamples) {
        rawSamples?.let { VoiceWaveform.peakAmplitude(it) } ?: 0f
    }
    val thresholdAmp = if (maxPeak > 0f && stats != null && stats.frameCount > 0) {
        (10f.pow(stats.thresholdDb / 20f) / maxPeak).coerceIn(0f, 1f)
    } else {
        null
    }

    PageScaffold(
        title = stringResource(R.string.voice_preprocess_debug),
        onBack = onBack,
        contentBottomPadding = 24.dp,
    ) {
        item {
            Text(
                text = stringResource(R.string.voice_preprocess_debug_summary),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // ---- 录音 ----
        item { SmallTitle(text = stringResource(R.string.voice_preprocess_record)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(
                            text = stringResource(
                                if (recording) R.string.voice_preprocess_stop
                                else R.string.voice_preprocess_start
                            ),
                            onClick = { if (recording) finishRecording() else startRecording() },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = when {
                                recording -> stringResource(
                                    R.string.voice_preprocess_recorded,
                                    formatSeconds(recordedMs),
                                )

                                rawSamples != null -> stringResource(
                                    R.string.voice_preprocess_recorded,
                                    formatSeconds(rawMs),
                                )

                                else -> stringResource(R.string.voice_preprocess_no_audio)
                            },
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LevelBar(level = if (recording) level else 0f)
                    if (!permissionGranted) {
                        Text(
                            text = stringResource(R.string.voice_record_permission_missing),
                            color = MiuixTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(
                            text = stringResource(R.string.voice_grant_permission),
                            onClick = {
                                VoicePermissionHelper.requestRecordAudioPermission(context)
                            },
                        )
                    }
                    if (limitReached) {
                        Text(
                            text = stringResource(
                                R.string.voice_preprocess_limit_reached,
                                VoiceUtteranceRecorder.DEFAULT_MAX_DURATION_MS / 1000,
                            ),
                            color = MiuixTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.voice_preprocess_record_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // ---- 参数 ----
        item { SmallTitle(text = stringResource(R.string.voice_preprocess_params)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.voice_preprocess_mode),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    VoiceTrimMode.entries.forEach { mode ->
                        RadioButtonPreference(
                            title = stringResource(modeTitleRes(mode)),
                            selected = config.mode == mode,
                            onClick = { config = config.copy(mode = mode) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    NumberSliderInput(
                        value = config.snrMarginDb,
                        onValueChange = { config = config.copy(snrMarginDb = it) },
                        label = stringResource(R.string.voice_preprocess_margin),
                        range = 2f..20f,
                        step = 0.5f,
                        decimals = 1,
                    )
                    NumberSliderInput(
                        value = config.minSpeechMs.toFloat(),
                        onValueChange = { config = config.copy(minSpeechMs = it.toInt()) },
                        label = stringResource(R.string.voice_preprocess_min_speech),
                        range = 60f..600f,
                        step = 20f,
                    )
                    NumberSliderInput(
                        value = config.mergeGapMs.toFloat(),
                        onValueChange = { config = config.copy(mergeGapMs = it.toInt()) },
                        label = stringResource(R.string.voice_preprocess_merge_gap),
                        range = 100f..800f,
                        step = 20f,
                    )
                    if (config.mode == VoiceTrimMode.CompressInternal) {
                        NumberSliderInput(
                            value = config.keptSilenceMs.toFloat(),
                            onValueChange = { config = config.copy(keptSilenceMs = it.toInt()) },
                            label = stringResource(R.string.voice_preprocess_kept_silence),
                            range = 0f..500f,
                            step = 20f,
                        )
                    }
                    NumberSliderInput(
                        value = config.leadPadMs.toFloat(),
                        onValueChange = { config = config.copy(leadPadMs = it.toInt()) },
                        label = stringResource(R.string.voice_preprocess_lead_pad),
                        range = 0f..400f,
                        step = 20f,
                    )
                    NumberSliderInput(
                        value = config.tailPadMs.toFloat(),
                        onValueChange = { config = config.copy(tailPadMs = it.toInt()) },
                        label = stringResource(R.string.voice_preprocess_tail_pad),
                        range = 0f..600f,
                        step = 20f,
                    )
                    TextButton(
                        text = stringResource(R.string.voice_preprocess_reset),
                        onClick = { config = VoicePreprocessConfig() },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // ---- 波形与试听 ----
        item { SmallTitle(text = stringResource(R.string.voice_preprocess_waveform)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (rawSamples == null) {
                        Text(
                            text = stringResource(R.string.voice_preprocess_no_audio),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                        )
                        return@Column
                    }

                    val speechSpans = result?.speechSpans.orEmpty()
                    val removedSpans = result?.removedSpans(rawSamples.size).orEmpty()

                    Text(
                        text = stringResource(R.string.voice_preprocess_original, rawMs),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    WaveformBlock(
                        samples = rawSamples,
                        speechSpans = speechSpans,
                        removedSpans = removedSpans,
                        thresholdAmp = thresholdAmp,
                        progress = progress,
                        showProgress = playTarget == PlayTarget.Original,
                        barColor = MiuixTheme.colorScheme.primary,
                        dimColor = MiuixTheme.colorScheme.dividerLine,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.voice_preprocess_play_original),
                            onClick = { files?.let { play(PlayTarget.Original, it.original) } },
                            enabled = files != null && !preparing && !recording,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (playTarget == PlayTarget.Original) {
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    val processedMs = result?.stats?.processedMs ?: 0
                    Text(
                        text = stringResource(R.string.voice_preprocess_processed, processedMs),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    WaveformBlock(
                        samples = result?.processed ?: noSamples,
                        speechSpans = emptyList(),
                        removedSpans = emptyList(),
                        thresholdAmp = null,
                        progress = progress,
                        showProgress = playTarget == PlayTarget.Processed,
                        barColor = MiuixTheme.colorScheme.secondaryContainer,
                        dimColor = MiuixTheme.colorScheme.dividerLine,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.voice_preprocess_play_processed),
                            onClick = { files?.let { play(PlayTarget.Processed, it.processed) } },
                            enabled = files != null && !preparing && !recording,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (playTarget == PlayTarget.Processed) {
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.voice_preprocess_stop_play),
                            onClick = {
                                player.stop()
                                playTarget = PlayTarget.None
                                progress = 0f
                            },
                            enabled = playTarget != PlayTarget.None,
                        )
                    }
                    if (preparing) {
                        Text(
                            text = stringResource(R.string.voice_preprocess_preparing_audio),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        // ---- 统计 ----
        item { SmallTitle(text = stringResource(R.string.voice_preprocess_stats)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    if (stats == null) {
                        StatRow(
                            label = stringResource(R.string.voice_preprocess_no_audio),
                            value = if (processing) "…" else "",
                        )
                    } else {
                        StatRow(
                            label = stringResource(R.string.voice_preprocess_original, stats.originalMs),
                            value = stringResource(
                                R.string.voice_preprocess_processed,
                                stats.processedMs,
                            ),
                        )
                        StatRow(
                            label = stringResource(
                                R.string.voice_preprocess_saved,
                                (stats.savedRatio * 100).toInt(),
                                stats.removedMs,
                            ),
                            value = "",
                        )
                        StatRow(
                            label = stringResource(R.string.voice_preprocess_segments, stats.segmentCount),
                            value = "${stats.speechMs} ms",
                        )
                        StatRow(
                            label = stringResource(
                                R.string.voice_preprocess_level,
                                formatDb(stats.noiseFloorDb),
                                formatDb(stats.peakDb),
                                formatDb(stats.thresholdDb),
                            ),
                            value = "",
                        )
                        if (stats.lowLevel) {
                            StatRow(
                                label = stringResource(R.string.voice_preprocess_low_level),
                                value = "",
                            )
                        }
                        if (stats.truncated) {
                            StatRow(
                                label = stringResource(R.string.voice_preprocess_truncated),
                                value = "",
                            )
                        }
                        when (stats.fallback) {
                            VoicePreprocessFallback.TooShort -> StatRow(
                                label = stringResource(R.string.voice_preprocess_fallback_too_short),
                                value = "",
                            )

                            VoicePreprocessFallback.NoSpeech -> StatRow(
                                label = stringResource(
                                    if (config.emptyPolicy == VoiceEmptyPolicy.Drop) {
                                        R.string.voice_preprocess_fallback_no_speech_dropped
                                    } else {
                                        R.string.voice_preprocess_fallback_no_speech
                                    }
                                ),
                                value = "",
                            )

                            null -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** 录音电平条（0..1）。 */
@Composable
private fun LevelBar(level: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .height(6.dp)
                .background(MiuixTheme.colorScheme.primary)
        )
    }
}

/**
 * 波形块：按像素桶画峰值包络。
 *
 * [speechSpans] 非空时高亮落在语音段内的桶（其余压暗），[removedSpans] 叠一层淡色遮罩表示被切除，
 * [thresholdAmp] 画出门限参考线（归一化幅度，0..1），[showProgress] 时画播放进度线。
 */
@Composable
private fun WaveformBlock(
    samples: FloatArray,
    speechSpans: List<VoiceSpan>,
    removedSpans: List<VoiceSpan>,
    thresholdAmp: Float?,
    progress: Float,
    showProgress: Boolean,
    barColor: Color,
    dimColor: Color,
    modifier: Modifier = Modifier,
) {
    val peaks = remember(samples) {
        VoiceWaveform.peaks(samples, PEAK_BUCKETS, normalize = true)
    }
    val removedOverlay = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.16f)
    val thresholdColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.7f)
    val progressColor = MiuixTheme.colorScheme.onSurface

    Canvas(modifier.fillMaxWidth().height(84.dp)) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val sampleCount = samples.size

        if (peaks.isEmpty() || sampleCount <= 0) {
            drawLine(
                color = dimColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 1f,
            )
            return@Canvas
        }

        val bucketWidth = width / peaks.size
        val barWidth = (bucketWidth * 0.8f).coerceAtLeast(1f)
        val maxHalf = height / 2f * 0.94f

        for (i in peaks.indices) {
            val from = i * sampleCount / peaks.size
            val to = ((i + 1) * sampleCount / peaks.size).coerceAtLeast(from + 1)
            val inSpeech = speechSpans.isEmpty() ||
                    speechSpans.any { it.start < to && it.end > from }
            val half = maxHalf * sqrt(peaks[i].coerceIn(0f, 1f))
            drawRoundRect(
                color = if (inSpeech) barColor else dimColor,
                topLeft = Offset(i * bucketWidth, centerY - half),
                size = Size(barWidth, (half * 2f).coerceAtLeast(1f)),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }

        for (span in removedSpans) {
            val x = span.start.toFloat() / sampleCount * width
            val w = span.length.toFloat() / sampleCount * width
            drawRect(color = removedOverlay, topLeft = Offset(x, 0f), size = Size(w, height))
        }

        if (thresholdAmp != null && thresholdAmp > 0f) {
            val half = maxHalf * sqrt(thresholdAmp.coerceIn(0f, 1f))
            var x = 0f
            while (x < width) {
                val end = (x + 10f).coerceAtMost(width)
                drawLine(
                    color = thresholdColor,
                    start = Offset(x, centerY - half),
                    end = Offset(end, centerY - half),
                    strokeWidth = 1.5f,
                )
                x += 20f
            }
        }

        if (showProgress) {
            val x = (progress.coerceIn(0f, 1f)) * width
            drawLine(
                color = progressColor,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 2f,
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (value.isNotEmpty()) {
            Text(
                text = value,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
            )
        }
    }
}

private fun modeTitleRes(mode: VoiceTrimMode): Int = when (mode) {
    VoiceTrimMode.TrimEdges -> R.string.voice_preprocess_mode_edges
    VoiceTrimMode.CompressInternal -> R.string.voice_preprocess_mode_compress
    VoiceTrimMode.RemoveInternal -> R.string.voice_preprocess_mode_remove
}

private fun formatSeconds(ms: Int): String =
    String.format(Locale.US, "%.1f s", ms / 1000f)

private fun formatDb(db: Float): String =
    String.format(Locale.US, "%.1f", db)
