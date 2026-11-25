package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val KitchenDarkColorScheme = darkColorScheme(
    primary = CopperAmber,
    onPrimary = CopperDeep,
    primaryContainer = CopperContainerDark,
    onPrimaryContainer = CopperOnContainerDark,
    secondary = HerbEmerald,
    onSecondary = HerbDeep,
    secondaryContainer = HerbContainerDark,
    onSecondaryContainer = HerbOnContainerDark,
    tertiary = SaffronGold,
    onTertiary = CopperDeep,
    error = CrimsonWaste,
    onError = Color.White,
    errorContainer = CrimsonContainerDark,
    onErrorContainer = CrimsonOnContainerDark,
    background = SlateObsidian,
    onBackground = ChalkWhite,
    surface = SlateIron,
    onSurface = ChalkWhite,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = MistGray,
    outline = SlateBorder,
    outlineVariant = Color(0xFF252D34)
)

private val KitchenLightColorScheme = lightColorScheme(
    primary = BurntCopperLight,
    onPrimary = Color.White,
    primaryContainer = BurntCopperContainer,
    onPrimaryContainer = BurntCopperOnContainer,
    secondary = HerbForestLight,
    onSecondary = Color.White,
    secondaryContainer = HerbContainerLight,
    onSecondaryContainer = HerbOnContainerLight,
    tertiary = Color(0xFFB37400),
    onTertiary = Color.White,
    error = CrimsonLight,
    onError = Color.White,
    errorContainer = CrimsonContainerLight,
    onErrorContainer = CrimsonOnContainerLight,
    background = ParchmentIvory,
    onBackground = EspressoInk,
    surface = LinenWhite,
    onSurface = EspressoInk,
    surfaceVariant = LinenVariant,
    onSurfaceVariant = WarmSlateText,
    outline = StoneBorder,
    outlineVariant = Color(0xFFE4DDD1)
)

private val ChefLedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ChefLedgerTheme(
    darkTheme: Boolean = true,
    isArabic: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) KitchenDarkColorScheme else KitchenLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = appTypography(isArabic),
        shapes = ChefLedgerShapes,
        content = content
    )
}
