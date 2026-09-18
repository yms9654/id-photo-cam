package com.yms.idphoto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yms.idphoto.update.UpdateInfo

/**
 * 새 버전 알림. 촬영을 막지 않도록 화면 위쪽 얇은 띠로만 둔다.
 * 내려받는 동안에는 진행률로 바뀐다.
 */
@Composable
fun UpdateBanner(
    info: UpdateInfo,
    progress: Float?,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.NavyLift)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(30.dp)
                    .background(Ink.Foil),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (progress == null) {
                        "새 버전 " + info.versionName
                    } else {
                        "내려받는 중 " + (progress * 100).toInt() + "%"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink.Foil,
                )
                if (info.notes.isNotBlank()) {
                    Text(
                        text = info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.PaperDim,
                    )
                }
            }
            if (progress == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(
                        text = "나중에",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ink.PaperDim,
                        modifier = Modifier.clickable(onClick = onSkip),
                    )
                    Text(
                        text = "업데이트",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ink.Foil,
                        modifier = Modifier.clickable(onClick = onInstall),
                    )
                }
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = Ink.Foil,
                trackColor = Ink.Navy,
            )
        }
    }
}
