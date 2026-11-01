package com.ake.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ake.ui.theme.AkeTheme

@Composable
public fun EmptyState(
    title: String,
    description: String,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    iconDescription: String = title,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(56.dp).semantics { contentDescription = iconDescription },
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(text = actionText, onClick = onAction)
    }
}

@Composable
private fun EmptyStatePreviewContent() {
    Surface {
        EmptyState(
            title = "还没有对话",
            description = "开始一段对话，阿客会在本机为你处理。",
            actionText = "开始对话",
            onAction = {},
            icon = { Text("＋", style = MaterialTheme.typography.displayLarge) },
        )
    }
}

@Preview(name = "Empty State Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun EmptyStateLightPreview() {
    AkeTheme(darkTheme = false) { EmptyStatePreviewContent() }
}

@Preview(name = "Empty State Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EmptyStateDarkPreview() {
    AkeTheme(darkTheme = true) { EmptyStatePreviewContent() }
}
