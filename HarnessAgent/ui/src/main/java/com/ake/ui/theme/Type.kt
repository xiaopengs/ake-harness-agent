package com.ake.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DisplayStyle = TextStyle(
    fontSize = 32.sp,
    lineHeight = 40.sp,
    fontWeight = FontWeight.Bold,
)

private val HeadlineStyle = TextStyle(
    fontSize = 24.sp,
    lineHeight = 32.sp,
    fontWeight = FontWeight.Bold,
)

private val TitleStyle = TextStyle(
    fontSize = 18.sp,
    lineHeight = 26.sp,
    fontWeight = FontWeight.SemiBold,
)

private val BodyStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.Normal,
)

private val LabelStyle = TextStyle(
    fontSize = 13.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.Medium,
)

public val AkeCaptionStyle: TextStyle = TextStyle(
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Normal,
)

public val AkeTypography: Typography = Typography(
    displayLarge = DisplayStyle,
    displayMedium = DisplayStyle,
    displaySmall = DisplayStyle,
    headlineLarge = HeadlineStyle,
    headlineMedium = HeadlineStyle,
    headlineSmall = HeadlineStyle,
    titleLarge = TitleStyle,
    titleMedium = TitleStyle,
    titleSmall = TitleStyle,
    bodyLarge = BodyStyle,
    bodyMedium = BodyStyle,
    bodySmall = BodyStyle,
    labelLarge = LabelStyle,
    labelMedium = LabelStyle,
    labelSmall = AkeCaptionStyle,
)
