package com.tabslify.core.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.tabslify.BuildConfig
import com.tabslify.R
import com.tabslify.core.logging.Log
import com.tabslify.core.objects.toast
import com.tabslify.core.update.ApkUpdateInfo
import com.tabslify.core.update.ApkUpdater
import kotlinx.coroutines.isActive
import com.tabslify.core.update.GitHubRelease
import com.tabslify.core.update.GitHubReleaseChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class UpdateProgress(
    val downloaded: Long,
    val total: Long,
    val partIndex: Int,
    val partCount: Int
) {
    val fraction: Float
        get() = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
}

@Composable
fun ApkUpdatePanel(visible: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ApkUpdateInfo?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<UpdateProgress?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var upToDate by remember { mutableStateOf(false) }
    var githubRelease by remember { mutableStateOf<GitHubRelease?>(null) }

    fun loadInfo() {
        if (busy) return
        if (!scope.isActive) return
        busy = true
        errorMessage = null
        progress = null
        upToDate = false
        info = null
        downloaded = null
        githubRelease = null
        scope.launch {
            withContext(Dispatchers.IO) { ApkUpdater.deleteLocalIfInstalled(context) }
            if (BuildConfig.IS_DEV) {
                ApkUpdater.fetchUpdateInfo()
                    .onSuccess { meta ->
                        if (meta == null || meta.versionCode <= BuildConfig.VERSION_CODE.toLong()) {
                            withContext(Dispatchers.IO) { ApkUpdater.deleteDownloadedUpdate(context) }
                            upToDate = true
                        } else {
                            info = meta
                            downloaded = ApkUpdater.findDownloadedFile(context, meta)
                        }
                    }
                    .onFailure { error ->
                        errorMessage = error.message ?: error.javaClass.simpleName
                    }
            } else {
                GitHubReleaseChecker.fetchLatest()
                    .onSuccess { release ->
                        if (release == null ||
                            !GitHubReleaseChecker.isNewer(
                                release.versionName,
                                BuildConfig.VERSION_NAME
                            )
                        ) {
                            upToDate = true
                        } else {
                            githubRelease = release
                        }
                    }
                    .onFailure { error ->
                        errorMessage = error.message ?: error.javaClass.simpleName
                    }
            }
            busy = false
        }
    }

    fun install(meta: ApkUpdateInfo, file: File) {
        if (ApkUpdater.installApk(context, file)) {
            toast(context, resources.getString(R.string.update_install_started))
            if (!scope.isActive) return
            scope.launch {
                val deleted = ApkUpdater.deleteRemote(meta)
                toast(
                    context,
                    resources.getString(
                        if (deleted) R.string.update_deleted else R.string.update_delete_failed
                    )
                )
            }
        } else {
            toast(context, resources.getString(R.string.update_install_permission))
        }
    }

    fun openRelease(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (error: Exception) {
            Log.w("ApkUpdatePanel", "Release-Seite konnte nicht geoeffnet werden", error)
            toast(context, resources.getString(R.string.update_open_release_failed))
        }
    }

    LaunchedEffect(visible) {
        if (visible && !busy) loadInfo()
    }

    NeonBox(
        modifier = modifier.fillMaxWidth(),
        neonColors = listOf(Accent, AccentDim),
        backgroundAlpha = 0.15f
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = stringResource(R.string.update_title),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (BuildConfig.IS_DEV) R.string.update_subtitle
                    else R.string.update_subtitle_release
                ),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.update_installed,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                ),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp
            )

            info?.let { meta ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.update_available,
                        meta.partCount,
                        meta.versionName,
                        meta.versionCode,
                        ApkUpdater.formatSize(meta.totalSize),
                        meta.partCount
                    ),
                    color = Color.White,
                    fontSize = 13.sp
                )
                if (downloaded != null) {
                    Text(
                        text = stringResource(R.string.update_ready),
                        color = Color(0xFF00FFAA),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            githubRelease?.let { release ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.update_available_release,
                        release.versionName
                    ),
                    color = Color.White,
                    fontSize = 13.sp
                )
            }

            if (upToDate) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_current),
                    color = Color(0xFF00FFAA),
                    fontSize = 13.sp
                )
            }

            progress?.let { current ->
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { current.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.15f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.update_progress,
                        current.partIndex,
                        current.partCount,
                        ApkUpdater.formatSize(current.downloaded),
                        ApkUpdater.formatSize(current.total)
                    ),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }

            errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_failed, error),
                    color = ErrorRed,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { loadInfo() },
                    enabled = !busy
                ) {
                    Text(stringResource(R.string.update_check))
                }
                if (info != null && downloaded == null) {
                    Button(
                        onClick = {
                            val meta = info ?: return@Button
                            if (busy) return@Button
                            if (!scope.isActive) return@Button
                            busy = true
                            errorMessage = null
                            progress = UpdateProgress(0L, meta.totalSize, 0, meta.partCount)
                            scope.launch {
                                ApkUpdater.downloadAndAssemble(context, meta) { done, total, index, count ->
                                    progress = UpdateProgress(done, total, index, count)
                                }
                                    .onSuccess { file ->
                                        downloaded = file
                                        progress = null
                                        install(meta, file)
                                    }
                                    .onFailure { error ->
                                        errorMessage =
                                            error.message ?: error.javaClass.simpleName
                                        progress = null
                                    }
                                busy = false
                            }
                        },
                        enabled = !busy
                    ) {
                        Text(stringResource(R.string.update_download))
                    }
                }
                if (downloaded != null && info != null) {
                    Button(
                        onClick = {
                            val file = downloaded ?: return@Button
                            val meta = info ?: return@Button
                            install(meta, file)
                        },
                        enabled = !busy
                    ) {
                        Text(stringResource(R.string.update_install))
                    }
                }
                githubRelease?.let { release ->
                    Button(
                        onClick = { openRelease(release.htmlUrl) },
                        enabled = !busy
                    ) {
                        Text(stringResource(R.string.update_open_release))
                    }
                }
            }
        }
    }
}
