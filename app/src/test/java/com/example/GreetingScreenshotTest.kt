package com.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.character.NazimFallbackPortrait
import com.example.character.NazimPersona
import com.example.character.NazimState
import com.example.core.CompanionState
import com.example.permission.AutonomyMode
import com.example.ui.agent.ModePill
import com.example.ui.components.AuroraBackground
import com.example.ui.components.MicButton
import com.example.ui.components.StatusPill
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
 * Renders the real Obsidian-Aurora UI to PNGs (no emulator needed). Uses the pure-Compose
 * [NazimFallbackPortrait] (the SceneView path can't run headless) over the live components, so
 * the captures faithfully show the design. Generate with `./gradlew recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val nazim = NazimPersona.nazim

  @Test
  fun nazim_hero_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Box(Modifier.fillMaxSize()) {
          AuroraBackground(
            state = CompanionState.LISTENING, amplitude = 0.6f, persona = nazim,
            modifier = Modifier.fillMaxSize()
          )
          NazimFallbackPortrait(
            state = NazimState.LISTENING, amplitude = 0.6f, persona = nazim,
            modifier = Modifier.fillMaxSize()
          )
          Row(
            modifier = Modifier
              .align(Alignment.TopCenter)
              .fillMaxWidth()
              .statusBarsPadding()
              .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            StatusPill(state = CompanionState.LISTENING, personaName = nazim.name)
            ModePill(mode = AutonomyMode.AUTO, onCycle = {})
          }
          Box(
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .navigationBarsPadding()
              .padding(bottom = 32.dp)
          ) {
            MicButton(state = CompanionState.LISTENING, amplitude = 0.6f, onToggle = {})
          }
        }
      }
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/nazim_hero.png")
  }

  @Test
  fun nazim_portrait_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Box(Modifier.fillMaxSize()) {
          AuroraBackground(
            state = CompanionState.SPEAKING, amplitude = 0.85f, persona = nazim,
            modifier = Modifier.fillMaxSize()
          )
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
