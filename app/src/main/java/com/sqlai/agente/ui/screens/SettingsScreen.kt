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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sqlai.agente.ui.components.SectionHeader
import com.sqlai.agente.ui.theme.DeepPanel
import com.sqlai.agente.ui.theme.NeonCyan
import com.sqlai.agente.ui.theme.NeonLime
import com.sqlai.agente.ui.theme.NeonRose
import com.sqlai.agente.ui.theme.NeonViolet
import com.sqlai.agente.ui.theme.RaisedPanel
import com.sqlai.agente.ui.theme.StrokeDim
import com.sqlai.agente.ui.theme.TextMuted
import com.sqlai.agente.ui.theme.TextSecondary

private data class VaultField(
    val key: String,
    val label: String,
    val hint: String,
    val secret: Boolean = true,
)

private val AI_FIELDS = listOf(
    VaultField("provider.gemini", "Gemini API key", "AIza…"),
    VaultField("provider.grok", "Grok API key", "xai-…"),
    VaultField("provider.deepseek", "DeepSeek API key", "sk-…"),
    VaultField("provider.openai", "OpenAI API key", "sk-…"),
    VaultField("provider.ollama.endpoint", "Ollama / LM Studio endpoint", "http://192.168.1.5:11434", false),
)

private val EXCHANGE_FIELDS = listOf(
    VaultField("exchange.binance.api_key", "Binance API key", "…"),
    VaultField("exchange.binance.api_secret", "Binance API secret", "…"),
    VaultField("exchange.bitget.api_key", "Bitget API key", "…"),
    VaultField("exchange.bitget.api_secret", "Bitget API secret", "…"),
    VaultField("exchange.bitget.passphrase", "Bitget passphrase", "…"),
    VaultField("exchange.bybit.api_key", "Bybit API key", "…"),
    VaultField("exchange.bybit.api_secret", "Bybit API secret", "…"),
    VaultField("exchange.exness.token", "Exness token", "…"),
)

private data class ModelField(
    val key: String,
    val label: String,
    val hint: String,
    val presets: List<String>,
)

private val MODEL_FIELDS = listOf(
    ModelField(
        "model.gemini", "Gemini model", "gemini-2.0-flash",
        listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash-lite"),
    ),
    ModelField(
        "model.grok", "Grok model", "grok-3-mini",
        listOf("grok-3-mini", "grok-3", "grok-4-fast", "grok-3-mini-latest"),
    ),
    ModelField(
        "model.deepseek", "DeepSeek model", "deepseek-chat",
        listOf("deepseek-chat", "deepseek-reasoner"),
    ),
    ModelField(
        "model.openai", "OpenAI model", "gpt-4o-mini",
        listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "o4-mini"),
    ),
    ModelField(
        "model.ollama", "Ollama / LM Studio model", "qwen2.5-coder:3b",
        listOf("qwen2.5-coder:3b", "llama3.2:3b", "phi4-mini", "mistral:7b-instruct", "gemma3:4b"),
    ),
)

private const val LOCAL_MODEL_KEY = "model.local"

/**
 * Tab 5 — Settings & API Vault.
 * Keys are AES-256-GCM sealed through the Android Keystore the moment "Save" is hit;
 * the UI never echoes a stored value back, only a masked "••• set" indicator.
 */
@Composable
fun SettingsScreen(
    hasKey: (String) -> Boolean,
    onSave: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    providerOrder: String,
    onProviderOrderChanged: (String) -> Unit,
    read: (String) -> String? = { null },
    localModelState: String = "EMPTY",
    onLoadLocal: (String) -> Unit = {},
    onUnloadLocal: () -> Unit = {},
) {
    val visible = remember { mutableStateMapOf<String, Boolean>() }
    val drafts = remember { mutableStateMapOf<String, String>() }
    var savedFlash by remember { mutableStateOf<String?>(null) }
    var localPath by remember { mutableStateOf(read(LOCAL_MODEL_KEY) ?: "") }

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
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonViolet.copy(alpha = 0.5f)),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = NeonViolet)
                    Column {
                        Text(
                            "Local Secure Vault",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            "AES-256-GCM · Android Keystore · zero telemetry",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        item {
            SectionHeader("Provider priority (first = preferred)", NeonCyan)
            OutlinedTextField(
                value = providerOrder,
                onValueChange = onProviderOrderChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("LOCAL,GEMINI,DEEPSEEK,GROK,OPENAI", color = TextMuted) },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = FontFamily.Monospace,
                ),
                colors = fieldColors(),
            )
            Text(
                "On heavy local load the chain falls through to the next provider automatically.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }

        item { SectionHeader("AI providers", NeonCyan) }
        items(AI_FIELDS.size) { i -> VaultRow(AI_FIELDS[i], hasKey, drafts, visible, savedFlash) { k, v ->
            onSave(k, v); savedFlash = k
        } }

        item { SectionHeader("Model selection (tap a preset or type your own)", NeonViolet) }
        items(MODEL_FIELDS.size) { i ->
            ModelRow(MODEL_FIELDS[i], read, onSave) { savedFlash = it }
        }

        item { SectionHeader("Local AI model (GGUF · runs on-device)", NeonLime) }
        item {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(11.dp),
                color = DeepPanel,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (savedFlash == LOCAL_MODEL_KEY) NeonCyan else StrokeDim,
                ),
            ) {
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "llama.cpp weights",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            localModelState,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonLime,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        OutlinedTextField(
                            value = localPath,
                            onValueChange = { localPath = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text("/storage/emulated/0/models/qwen-3b-q4.gguf", color = TextMuted)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onBackground,
                                fontFamily = FontFamily.Monospace,
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = fieldColors(),
                        )
                        TextButtonPill("Load") {
                            val path = localPath.trim()
                            if (path.isNotEmpty()) {
                                onSave(LOCAL_MODEL_KEY, path)
                                savedFlash = LOCAL_MODEL_KEY
                                onLoadLocal(path)
                            }
                        }
                        TextButtonPill("Unload") { onUnloadLocal() }
                    }
                    Text(
                        "Path is stored encrypted; weights are evicted on background/thermal throttle.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        }

        item { SectionHeader("Exchange keys", NeonRose) }
        items(EXCHANGE_FIELDS.size) { i -> VaultRow(EXCHANGE_FIELDS[i], hasKey, drafts, visible, savedFlash) { k, v ->
            onSave(k, v); savedFlash = k
        } }

        item { SectionHeader("Storage", NeonViolet) }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DeepPanel)
                    .border(1.dp, StrokeDim, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = NeonRose, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("Purge conversation + trade logs", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text("Encrypted DB rows are shredded, vault keys are kept",
                        style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                TextButtonPill("Purge") { onDelete("__PURGE__") }
            }
        }
        item { Box(Modifier.size(10.dp)) }
    }
}

@Composable
private fun VaultRow(
    field: VaultField,
    hasKey: (String) -> Boolean,
    drafts: MutableMap<String, String>,
    visible: MutableMap<String, Boolean>,
    savedFlash: String?,
    onSave: (String, String) -> Unit,
) {
    val isSet = hasKey(field.key)
    val show = visible[field.key] == true
    val draft = drafts[field.key] ?: ""

    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        color = DeepPanel,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (savedFlash == field.key) NeonCyan else StrokeDim,
        ),
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    field.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (isSet) {
                    Text(
                        "••• set",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                        fontFamily = FontFamily.Monospace,
                    )
                    IconButton(onClick = { onSave(field.key, "") }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear",
                            tint = NeonRose, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { drafts[field.key] = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(field.hint, color = TextMuted) },
                    singleLine = true,
                    visualTransformation = if (show || !field.secret) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Uri,
                    ),
                    colors = fieldColors(),
                )
                if (field.secret) {
                    IconButton(
                        onClick = { visible[field.key] = !show },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(RaisedPanel),
                    ) {
                        Icon(
                            if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle",
                            tint = TextSecondary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                TextButtonPill("Save") {
                    val v = draft.trim()
                    if (v.isNotEmpty()) {
                        onSave(field.key, v)
                        drafts[field.key] = ""
                    }
                }
            }
        }
    }
}

/**
 * Model picker row: shows the active model, a row of preset chips (tap = apply
 * immediately) and a free-text field for custom model ids (Save = apply).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ModelRow(
    field: ModelField,
    read: (String) -> String?,
    onSave: (String, String) -> Unit,
    onSaved: (String) -> Unit,
) {
    var current by remember(field.key) { mutableStateOf(read(field.key) ?: "") }
    var draft by remember(field.key) { mutableStateOf("") }

    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        color = DeepPanel,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (current.isNotEmpty()) NeonCyan.copy(alpha = 0.55f) else StrokeDim,
        ),
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    field.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (current.isEmpty()) "default: ${field.hint}" else current,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (current.isEmpty()) TextMuted else NeonCyan,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Preset chips — tap applies instantly (encrypted at rest).
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                field.presets.forEach { preset ->
                    val selected = current == preset
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) NeonCyan.copy(alpha = 0.20f)
                                else RaisedPanel
                            )
                            .border(
                                1.dp,
                                if (selected) NeonCyan else StrokeDim,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable {
                                current = preset
                                onSave(field.key, preset)
                                onSaved(field.key)
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(
                            preset,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) NeonCyan else TextSecondary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(field.hint, color = TextMuted) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontFamily = FontFamily.Monospace,
                    ),
                    colors = fieldColors(),
                )
                TextButtonPill("Save") {
                    val v = draft.trim()
                    if (v.isNotEmpty()) {
                        current = v
                        onSave(field.key, v)
                        onSaved(field.key)
                        draft = ""
                    }
                }
            }
        }
    }
}

@Composable
private fun TextButtonPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(NeonCyan.copy(alpha = 0.16f))
            .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = NeonCyan)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NeonCyan,
    unfocusedBorderColor = StrokeDim,
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    cursorColor = NeonCyan,
    focusedContainerColor = RaisedPanel,
    unfocusedContainerColor = RaisedPanel,
)
