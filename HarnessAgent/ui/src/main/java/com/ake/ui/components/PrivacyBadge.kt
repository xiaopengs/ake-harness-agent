package com.ake.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ake.ui.theme.AkeTheme

@Composable
public fun PrivacyBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val privacyColor = MaterialTheme.colorScheme.secondary
    Row(
        modifier = modifier.background(
            color = privacyColor.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp),
        ).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "✓",
            color = privacyColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = text,
            color = privacyColor,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun PrivacyBadgePreviewContent() {
    Surface(Modifier.padding(16.dp)) {
        PrivacyBadge("数据不出手机")
    }
}

@Preview(name = "Privacy Badge Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun PrivacyBadgeLightPreview() {
    AkeTheme(darkTheme = false) { PrivacyBadgePreviewContent() }
}

@Preview(name = "Privacy Badge Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PrivacyBadgeDarkPreview() {
    AkeTheme(darkTheme = true) { PrivacyBadgePreviewContent() }
}
