package dev.vvivid.newsfeed.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppearanceStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun startsFollowingTheSystemWithTheAppsOwnColors() {
        assertEquals(Appearance(ThemeMode.System, useDynamicColor = false), AppearanceStore(context).appearance.value)
    }

    @Test
    fun keepsTheChoiceForTheNextLaunch() {
        val chosen = Appearance(ThemeMode.Light, useDynamicColor = true, keepScreenOn = false)
        val store = AppearanceStore(context)

        store.update(chosen)

        assertEquals(chosen, store.appearance.value)
        assertEquals(chosen, AppearanceStore(context).appearance.value)
    }
}
