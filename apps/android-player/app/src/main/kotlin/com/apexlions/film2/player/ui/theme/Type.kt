@file:OptIn(ExperimentalTextApi::class)

package com.apexlions.film2.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.apexlions.film2.player.R

// Outfit (OFL, Google Fonts) as a single variable-weight TTF resource — bundled locally
// rather than fetched at runtime via the Downloadable Fonts provider, so typography never
// depends on Google Play Services being present or network being up at first launch.
private fun outfitWeight(weight: Int) = Font(
    resId = R.font.outfit_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val OutfitFamily = FontFamily(
    outfitWeight(400),
    outfitWeight(500),
    outfitWeight(600),
    outfitWeight(700),
    outfitWeight(800),
)

val Film2PlayerTypography = Typography(
    displayLarge = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(800), fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(700), fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.3).sp),
    headlineLarge = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(700), fontSize = 26.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(600), fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(600), fontSize = 19.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(500), fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(400), fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(600), fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight(500), fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp),
)
