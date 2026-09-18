package com.yms.idphoto.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yms.idphoto.photo.BackgroundStyle
import com.yms.idphoto.photo.EncodedJpeg
import com.yms.idphoto.photo.PhotoResult
import com.yms.idphoto.photo.Status
import com.yms.idphoto.photo.ValidationItem
import com.yms.idphoto.photo.worst

@Composable
fun ResultScreen(
    result: PhotoResult,
    preview: Bitmap?,
    encoded: EncodedJpeg?,
    background: BackgroundStyle,
    onBackgroundChange: (BackgroundStyle) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = result.spec
    val verdict = result.report.worst

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Ink.Deep)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            text = verdictTitle(verdict),
            style = MaterialTheme.typography.headlineSmall,
            color = statusColor(verdict),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = spec.label + " · " + spec.summary,
            style = MaterialTheme.typography.bodySmall,
            color = Ink.PaperDim,
        )

        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .aspectRatio(spec.aspect)
                    .border(1.dp, Ink.NavyLift, RoundedCornerShape(2.dp))
                    .background(Ink.Navy),
            ) {
                preview?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "완성된 " + spec.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                DetailLine("크기", "%d × %d px".format(spec.outWidthPx, spec.outHeightPx))
                DetailLine("실물", "%.0f × %.0f mm".format(spec.widthMm, spec.heightMm))
                encoded?.let {
                    DetailLine("용량", "%.0f KB".format(it.bytes.size / 1024f))
                    DetailLine("품질", "JPEG %d".format(it.quality))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "배경 · " + background.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.PaperDim,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BackgroundStyle.entries.forEach { style ->
                        BackgroundSwatch(
                            style = style,
                            selected = style == background,
                            onClick = { onBackgroundChange(style) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        result.report.forEach { item ->
            ReportRow(item)
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton("저장", primary = true, modifier = Modifier.weight(1f), onClick = onSave)
            ActionButton("보내기", primary = false, modifier = Modifier.weight(1f), onClick = onShare)
            ActionButton("다시 찍기", primary = false, modifier = Modifier.weight(1f), onClick = onRetake)
        }

        Spacer(Modifier.height(22.dp))
        spec.notes.forEach { note ->
            Row(modifier = Modifier.padding(bottom = 6.dp)) {
                Text("·", color = Ink.PaperDim, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Text(note, color = Ink.PaperDim, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Ink.PaperDim,
            modifier = Modifier.width(40.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Ink.Paper)
    }
}

@Composable
private fun BackgroundSwatch(style: BackgroundStyle, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (style == BackgroundStyle.ORIGINAL) Ink.NavyLift else Color(style.color))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Ink.Foil else Ink.NavyLift,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (style == BackgroundStyle.ORIGINAL) {
            Text("원", style = MaterialTheme.typography.bodySmall, color = Ink.Paper)
        }
    }
}

@Composable
private fun ReportRow(item: ValidationItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor(item.status)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.Paper,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = item.detail,
            style = MaterialTheme.typography.bodySmall,
            color = Ink.PaperDim,
            modifier = Modifier.weight(1f),
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Ink.NavyLift.copy(alpha = 0.5f)),
    )
}

@Composable
private fun ActionButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (primary) Ink.Foil else Color.Transparent)
            .border(1.dp, if (primary) Ink.Foil else Ink.NavyLift, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (primary) Ink.Deep else Ink.Paper,
        )
    }
}

private fun verdictTitle(status: Status): String = when (status) {
    Status.PASS -> "규격에 맞습니다"
    Status.WARN -> "쓸 수 있지만 확인이 필요합니다"
    Status.FAIL -> "다시 촬영하는 게 좋습니다"
}

private fun statusColor(status: Status): Color = when (status) {
    Status.PASS -> Ink.Good
    Status.WARN -> Ink.Caution
    Status.FAIL -> Ink.Stamp
}
