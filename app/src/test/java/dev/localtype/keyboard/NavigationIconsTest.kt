package dev.localtype.keyboard

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationIconsTest {
    @Test
    fun aLightKeyboardGetsDarkButtonsInTheBand() {
        assertTrue(ThemeMode.darkNavigationIcons(Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL))
    }

    @Test
    fun aDarkKeyboardGetsLightButtonsInTheBand() {
        assertFalse(ThemeMode.darkNavigationIcons(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL))
    }

    @Test
    fun anUnknownNightModeIsTreatedAsLight() {
        assertTrue(ThemeMode.darkNavigationIcons(Configuration.UI_MODE_NIGHT_UNDEFINED))
    }

    @Test
    fun theKeyboardSetsTheButtonsWhenItShowsAndWhenTheThemeChanges() {
        val source = java.io.File("src/main/java/dev/localtype/keyboard/LocalTypeInputMethodService.kt").readText()
        val shown = source.substringAfter("override fun onWindowShown()").substringBefore("\n    }")
        assertTrue(shown.contains("applyNavigationIcons()"))
        val themeChange = source.substringAfter("if (theme != themeMode) {").substringBefore("\n        }")
        assertTrue(themeChange.contains("applyNavigationIcons()"))
    }
}
