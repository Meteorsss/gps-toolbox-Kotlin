package com.meteorsss.startrace

import org.junit.Assert
import org.junit.Test

class ThemeModeTest {
    @Test
    fun manualModesIgnoreSystem() {
        Assert.assertFalse(ThemeMode.DARK.isLight(false))
        Assert.assertFalse(ThemeMode.DARK.isLight(true))
        Assert.assertTrue(ThemeMode.LIGHT.isLight(false))
        Assert.assertTrue(ThemeMode.LIGHT.isLight(true))
    }

    @Test
    fun systemModeFollowsAppearance() {
        Assert.assertTrue(ThemeMode.SYSTEM.isLight(false))
        Assert.assertFalse(ThemeMode.SYSTEM.isLight(true))
    }

    @Test
    fun legacyManualChoiceIsPreserved() {
        Assert.assertEquals(ThemeMode.LIGHT, ThemeMode.Companion.restore(null, true, true))
        Assert.assertEquals(ThemeMode.DARK, ThemeMode.Companion.restore(null, true, false))
    }

    @Test
    fun explicitModeWinsAndNewInstallFollowsSystem() {
        Assert.assertEquals(ThemeMode.SYSTEM, ThemeMode.Companion.restore("SYSTEM", true, false))
        Assert.assertEquals(ThemeMode.LIGHT, ThemeMode.Companion.restore("LIGHT", true, false))
        Assert.assertEquals(ThemeMode.SYSTEM, ThemeMode.Companion.restore(null, false, false))
        Assert.assertEquals(ThemeMode.SYSTEM, ThemeMode.Companion.restore("unknown", false, false))
    }
}
