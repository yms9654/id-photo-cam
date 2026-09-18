package com.yms.idphoto.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.yms.idphoto.camera.AnalysisFrame
import com.yms.idphoto.camera.Check
import com.yms.idphoto.camera.FaceAnalyzer
import com.yms.idphoto.camera.Guidance
import com.yms.idphoto.camera.GuidanceRules
import com.yms.idphoto.camera.HEAD_TOP_EXTENSION
import com.yms.idphoto.camera.HeadMetrics
import com.yms.idphoto.camera.ReadyDebouncer
import com.yms.idphoto.spec.PhotoSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

@Composable
fun CameraScreen(
    spec: PhotoSpec,
    onSpecChange: (PhotoSpec) -> Unit,
    onCaptured: (ByteArray, Int) -> Unit,
    busy: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val capturedCallback by rememberUpdatedState(onCaptured)

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }
    var autoCapture by remember { mutableStateOf(true) }
    var frame by remember { mutableStateOf<AnalysisFrame?>(null) }
    var readyStable by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var countdown by remember { mutableStateOf(0) }
    var capturing by remember { mutableStateOf(false) }

    val debouncer = remember { ReadyDebouncer() }
    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
    }
    val analyzer = remember { FaceAnalyzer { frame = it } }
    var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var availableLenses by remember { mutableStateOf(emptyList<Int>()) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    // 화면에 실제로 그려지는 미리보기 영역과 그 안의 규격 프레임.
    val frameAspect = frame?.let { it.frameWidth.toFloat() / it.frameHeight } ?: (3f / 4f)
    val previewRect: Rect? = if (viewSize == IntSize.Zero) null else {
        fitCenterRect(viewSize.width.toFloat(), viewSize.height.toFloat(), frameAspect)
    }
    val guideRect: Rect? = previewRect?.let { guideFrameIn(it, spec.aspect) }

    val mirrored = lensFacing == CameraSelector.LENS_FACING_FRONT
    val metrics = if (previewRect != null && guideRect != null) {
        frame?.let { headMetrics(it, previewRect, guideRect, mirrored) }
    } else null
    val guidance: Guidance = GuidanceRules.evaluate(spec, frame?.faceCount ?: 0, metrics)

    LaunchedEffect(guidance.ready, frame) {
        readyStable = debouncer.update(guidance.ready)
    }
    LaunchedEffect(spec, lensFacing) {
        debouncer.reset()
        readyStable = false
    }

    fun takePhoto() {
        if (capturing || busy) return
        capturing = true
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()
                    capturing = false
                    capturedCallback(bytes, rotation)
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                }
            },
        )
    }

    LaunchedEffect(readyStable, autoCapture, busy) {
        if (!autoCapture || !readyStable || busy) {
            countdown = 0
            return@LaunchedEffect
        }
        delay(350)
        for (n in 3 downTo 1) {
            countdown = n
            delay(850)
        }
        countdown = 0
        takePhoto()
    }

    LaunchedEffect(lensFacing) {
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }

        // 기기에 없는 렌즈로 바인딩하면 CameraX 가 예외를 던진다. 먼저 있는 것만 추려 둔다.
        val lenses = listOf(CameraSelector.LENS_FACING_FRONT, CameraSelector.LENS_FACING_BACK)
            .filter { runCatching { provider.hasCamera(lensSelector(it)) }.getOrDefault(false) }
        availableLenses = lenses

        val target = if (lensFacing in lenses) lensFacing else lenses.firstOrNull()
        if (target == null) {
            cameraError = "사용할 수 있는 카메라가 없습니다"
            return@LaunchedEffect
        }
        if (target != lensFacing) {
            lensFacing = target // 이 effect 가 다시 돌면서 바인딩한다.
            return@LaunchedEffect
        }

        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(selector).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }

        try {
            provider.unbindAll()
            boundProvider = provider
            provider.bindToLifecycle(lifecycleOwner, lensSelector(target), preview, analysis, imageCapture)
            cameraError = null
        } catch (t: Throwable) {
            cameraError = "카메라를 열지 못했습니다: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            boundProvider?.unbindAll()
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Deep),
    ) {
        SpecSelector(
            spec = spec,
            onSpecChange = onSpecChange,
            canFlip = availableLenses.size > 1,
            onFlip = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
            },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { viewSize = it },
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            GuideOverlay(
                spec = spec,
                ready = readyStable,
                modifier = Modifier.fillMaxSize(),
                frameProvider = { guideRect },
            )
            if (countdown > 0) {
                Text(
                    text = countdown.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = Ink.Foil,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        GuidancePanel(
            guidance = guidance,
            cameraError = cameraError,
            ready = readyStable,
            busy = busy || capturing,
            autoCapture = autoCapture,
            onAutoCaptureChange = { autoCapture = it },
            onShutter = { takePhoto() },
        )
    }
}

private fun lensSelector(facing: Int): CameraSelector =
    CameraSelector.Builder().requireLensFacing(facing).build()

/** 분석 프레임 좌표(0~1)를 가이드 프레임 기준 좌표로 옮긴다. */
private fun headMetrics(
    frame: AnalysisFrame,
    preview: Rect,
    guide: Rect,
    mirrored: Boolean,
): HeadMetrics? {
    val face = frame.face ?: return null
    fun mapY(ny: Float) = (preview.top + ny * preview.height - guide.top) / guide.height
    fun mapX(nx: Float): Float {
        val viewX = if (mirrored) {
            preview.right - nx * preview.width
        } else {
            preview.left + nx * preview.width
        }
        return (viewX - guide.left) / guide.width
    }

    val eyes = when {
        face.leftEyeOpen != null && face.rightEyeOpen != null ->
            min(face.leftEyeOpen, face.rightEyeOpen)
        else -> face.leftEyeOpen ?: face.rightEyeOpen
    }
    return HeadMetrics(
        headTop = mapY(face.top - face.height * HEAD_TOP_EXTENSION),
        chin = mapY(face.bottom),
        centerX = mapX(face.centerX),
        eulerX = face.eulerX,
        eulerY = face.eulerY,
        eulerZ = face.eulerZ,
        eyesOpen = eyes,
        luma = frame.luma,
    )
}

@Composable
private fun SpecSelector(
    spec: PhotoSpec,
    onSpecChange: (PhotoSpec) -> Unit,
    canFlip: Boolean,
    onFlip: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.Navy)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoSpec.ALL.forEach { option ->
            val selected = option.id == spec.id
            Column(
                modifier = Modifier
                    .padding(end = 20.dp)
                    .clickable { onSpecChange(option) },
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) Ink.Foil else Ink.PaperDim,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(if (selected) 34.dp else 0.dp)
                        .height(2.dp)
                        .background(Ink.Foil),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (canFlip) {
            Icon(
                imageVector = Icons.Filled.Cameraswitch,
                contentDescription = "앞뒤 카메라 전환",
                tint = Ink.Paper,
                modifier = Modifier
                    .size(26.dp)
                    .clickable(onClick = onFlip),
            )
        }
    }
    Text(
        text = spec.summary,
        style = MaterialTheme.typography.bodySmall,
        color = Ink.PaperDim,
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.Navy)
            .padding(start = 16.dp, bottom = 10.dp),
    )
}

@Composable
private fun GuidancePanel(
    guidance: Guidance,
    cameraError: String?,
    ready: Boolean,
    busy: Boolean,
    autoCapture: Boolean,
    onAutoCaptureChange: (Boolean) -> Unit,
    onShutter: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.Navy)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = cameraError ?: if (busy) "사진을 다듬는 중" else guidance.message,
            style = MaterialTheme.typography.headlineSmall,
            color = when {
                cameraError != null -> Ink.Stamp
                ready -> Ink.Foil
                else -> Ink.Paper
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            guidance.checks.forEach { check -> CheckDot(check) }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Switch(
                    checked = autoCapture,
                    onCheckedChange = onAutoCaptureChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Ink.Deep,
                        checkedTrackColor = Ink.Foil,
                        uncheckedThumbColor = Ink.PaperDim,
                        uncheckedTrackColor = Ink.NavyLift,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "자동 촬영",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.PaperDim,
                )
            }
            ShutterButton(ready = ready, enabled = !busy, onClick = onShutter)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ShutterButton(ready: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ringAlpha by animateFloatAsState(if (ready) 1f else 0.45f, label = "shutterRing")
    Box(
        modifier = Modifier
            .size(68.dp)
            .border(2.dp, Ink.Foil.copy(alpha = ringAlpha), CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (enabled) Ink.Paper else Ink.NavyLift)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun CheckDot(check: Check) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (check.ok) Ink.Good else Ink.NavyLift),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = check.label,
            style = MaterialTheme.typography.bodySmall,
            color = if (check.ok) Ink.PaperDim else Ink.PaperDim.copy(alpha = 0.5f),
        )
    }
}
