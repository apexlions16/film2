@file:OptIn(ExperimentalTextApi::class)

package com.apexlions.film2.studio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.apexlions.film2.studio.R

// Manrope (OFL, Google Fonts), bundled locally as a single variable-weight TTF — gives the
// admin app its own typographic identity distinct from android-player's Outfit, without
// depending on network/Play Services at first launch.
private fun manropeWeight(weight: Int) = Font(
    resId = R.font.manrope_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val ManropeFamily = FontFamily(
    manropeWeight(400),
    manropeWeight(500),
    manropeWeight(600),
    manropeWeight(700),
    manropeWeight(800),
)

val Film2StudioTypography = Typography(
    displaySmall = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(800), fontSize = 30.sp, lineHeight = 36.sp),
    headlineLarge = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(700), fontSize = 24.sp, lineHeight = 30.sp),
    headlineMedium = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(700), fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(600), fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(400), fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(400), fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(400), fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(600), fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(600), fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight(500), fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.3.sp),
)
