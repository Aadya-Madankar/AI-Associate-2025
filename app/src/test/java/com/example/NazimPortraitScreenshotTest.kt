package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.character.NazimFallbackPortrait
import com.example.character.NazimPersona
import com.example.character.NazimState
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the pure-Compose [NazimFallbackPortrait] to a PNG (no emulator needed; the SceneView
 * path can't run headless). Generate with `./gradlew recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class NazimPortraitScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val nazim = NazimPersona.nazim

  @Test
  fun nazim_portrait_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Box(Modifier.fillMaxSize()) {
          NazimFallbackPortrait(
            state = NazimState.SPEAKING, amplitude = 0.85f, persona = nazim,
            modifier = Modifier.fillMaxSize()
          )
        }
      }
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/nazim_portrait.png")
  }
}
