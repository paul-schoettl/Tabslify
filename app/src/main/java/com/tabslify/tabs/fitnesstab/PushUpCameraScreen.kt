package com.tabslify.tabs.fitnesstab

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tabslify.BuildConfig
import com.tabslify.core.ui.AccentSoft
import com.tabslify.core.ui.BgDeep
import com.tabslify.core.ui.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private val ANALYSIS_SIZES = listOf(Size(1280, 720), Size(640, 360))

@Composable
fun PushUpCameraScreen(vm: FitnessViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current

    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerHolder = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val boundUseCases = remember { mutableListOf<UseCase>() }
    val analysisHolder = remember { mutableStateOf<ImageAnalysis?>(null) }
    var rebindKey by remember { mutableIntStateOf(0) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val analyzer = remember {
        PoseAnalyzer(
            context = context,
            onFrame = { vm.onPoseFrame(it) },
            onFailure = { vm.errorMessage = it.message }
        )
    }

    DisposableEffect(lifecycleOwner) {
        var sawFirstResume = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (sawFirstResume) rebindKey++ else sawFirstResume = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                analysisHolder.value?.clearAnalyzer()
                if (boundUseCases.isNotEmpty()) {
                    providerHolder.value?.unbind(*boundUseCases.toTypedArray())
                }
            }
            boundUseCases.clear()
            analysisHolder.value = null
            analyzer.close()
            executor.shutdown()
        }
    }

    LaunchedEffect(vm.useFrontCamera, rebindKey) {
        analyzer.isFrontCamera = vm.useFrontCamera
        runCatching {
            val provider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }
            providerHolder.value = provider

            val selector = if (vm.useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            analysisHolder.value?.clearAnalyzer()
            if (boundUseCases.isNotEmpty()) {
                provider.unbind(*boundUseCases.toTypedArray())
                boundUseCases.clear()
            }
            analysisHolder.value = null

            var bindError: Throwable? = null
            for (analysisSize in ANALYSIS_SIZES) {
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .build()
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    analysisSize,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    )
                    .build()

                val bound = runCatching {
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                }
                if (bound.isSuccess) {
                    analysis.setAnalyzer(executor, analyzer)
                    preview.surfaceProvider = previewView.surfaceProvider
                    boundUseCases += listOf(preview, analysis)
                    analysisHolder.value = analysis
                    bindError = null
                    break
                }

                bindError = bound.exceptionOrNull()
                runCatching { provider.unbind(preview, analysis) }
            }

            bindError?.let { throw it }
            vm.errorMessage = null
        }.onFailure { vm.errorMessage = it.message }
    }

    LaunchedEffect(vm.lastRepAtMs) {
        if (vm.lastRepAtMs > 0L) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        if (vm.showSkeleton) {
            PoseOverlay(
                frame = vm.frame,
                phase = vm.phase,
                modifier = Modifier.fillMaxSize()
            )
        }

        PushUpHud(vm = vm, modifier = Modifier.fillMaxSize())

        if (BuildConfig.IS_DEV) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                DebugChip(
                    label = if (vm.useV3) "V3" else "V2",
                    active = vm.useV3,
                    enabled = !vm.isRunning,
                    onClick = { vm.toggleV3() }
                )
                DebugChip(
                    label = vm.capturePreset.name,
                    active = false,
                    enabled = !vm.captureActive,
                    onClick = { vm.cycleCapturePreset() }
                )
                DebugChip(
                    label = if (vm.captureActive) "REC ON" else "REC",
                    active = vm.captureActive,
                    enabled = true,
                    onClick = { vm.toggleCapture() }
                )
                vm.captureError?.let {
                    Text(
                        text = it,
                        color = Color(0xFFFF6B6B),
                        fontSize = 9.sp
                    )
                }
                vm.captureId?.let {
                    Text(
                        text = it.takeLast(15),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 9.sp
                    )
                }
                if (vm.useV3 && vm.debugSignalLine.isNotEmpty()) {
                    Text(
                        text = vm.debugSignalLine,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugChip(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) AccentSoft else BgDeep.copy(alpha = 0.82f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else Color.White.copy(alpha = 0.35f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
