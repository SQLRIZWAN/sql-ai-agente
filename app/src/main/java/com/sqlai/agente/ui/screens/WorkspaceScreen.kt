package com.sqlai.agente.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sqlai.agente.core.monitor.SystemStats
import com.sqlai.agente.ui.components.SectionHeader
import com.sqlai.agente.ui.components.SystemMonitorPanel
import com.sqlai.agente.ui.theme.DeepPanel
import com.sqlai.agente.ui.theme.NeonAmber
import com.sqlai.agente.ui.theme.NeonCyan
import com.sqlai.agente.ui.theme.NeonViolet
import com.sqlai.agente.ui.theme.RaisedPanel
import com.sqlai.agente.ui.theme.StrokeDim
import com.sqlai.agente.ui.theme.TextMuted
import com.sqlai.agente.ui.theme.TextSecondary
import kotlinx.coroutines.launch

data class ChatLine(val role: String, val text: String, val meta: String = "")

/**
 * Tab 1 — AI Command Workspace.
 * Voice + text chat with a live "thinking log" rail showing which provider served
 * the reply and how long the fallback chain took.
 */
@Composable
fun WorkspaceScreen(
    stats: SystemStats,
    history: List<ChatLine>,
    thinking: List<String>,
    busy: Boolean,
    activeProvider: String,
    onSend: (String) -> Unit,
    onVoice: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
    ) {
        SystemMonitorPanel(stats, Modifier.padding(top = 10.dp))

        SectionHeader("Thinking log", NeonViolet)
        Surface(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 96.dp),
            shape = RoundedCornerShape(10.dp),
            color = DeepPanel,
            border = androidx.compose.foundation.BorderStroke(1.dp, StrokeDim),
        ) {
            LazyColumn(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(thinking.takeLast(6)) { line ->
                    Text(
                        "› $line",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (thinking.isEmpty()) {
                    item {
                        Text(
                            "› idle — local first, cloud on demand",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        SectionHeader("Conversation", NeonCyan)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(history) { line -> MessageBubble(line) }
        }

        if (busy) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(NeonAmber)
                )
                Text(
                    "thinking via $activeProvider…",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonAmber,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onVoice,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(RaisedPanel)
                    .border(1.dp, StrokeDim, CircleShape),
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Voice", tint = NeonViolet)
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask, plan, execute…", color = TextMuted) },
                shape = RoundedCornerShape(23.dp),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = StrokeDim,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    cursorColor = NeonCyan,
                    focusedContainerColor = DeepPanel,
                    unfocusedContainerColor = DeepPanel,
                ),
            )
            IconButton(
                onClick = {
                    if (input.isNotBlank() && !busy) {
                        onSend(input.trim())
                        input = ""
                    }
                },
                enabled = input.isNotBlank() && !busy,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (input.isNotBlank() && !busy) NeonCyan else RaisedPanel),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (input.isNotBlank() && !busy) Color(0xFF05070C) else TextMuted,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(line: ChatLine) {
    val isUser = line.role == "user"
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp,
            ),
            color = if (isUser) NeonCyan.copy(alpha = 0.12f) else DeepPanel,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isUser) NeonCyan.copy(alpha = 0.4f) else StrokeDim,
            ),
        ) {
            Text(
                text = line.text,
                Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (line.meta.isNotBlank()) {
            Text(
                line.meta,
                Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}
