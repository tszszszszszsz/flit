// Theme foundation for the M3 Expressive rewrite. Design memory:
// .ui-craft/brief.md (principles) + .ui-craft/tokens.md (token spine).
// Color: Material You dynamic color on API 31+; below that the static
// Google brand-blue scheme Google's own apps show (light + dark). The
// existing in-app auto/dark/light option is honored (pure reskin).
// Motion lives in FlitSpring — the expressive scheme numbers, applied
// per-component (material3 1.4.0 keeps its expressive theme internals).
package app.flit.share

import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Expressive plan coefficients — md.sys.motion.spring.*.expressive, verbatim. */
object FlitSpring {
    private const val DAMP_SPATIAL = 0.8f
    private const val STIFF_SPATIAL = 380f
    private const val DAMP_SPATIAL_FAST = 0.6f
    private const val STIFF_SPATIAL_FAST = 800f
    private const val STIFF_SPATIAL_SLOW = 200f
    private const val STIFF_FX_DEFAULT = 1600f
    private const val STIFF_FX_FAST = 3800f
    private const val STIFF_FX_SLOW = 800f

    /** Position/size/corner — may overshoot and bounce. */
    fun <T> fastSpatial(): SpringSpec<T> = spring(dampingRatio = DAMP_SPATIAL_FAST, stiffness = STIFF_SPATIAL_FAST)
    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = DAMP_SPATIAL, stiffness = STIFF_SPATIAL)
    fun <T> slowSpatial(): SpringSpec<T> = spring(dampingRatio = DAMP_SPATIAL, stiffness = STIFF_SPATIAL_SLOW)

    /** Color/opacity — damping 1.0, never overshoots. Stiffness carries the speed. */
    fun <T> fastEffects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = STIFF_FX_FAST)
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = STIFF_FX_DEFAULT)
    fun <T> slowEffects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = STIFF_FX_SLOW)
}

/** The M3 10-step corner scale as Dp constants (Shapes itself is final). */
object FlitCorner {
    val none = 0.dp
    val extraSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val largeIncreased = 20.dp
    val extraLarge = 28.dp
    val extraLargeIncreased = 32.dp
    val extraExtraLarge = 48.dp

    val full = CircleShape
}

// Theme-mapped corner slots (semantic layer over the 10-step scale).
private val ThemeShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// Static Google brand-blue for API < 31 (clean-room values, see tokens.md);
// every other role stays on the M3 baseline.
private val GoogleBlueLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
)

private val GoogleBlueDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
)

/** The colors FlitTheme uses — exposed so dialog windows can pin the same scheme. */
@Composable
fun FlitThemeColors(themeOverride: String? = null): ColorScheme {
    val dark = when (themeOverride) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) GoogleBlueDark else GoogleBlueLight
    }
}

@Composable
fun FlitTheme(themeOverride: String? = null, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FlitThemeColors(themeOverride), shapes = ThemeShapes) {
        content()
    }
}
