package com.sqlai.agente.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sqlai.agente.ui.components.SectionHeader
import com.sqlai.agente.ui.theme.DeepPanel
import com.sqlai.agente.ui.theme.NeonAmber
import com.sqlai.agente.ui.theme.NeonCyan
import com.sqlai.agente.ui.theme.NeonLime
import com.sqlai.agente.ui.theme.NeonViolet
import com.sqlai.agente.ui.theme.RaisedPanel
import com.sqlai.agente.ui.theme.StrokeDim
import com.sqlai.agente.ui.theme.TextMuted

private val HOTKEYS = listOf("CTRL+C", "CTRL+L", "TAB", "↑/↓")

/**
 * Tab 2 — Terminal Engine.
 * PTY-backed view of the embedded Linux sandbox with a hotkey rail, scrollback
 * buffer and command history navigation.
 */
@Composable
fun TerminalScreen(
    output: String,
    running: Boolean,
    banner: String,
    onCommand: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
) {
    var cmd by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var histIdx by remember { mutableIntStateOf(-1) }
    val scroll = rememberLazyListState()

    LaunchedEffect(output) {
        val last = scroll.layoutInfo.totalItemsCount
        if (last > 0) scroll.animateScrollToItem(last - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (running) NeonLime else NeonAmber)
            )
            Text(
                if (running) "sandbox active" else "sandbox idle",
                style = MaterialTheme.typography.labelSmall,
                color = if (running) NeonLime else NeonAmber,
            )
            Spacer(Modifier.weight(1f))
            TerminalIconButton(Icons.Default.PlayArrow, "Start", NeonLime, onStart)
            TerminalIconButton(Icons.Default.Stop, "Stop", NeonAmber, onStop)
            TerminalIconButton(Icons.Default.Refresh, "Refresh", NeonCyan, onRefresh)
        }

        SectionHeader("Hotkeys", NeonViolet)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HOTKEYS.forEach { key ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = RaisedPanel,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StrokeDim),
                ) {
                    Text(
                        key,
                        Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Surface(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF03050A),
            border = androidx.compose.foundation.BorderStroke(1.dp, StrokeDim),
        ) {
            LazyColumn(
                state = scroll,
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                item {
                    Text(
                        banner.ifBlank { "SQL AI AGENTE terminal\nboot the sandbox to begin" },
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = NeonViolet,
                        ),
                    )
                }
                items(output.lines()) { l ->
                    Text(
                        l,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (l.contains("error", true)) NeonAmber else NeonLime,
                        ),
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "$ ",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = NeonCyan,
                ),
            )
            BasicTextField(
                value = cmd,
                onValueChange = { cmd = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(NeonCyan),
                singleLine = true,
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DeepPanel)
                            .border(1.dp, StrokeDim, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                    ) {
                        if (cmd.isEmpty()) {
                            Text(
                                "Type a command…",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    color = TextMuted,
                                ),
                            )
                        }
                        inner()
                    }
                },
            )
            IconButton(
                onClick = {
                    val c = cmd.trim()
                    if (c.isEmpty()) return@IconButton
                    history += c
                    histIdx = history.size
                    onCommand(c)
                    cmd = ""
                },
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NeonCyan),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Run",
                    tint = Color(0xFF05070C),
                )
            }
        }
    }
}

@Composable
private fun TerminalIconButton(
    icon: ImageVector,
    desc: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(RaisedPanel)
            .border(1.dp, StrokeDim, RoundedCornerShape(9.dp)),
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(18.dp))
    }
}
