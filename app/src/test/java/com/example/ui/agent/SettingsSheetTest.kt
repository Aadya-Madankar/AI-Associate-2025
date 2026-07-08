package com.example.ui.agent

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.audit.AuditRecord
import com.example.permission.AutonomyMode
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test for [SettingsSheetContent] — the ModalBottomSheet wrapper ([SettingsSheet]) needs a
 * real [com.example.viewmodels.XenoViewModel] + [com.example.di.ServiceLocator], so the content
 * composable is tested directly in isolation, same split used for the sheet chrome elsewhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSheetTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun rendersWithEmptyAuditListWithoutCrashing_andShowsAllFourSectionHeaders() {
        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsSheetContent(
                    autonomyMode = AutonomyMode.ASK,
                    onSetMode = {},
                    onKillSwitch = {},
                    auditRecords = emptyList<AuditRecord>(),
                    apiKeys = emptyList(),
                    onAddKey = {},
                    onRemoveKey = {},
                    cameraOn = false,
                    onToggleCamera = {},
                    screenOn = false,
                    onToggleScreen = {},
                    roamOn = false,
                    onToggleRoam = {}
                )
            }
        }

        composeTestRule.onNodeWithText("MODE").assertExists()
        composeTestRule.onNodeWithText("RECENT ACTIVITY").assertExists()
        composeTestRule.onNodeWithText("API KEYS").assertExists()
        composeTestRule.onNodeWithText("VISION").assertExists()
    }
}
