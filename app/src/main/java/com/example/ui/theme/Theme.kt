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
    primary = TerracottaGlow,
    onPrimary = TerracottaDeep,
    primaryContainer = TerracottaContainerDark,
    onPrimaryContainer = TerracottaOnContainerDark,
    secondary = MintEmerald,
    onSecondary = MintDeep,
    secondaryContainer = MintContainerDark,
    onSecondaryContainer = MintOnContainerDark,
    tertiary = AmberWarm,
    onTertiary = TerracottaDeep,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberOnContainerDark,
    error = RoseError,
    onError = Color.White,
    errorContainer = RoseContainerDark,
    onErrorContainer = RoseOnContainerDark,
    background = CarbonCanvas,
    onBackground = InkWhite,
    surface = ObsidianSurface,
    onSurface = InkWhite,
    surfaceVariant = GraphiteVariant,
    onSurfaceVariant = SlateMutedDark,
    outline = HairlineDark,
    outlineVariant = HairlineSubtleDark
)

private val KitchenLightColorScheme = lightColorScheme(
    primary = TerracottaLight,
    onPrimary = Color.White,
    primaryContainer = TerracottaContainerLight,
    onPrimaryContainer = TerracottaOnContainerLight,
    secondary = SageEmeraldLight,
    onSecondary = Color.White,
    secondaryContainer = SageContainerLight,
    onSecondaryContainer = SageOnContainerLight,
    tertiary = OchreLight,
    onTertiary = Color.White,
    tertiaryContainer = OchreContainerLight,
    onTertiaryContainer = OchreOnContainerLight,
    error = CrimsonMinimalLight,
    onError = Color.White,
    errorContainer = CrimsonContainerMinimalLight,
    onErrorContainer = CrimsonOnContainerMinimalLight,
    background = AlabasterCanvas,
    onBackground = CharcoalInk,
    surface = PureWhiteSurface,
    onSurface = CharcoalInk,
    surfaceVariant = CoolMistVariant,
    onSurfaceVariant = MutedSlateLight,
    outline = HairlineLight,
    outlineVariant = HairlineSubtleLight
)

private val ChefsMateShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ChefsMateTheme(
    darkTheme: Boolean = true,
    isArabic: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) KitchenDarkColorScheme else KitchenLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = appTypography(isArabic),
        shapes = ChefsMateShapes,
        content = content
    )
}
