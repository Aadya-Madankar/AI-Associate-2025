package com.example.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vision.VisionState

/**
 * Two compact toggles for what Nazim SEES beyond the accessibility tree: the **camera**
 * (the world in front of the phone) or a **screen-share** (the screen's pixels). Only one
 * streams at a time; tapping the active one turns it off.
 */
@Composable
fun VisionControls(
    visionState: VisionState,
    onToggleCamera: () -> Unit,
    onToggleScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterChip(
            selected = visionState == VisionState.CAMERA,
            onClick = onToggleCamera,
            leadingIcon = { Icon(Icons.Rounded.CameraAlt, contentDescription = null) },
            label = { Text("Camera") }
        )
        FilterChip(
            selected = visionState == VisionState.SCREEN,
            onClick = onToggleScreen,
            leadingIcon = { Icon(Icons.Rounded.ScreenShare, contentDescription = null) },
            label = { Text("Screen") }
        )
    }
}
