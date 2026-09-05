package io.legado.app.ui.config.labConfig

import android.app.Application
import android.os.Looper
import io.legado.app.domain.gateway.LabSettingsGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.model.settings.LabSettings
import io.legado.app.domain.model.settings.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LabConfigViewModelTest {

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    @Test
    fun enableEInkDisplay_appliesElinkThemeAndFlipsFlag() = runBlocking {
        val labGateway = FakeLabSettingsGateway()
        val themeGateway = FakeThemeSettingsGateway()
        val viewModel = createViewModel(labGateway, themeGateway)

        viewModel.onIntent(LabConfigIntent.SetEInkDisplay(true))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(true, viewModel.uiState.value.settings.eInkDisplay)
        assertEquals("4", themeGateway.currentSettings.appTheme)
        assertEquals(1, themeGateway.updateCount)
    }

    @Test
    fun disableEInkDisplay_flipsFlagWithoutTouchingTheme() = runBlocking {
        val labGateway = FakeLabSettingsGateway(
            LabSettings(enabled = true, eInkDisplay = true)
        )
        val themeGateway = FakeThemeSettingsGateway()
        val viewModel = createViewModel(labGateway, themeGateway)

        viewModel.onIntent(LabConfigIntent.SetEInkDisplay(false))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(false, viewModel.uiState.value.settings.eInkDisplay)
        assertEquals(0, themeGateway.updateCount)
    }

    @Test
    fun setEnabled_doesNotTouchTheme() = runBlocking {
        val themeGateway = FakeThemeSettingsGateway()
        val viewModel = createViewModel(themeGateway = themeGateway)

        viewModel.onIntent(LabConfigIntent.SetEnabled(true))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(true, viewModel.uiState.value.settings.enabled)
        assertEquals(0, themeGateway.updateCount)
    }

    private fun createViewModel(
        labGateway: LabSettingsGateway = FakeLabSettingsGateway(),
        themeGateway: ThemeSettingsGateway = FakeThemeSettingsGateway(),
    ) = LabConfigViewModel(
        settingsGateway = labGateway,
        themeSettingsGateway = themeGateway,
    )

    private class FakeLabSettingsGateway(
        initial: LabSettings = LabSettings(),
    ) : LabSettingsGateway {
        private val state = MutableStateFlow(initial)

        override val currentSettings: LabSettings
            get() = state.value
        override val settings: Flow<LabSettings> = state

        override suspend fun update(transform: (LabSettings) -> LabSettings) {
            state.value = transform(state.value)
        }
    }

    private class FakeThemeSettingsGateway : ThemeSettingsGateway {
        private val state = MutableStateFlow(ThemeSettings())
        var updateCount = 0
            private set

        override val currentSettings: ThemeSettings
            get() = state.value
        override val settings: Flow<ThemeSettings> = state

        override suspend fun update(transform: (ThemeSettings) -> ThemeSettings) {
            state.value = transform(state.value)
            updateCount++
        }
    }
}
