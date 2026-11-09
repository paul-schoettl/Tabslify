package com.tabslify.tabs.school.study

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.tabslify.R
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.BgSurface
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary
import java.io.File

@Composable
fun StudyUploadScreen(
    state: StudyUploadState,
    onTitle: (String) -> Unit,
    onSubject: (String) -> Unit,
    onMinutes: (String) -> Unit,
    onLanguage: (String) -> Unit,
    onAddImages: (List<Uri>) -> Unit,
    onRemoveImage: (String) -> Unit,
    onMoveImage: (String, Int) -> Unit,
    onAnalyze: () -> Unit,
    onBack: () -> Unit,
    paddingValues: PaddingValues,
) {
    val context = LocalContext.current
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) onAddImages(uris)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) onAddImages(listOf(uri))
        pendingCameraFile?.delete()
        pendingCameraFile = null
        pendingCameraUri = null
    }

    fun startCamera() {
        val file = File(context.cacheDir, "study_capture_${System.currentTimeMillis()}.jpg")
        pendingCameraFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() }

    Column(modifier = Modifier.fillMaxSize()) {
        StudyHeader(
            title = stringResource(R.string.study_upload_title),
            subtitle = stringResource(R.string.study_upload_subtitle),
            onBack = onBack,
            paddingValues = paddingValues,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = onTitle,
                    label = { Text(stringResource(R.string.study_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = state.subject,
                    onValueChange = onSubject,
                    label = { Text(stringResource(R.string.study_subject_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.minutesPerDay.toString(),
                        onValueChange = onMinutes,
                        label = { Text(stringResource(R.string.study_minutes_label)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.language,
                        onValueChange = onLanguage,
                        label = { Text(stringResource(R.string.study_language_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudySecondaryButton(
                        text = stringResource(R.string.study_add_images),
                        modifier = Modifier.weight(1f),
                        enabled = !state.busy && state.images.size < StudyUploadViewModel.MAX_IMAGES,
                        onClick = { galleryLauncher.launch("image/*") },
                    )
                    StudySecondaryButton(
                        text = stringResource(R.string.foto_aufnehmen),
                        modifier = Modifier.weight(1f),
                        enabled = !state.busy && state.images.size < StudyUploadViewModel.MAX_IMAGES,
                        onClick = {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                startCamera()
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                    )
                }
            }

            if (state.images.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.images, key = { it.id }) { image ->
                            StudyUploadThumb(
                                image = image,
                                index = state.images.indexOf(image) + 1,
                                canMoveLeft = state.images.indexOf(image) > 0,
                                canMoveRight = state.images.indexOf(image) < state.images.lastIndex,
                                onRemove = { onRemoveImage(image.id) },
                                onMove = { direction -> onMoveImage(image.id, direction) },
                            )
                        }
                    }
                }
            }

            if (state.busy) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgSurface)
                            .padding(14.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = TextPrimary,
                        )
                        Text(
                            text = state.progress ?: stringResource(R.string.study_analyzing),
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            item { StudyErrorBanner(state.error) }
            item { StudyNoticeBanner(state.notice) }

            item {
                StudyPrimaryButton(
                    text = stringResource(R.string.study_analyze),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.images.isNotEmpty() && !state.busy,
                    onClick = onAnalyze,
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StudyUploadThumb(
    image: StudyUploadImage,
    index: Int,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            AsyncImage(
                model = image.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgSurface)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("$index", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgSurface)
                    .clickable(enabled = canMoveLeft) { onMove(-1) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("◀", color = if (canMoveLeft) TextSecondary else TextTertiary, fontSize = 12.sp)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgSurface)
                    .clickable { onRemove() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.study_remove_image),
                    color = TextTertiary,
                    fontSize = 9.sp,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgSurface)
                    .clickable(enabled = canMoveRight) { onMove(1) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("▶", color = if (canMoveRight) TextSecondary else TextTertiary, fontSize = 12.sp)
            }
        }
    }
}
