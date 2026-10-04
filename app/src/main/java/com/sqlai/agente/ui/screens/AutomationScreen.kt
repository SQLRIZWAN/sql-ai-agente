package com.sqlai.agente.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sqlai.agente.core.automation.UiNode
import com.sqlai.agente.ui.components.SectionHeader
import com.sqlai.agente.ui.theme.DeepPanel
import com.sqlai.agente.ui.theme.NeonAmber
import com.sqlai.agente.ui.theme.NeonCyan
import com.sqlai.agente.ui.theme.NeonLime
import com.sqlai.agente.ui.theme.NeonViolet
import com.sqlai.agente.ui.theme.RaisedPanel
import com.sqlai.agente.ui.theme.StrokeDim
import com.sqlai.agente.ui.theme.TextMuted
import com.sqlai.agente.ui.theme.TextSecondary

/**
 * Tab 4 — App Automation & Scripts.
 * Shows accessibility permission state, live UI-tree nodes of the foreground app,
 * and lets the user trigger taps / build workflow steps.
 */
@Composable
fun AutomationScreen(
    serviceActive: Boolean,
    currentPackage: String,
    nodes: List<UiNode>,
    workflows: List<Pair<String, String>>,
    logs: List<String>,
    onTapNode: (UiNode) -> Unit,
    onOpenSettings: () -> Unit,
    onRunWorkflow: (String) -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Box(Modifier.size(4.dp)) }

        item {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = DeepPanel,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (serviceActive) NeonLime.copy(alpha = 0.5f) else NeonAmber.copy(alpha = 0.5f),
                ),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = if (serviceActive) NeonLime else NeonAmber,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (serviceActive) "Controller connected" else "Controller disabled",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            if (serviceActive) "foreground: ${currentPackage.ifBlank { "—" }}"
                            else "grant accessibility permission to control apps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(RaisedPanel)
                            .border(1.dp, StrokeDim, RoundedCornerShape(9.dp))
                            .size(38.dp),
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        item { SectionHeader("Saved workflows", NeonViolet) }
        if (workflows.isEmpty()) {
            item {
                Text(
                    "no workflows yet — steps are captured from the UI tree below",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
        }
        items(workflows) { (name, steps) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DeepPanel)
                    .border(1.dp, StrokeDim, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        steps.take(80),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                IconButton(onClick = { onRunWorkflow(name) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = NeonLime)
                }
            }
        }

        item { SectionHeader("UI tree (tap to control)", NeonCyan) }
        if (nodes.isEmpty()) {
            item {
                Text(
                    if (serviceActive) "waiting for a window…" else "enable the service to parse UI",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
        }
        items(nodes.take(60)) { n -> NodeRow(n, onTapNode) }

        item { SectionHeader("Automation log", NeonAmber) }
        items(logs.takeLast(8).asReversed()) { l ->
            Text(
                l,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
            )
        }
        item { Box(Modifier.size(8.dp)) }
    }
}

@Composable
private fun NodeRow(n: UiNode, onTap: (UiNode) -> Unit) {
    val label = n.text ?: n.desc ?: n.viewId ?: n.className ?: "node"
    val indents = n.depth.coerceAtMost(6) * 8
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = indents.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (n.clickable) RaisedPanel else Color.Transparent)
            .clickable(enabled = n.clickable) { onTap(n) }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (n.clickable) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(NeonCyan)
            )
        } else {
            Box(Modifier.size(6.dp))
        }
        Text(
            label.take(44),
            style = MaterialTheme.typography.labelSmall,
            color = if (n.clickable) NeonCyan else TextMuted,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f, fill = true),
        )
        Text(
            "${n.bounds.width}×${n.bounds.height}",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
        )
    }
}
