package com.example.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audit.AuditOutcome
import com.example.audit.AuditRecord
import com.example.di.ServiceLocator
import com.example.permission.AutonomyMode
import com.example.ui.theme.XenoWarm
import com.example.viewmodels.XenoViewModel
import com.example.vision.VisionState

/**
 * XENO's one settings surface — a warm-light [ModalBottomSheet] opened from the gear icon on
 * [com.example.ui.screens.LiveScreen]. Folds together autonomy mode, the panic kill-switch,
 * recent audit history, API keys (replacing the standalone `ApiKeysSheet`) and the vision/roam
 * toggles, all in the same XenoWarm idiom as the rest of the screen.
 *
 * This wrapper only owns sheet chrome + async state (loading recent audit rows); the actual
 * layout lives in [SettingsSheetContent] so it can be composed and tested without a real
 * [XenoViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit, viewModel: XenoViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val autonomyMode by viewModel.autonomyMode.collectAsStateWithLifecycle()
    val apiKeys by viewModel.apiKeys.collectAsStateWithLifecycle()
    val visionState by viewModel.visionState.collectAsStateWithLifecycle()
    val roamEnabled by viewModel.roamEnabled.collectAsStateWithLifecycle()

    var auditRecords by remember { mutableStateOf<List<AuditRecord>>(emptyList()) }
    LaunchedEffect(Unit) {
        auditRecords = runCatching { ServiceLocator.auditLog.recent(50) }.getOrDefault(emptyList())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = XenoWarm.SurfaceStrong,
        contentColor = XenoWarm.TextPrimary,
        scrimColor = XenoWarm.Scrim
    ) {
        SettingsSheetContent(
            autonomyMode = autonomyMode,
            onSetMode = viewModel::setAutonomyMode,
            onKillSwitch = viewModel::stopAgent,
            auditRecords = auditRecords,
            apiKeys = apiKeys,
            onAddKey = viewModel::addApiKey,
            onRemoveKey = viewModel::removeApiKey,
            cameraOn = visionState == VisionState.CAMERA,
            onToggleCamera = viewModel::toggleCameraVision,
            screenOn = visionState == VisionState.SCREEN,
            onToggleScreen = viewModel::requestScreenShare,
            roamOn = roamEnabled,
            onToggleRoam = viewModel::toggleRoam,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 32.dp)
        )
    }
}

/**
 * The sheet's actual content, decoupled from [XenoViewModel] so it can be rendered in a plain
 * Robolectric compose test. Five sections in the order the task specifies: autonomy mode,
 * kill-switch, recent activity, API keys, vision.
 */
@Composable
fun SettingsSheetContent(
    autonomyMode: AutonomyMode,
    onSetMode: (AutonomyMode) -> Unit,
    onKillSwitch: () -> Unit,
    auditRecords: List<AuditRecord>,
    apiKeys: List<String>,
    onAddKey: (String) -> Unit,
    onRemoveKey: (String) -> Unit,
    cameraOn: Boolean,
    onToggleCamera: () -> Unit,
    screenOn: Boolean,
    onToggleScreen: () -> Unit,
    roamOn: Boolean,
    onToggleRoam: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("Settings", color = XenoWarm.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

        // -- 1. Autonomy mode --------------------------------------------------
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Mode")
            ModeSegmentedRow(selected = autonomyMode, onSelect = onSetMode)
        }

        // -- 2. Kill switch -----------------------------------------------------
        KillSwitchRow(onConfirm = onKillSwitch)

        // -- 3. Recent activity ---------------------------------------------------
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Recent activity")
            RecentActivityList(auditRecords)
        }

        // -- 4. API keys ----------------------------------------------------------
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("API keys")
            ApiKeysSection(keys = apiKeys, onAdd = onAddKey, onRemove = onRemoveKey)
        }

        // -- 5. Vision ------------------------------------------------------------
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Vision")
            VisionToggleRow(Icons.Rounded.Videocam, "See (camera)", cameraOn, onToggleCamera)
            VisionToggleRow(Icons.Rounded.ScreenShare, "Screen share", screenOn, onToggleScreen)
            VisionToggleRow(Icons.Rounded.DirectionsWalk, "Roam over other apps", roamOn, onToggleRoam)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = XenoWarm.TextTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp
    )
}

/** Direct-select pill row — tap a mode to switch straight to it (no cycling). */
@Composable
private fun ModeSegmentedRow(selected: AutonomyMode, onSelect: (AutonomyMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(XenoWarm.BgMid.copy(alpha = 0.5f))
            .border(1.dp, XenoWarm.Hairline, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            AutonomyMode.ASK to "Ask",
            AutonomyMode.ASK_LESS to "Ask-less",
            AutonomyMode.AUTO to "Auto",
            AutonomyMode.BYPASS to "Bypass"
        ).forEach { (mode, label) ->
            val isSelected = mode == selected
            val bg = if (isSelected) XenoWarm.TextPrimary else Color.Transparent
            val fg = if (isSelected) XenoWarm.TextOnDark else XenoWarm.TextSecondary
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** The panic stop — a plain red row that only fires after a confirm dialog. */
@Composable
private fun KillSwitchRow(onConfirm: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(XenoWarm.Error.copy(alpha = 0.10f))
            .border(1.dp, XenoWarm.Error.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable { showConfirm = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Rounded.Stop, null, tint = XenoWarm.Error, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text("Stop everything", color = XenoWarm.Error, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "Halts any action in progress and resets to Ask",
                color = XenoWarm.Error.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Stop everything?") },
            text = { Text("XENO will immediately stop any action it's taking and autonomy resets to Ask.") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onConfirm() }) {
                    Text("Stop", color = XenoWarm.Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
            containerColor = XenoWarm.SurfaceStrong,
            titleContentColor = XenoWarm.TextPrimary,
            textContentColor = XenoWarm.TextSecondary
        )
    }
}

@Composable
private fun RecentActivityList(records: List<AuditRecord>) {
    if (records.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(XenoWarm.Surface.copy(alpha = 0.55f))
                .border(1.dp, XenoWarm.Hairline, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text("No activity yet.", color = XenoWarm.TextTertiary, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = (records.size.coerceAtMost(5) * 56).dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(records, key = { it.id }) { record -> AuditRow(record) }
    }
}

@Composable
private fun AuditRow(record: AuditRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(XenoWarm.Surface.copy(alpha = 0.55f))
            .border(1.dp, XenoWarm.Hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(outcomeIcon(record.outcome), null, tint = outcomeColor(record.outcome), modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = describeAction(record),
                color = XenoWarm.TextPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(relativeTime(record.timestampMs), color = XenoWarm.TextTertiary, fontSize = 11.sp)
    }
}

private fun describeAction(record: AuditRecord): String {
    val label = record.actionType.replace('_', ' ')
    return if (record.targetApp.isNullOrBlank()) label else "$label · ${record.targetApp}"
}

private fun outcomeIcon(outcome: AuditOutcome): ImageVector = when (outcome) {
    AuditOutcome.ALLOWED_AUTO, AuditOutcome.ALLOWED_CONFIRMED -> Icons.Rounded.CheckCircle
    AuditOutcome.BLOCKED, AuditOutcome.DENIED_BY_USER -> Icons.Rounded.Block
    AuditOutcome.FAILED -> Icons.Rounded.ErrorOutline
}

private fun outcomeColor(outcome: AuditOutcome): Color = when (outcome) {
    AuditOutcome.ALLOWED_AUTO, AuditOutcome.ALLOWED_CONFIRMED -> XenoWarm.Success
    AuditOutcome.BLOCKED, AuditOutcome.DENIED_BY_USER -> XenoWarm.Warning
    AuditOutcome.FAILED -> XenoWarm.Error
}

/** Coarse "Xm/h/d ago" — no date library needed for a 50-row recent list. */
private fun relativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val sec = (nowMs - timestampMs).coerceAtLeast(0) / 1000
    return when {
        sec < 60 -> "just now"
        sec < 3600 -> "${sec / 60}m ago"
        sec < 86400 -> "${sec / 3600}h ago"
        else -> "${sec / 86400}d ago"
    }
}

// =====================================================================================
//  API keys (folded in from the deleted ApiKeysSheet)
// =====================================================================================

@Composable
private fun ApiKeysSection(keys: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var input by remember { mutableStateOf("") }

    Text(
        "Paste one or more Gemini API keys. XENO uses them in order and fails over " +
            "automatically. Keys stay on this phone.",
        color = XenoWarm.TextSecondary,
        fontSize = 12.sp
    )

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            label = { Text("Paste a Gemini API key") },
            shape = RoundedCornerShape(12.dp),
            keyboardActions = KeyboardActions(
                onDone = { if (input.isNotBlank()) { onAdd(input.trim()); input = "" } }
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = XenoWarm.TextPrimary,
                unfocusedBorderColor = XenoWarm.Hairline,
                cursorColor = XenoWarm.TextPrimary,
                focusedTextColor = XenoWarm.TextPrimary,
                unfocusedTextColor = XenoWarm.TextPrimary,
                focusedLabelColor = XenoWarm.TextPrimary,
                unfocusedLabelColor = XenoWarm.TextTertiary
            ),
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = { if (input.isNotBlank()) { onAdd(input.trim()); input = "" } },
            enabled = input.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = XenoWarm.TextPrimary,
                contentColor = XenoWarm.TextOnDark,
                disabledContainerColor = XenoWarm.Hairline,
                disabledContentColor = XenoWarm.TextTertiary
            )
        ) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
        }
    }

    if (keys.isEmpty()) {
        Text(
            "No keys added yet — XENO will use the build-time key if one is bundled.",
            color = XenoWarm.TextTertiary,
            fontSize = 12.sp
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${keys.size} KEY${if (keys.size == 1) "" else "S"} · USED TOP TO BOTTOM",
                color = XenoWarm.TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            )
            keys.forEach { key -> ApiKeyRow(masked = maskKey(key), onRemove = { onRemove(key) }) }
        }
    }
}

@Composable
private fun ApiKeyRow(masked: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(XenoWarm.Surface.copy(alpha = 0.55f))
            .border(1.dp, XenoWarm.Hairline, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Rounded.Key, null, tint = XenoWarm.TextSecondary, modifier = Modifier.size(15.dp))
        Text(
            masked,
            color = XenoWarm.TextPrimary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.DeleteOutline, "Remove key", tint = XenoWarm.Error, modifier = Modifier.size(18.dp))
        }
    }
}

private fun maskKey(k: String): String = if (k.length <= 12) k else k.take(7) + "…" + k.takeLast(4)

// =====================================================================================
//  Vision toggles
// =====================================================================================

@Composable
private fun VisionToggleRow(icon: ImageVector, label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(XenoWarm.Surface.copy(alpha = 0.55f))
            .border(1.dp, XenoWarm.Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = XenoWarm.TextPrimary.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = XenoWarm.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = XenoWarm.SurfaceStrong,
                checkedTrackColor = XenoWarm.TextPrimary,
                uncheckedThumbColor = XenoWarm.SurfaceStrong,
                uncheckedTrackColor = XenoWarm.Hairline
            )
        )
    }
}
