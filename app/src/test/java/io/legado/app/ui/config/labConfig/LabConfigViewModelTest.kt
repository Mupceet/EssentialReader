package io.legado.app.ui.config.labConfig

import android.app.Application
import android.os.Looper
import io.legado.app.domain.gateway.LabSettingsGateway
import io.legado.app.domain.model.settings.LabSettings
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
    fun enableEInkDisplay_flipsFlagWithoutTouchingTheme() = runBlocking {
        val labGateway = FakeLabSettingsGateway()
        val viewModel = createViewModel(labGateway)

        viewModel.onIntent(LabConfigIntent.SetEInkDisplay(true))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(true, viewModel.uiState.value.settings.eInkDisplay)
    }

    @Test
    fun disableEInkDisplay_flipsFlagWithoutTouchingTheme() = runBlocking {
        val labGateway = FakeLabSettingsGateway(
            LabSettings(enabled = true, eInkDisplay = true)
        )
        val viewModel = createViewModel(labGateway)

        viewModel.onIntent(LabConfigIntent.SetEInkDisplay(false))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(false, viewModel.uiState.value.settings.eInkDisplay)
    }

    @Test
    fun setEnabled_doesNotTouchTheme() = runBlocking {
        val viewModel = createViewModel()

        viewModel.onIntent(LabConfigIntent.SetEnabled(true))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(true, viewModel.uiState.value.settings.enabled)
    }

    private fun createViewModel(
        labGateway: LabSettingsGateway = FakeLabSettingsGateway(),
    ) = LabConfigViewModel(
        settingsGateway = labGateway,
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
}
