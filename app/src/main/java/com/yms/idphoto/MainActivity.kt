package com.yms.idphoto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yms.idphoto.ui.CameraScreen
import com.yms.idphoto.ui.IdPhotoTheme
import com.yms.idphoto.ui.Ink
import com.yms.idphoto.ui.ResultScreen
import com.yms.idphoto.ui.UpdateBanner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IdPhotoTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var askedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCamera = granted
        askedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
        vm.checkForUpdate(context)
    }
    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }
    LaunchedEffect(vm.errorMessage) {
        vm.errorMessage?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Ink.Deep,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            vm.update?.let { info ->
                UpdateBanner(
                    info = info,
                    progress = vm.updateProgress,
                    onInstall = { vm.installUpdate(context) },
                    onSkip = { vm.skipUpdate(context) },
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                when {
                    !hasCamera -> PermissionGate(
                        askedOnce = askedOnce,
                        onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                            )
                        },
                    )

                    vm.stage == Stage.RESULT && vm.result != null -> ResultScreen(
                        result = vm.result!!,
                        preview = vm.preview,
                        encoded = vm.encoded,
                        background = vm.background,
                        onBackgroundChange = vm::chooseBackground,
                        onSave = { vm.save(context) },
                        onShare = { vm.share(context) },
                        onRetake = vm::retake,
                    )

                    else -> CameraScreen(
                        spec = vm.spec,
                        onSpecChange = vm::selectSpec,
                        onCaptured = vm::onCaptured,
                        busy = vm.stage == Stage.PROCESSING,
                    )
                }

                if (vm.stage == Stage.PROCESSING) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Ink.Deep.copy(alpha = 0.82f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Ink.Foil)
                            Spacer(Modifier.height(18.dp))
                            Text(
                                text = "배경을 지우고 규격에 맞춰 자르는 중",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink.Paper,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionGate(askedOnce: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "카메라 권한이 필요합니다",
            style = MaterialTheme.typography.headlineSmall,
            color = Ink.Paper,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "사진은 휴대폰 안에서만 처리되고 어디에도 전송되지 않습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.PaperDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        Box(
            modifier = Modifier
                .border(1.dp, Ink.Foil, RoundedCornerShape(3.dp))
                .clickable { if (askedOnce) onOpenSettings() else onRequest() }
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Text(
                text = if (askedOnce) "설정에서 권한 켜기" else "권한 허용하기",
                style = MaterialTheme.typography.labelLarge,
                color = Ink.Foil,
            )
        }
    }
}
