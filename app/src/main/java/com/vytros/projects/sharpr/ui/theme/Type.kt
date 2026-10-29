@file:OptIn(ExperimentalTextApi::class)

package com.vytros.projects.sharpr.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.AppFont

private fun variable(res: Int, vararg weights: Int, opsz: Float? = null): FontFamily = FontFamily(
    weights.map { w ->
        val settings = buildList {
            add(FontVariation.weight(w))
            if (opsz != null) add(FontVariation.Setting("opsz", opsz))
        }
        Font(
            resId = res,
            weight = FontWeight(w),
            style = FontStyle.Normal,
            variationSettings = FontVariation.Settings(*settings.toTypedArray()),
        )
    },
)

/** Headlines: Bricolage Grotesque, a characterful grotesque with optical sizes. */
val DisplayFamily = variable(R.font.bricolage_grotesque, 400, 500, 600, 700, 800, opsz = 48f)

/** Numbers and technical readouts. */
val MonoFamily = variable(R.font.jetbrains_mono, 400, 500, 600, 700)

fun bodyFamily(font: AppFont): FontFamily = when (font) {
    AppFont.Jakarta -> variable(R.font.plus_jakarta_sans, 400, 450, 500, 600, 650, 700, 800)
    AppFont.Inter -> variable(R.font.inter, 400, 450, 500, 600, 650, 700, 800)
    AppFont.Outfit -> variable(R.font.outfit, 400, 450, 500, 600, 650, 700, 800)
    AppFont.Grotesk -> variable(R.font.space_grotesk, 400, 450, 500, 600, 650, 700)
}

fun sharprTypography(font: AppFont): Typography {
    val body = bodyFamily(font)
    fun d(size: Int, weight: Int, tracking: Double, line: Int) =
        TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(weight), fontSize = size.sp, letterSpacing = tracking.em, lineHeight = line.sp)
    fun b(size: Double, weight: Int, line: Int, tracking: Double = 0.0) =
        TextStyle(fontFamily = body, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em)
    return Typography(
        displayLarge = d(48, 800, -0.035, 50),
        displayMedium = d(38, 800, -0.03, 42),
        displaySmall = d(32, 700, -0.025, 36),
        headlineLarge = d(28, 700, -0.02, 33),
        headlineMedium = d(24, 700, -0.015, 29),
        headlineSmall = d(20, 700, -0.01, 25),
        titleLarge = b(19.0, 700, 25, -0.005),
        titleMedium = b(16.0, 650, 22),
        titleSmall = b(14.0, 650, 19),
        bodyLarge = b(16.0, 450, 24),
        bodyMedium = b(14.5, 450, 21),
        bodySmall = b(12.5, 450, 17),
        labelLarge = b(14.5, 650, 19),
        labelMedium = b(12.5, 600, 16),
        labelSmall = b(11.0, 650, 14, 0.06),
    )
}

val MonoStyle = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight(500), fontSize = 12.5.sp, letterSpacing = (-0.01).em)
