package com.sqlai.agente.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sqlai.agente.ui.components.SectionHeader
import com.sqlai.agente.ui.theme.DeepPanel
import com.sqlai.agente.ui.theme.NeonAmber
import com.sqlai.agente.ui.theme.NeonCyan
import com.sqlai.agente.ui.theme.NeonLime
import com.sqlai.agente.ui.theme.NeonRose
import com.sqlai.agente.ui.theme.NeonViolet
import com.sqlai.agente.ui.theme.RaisedPanel
import com.sqlai.agente.ui.theme.StrokeDim
import com.sqlai.agente.ui.theme.TextMuted
import com.sqlai.agente.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class VaultField(
    val key: String,
    val label: String,
    val hint: String,
    val secret: Boolean = true,
)

private data class ModelField(
    val key: String,
    val label: String,
    val hint: String,
    val presets: List<String>,
)

// Google AI Studio (free tier) + paid-tier ids for Gemini.
private val GEMINI_MODELS = ModelField(
    "model.gemini", "Model", "gemini-2.5-flash",
    listOf(
        "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite",
        "gemini-2.0-flash", "gemini-2.0-flash-lite",
        "gemini-1.5-flash", "gemini-1.5-pro",
    ),
)
private val GROK_MODELS = ModelField(
    "model.grok", "Model", "grok-4-fast",
    listOf("grok-4-fast", "grok-4", "grok-3-mini", "grok-3"),
)
private val DEEPSEEK_MODELS = ModelField(
    "model.deepseek", "Model", "deepseek-chat",
    listOf("deepseek-chat", "deepseek-reasoner"),
)
private val OPENAI_MODELS = ModelField(
    "model.openai", "Model", "gpt-4o-mini",
    listOf("gpt-4.1-mini", "gpt-4.1", "gpt-4.1-nano", "gpt-4o-mini", "gpt-4o", "o4-mini"),
)
private val OLLAMA_MODELS = ModelField(
    "model.ollama", "Model", "qwen2.5-coder:3b",
    listOf(
        "llama3.2:3b", "llama3.1:8b", "llama3.2:1b",
        "gemma3:4b", "gemma3:12b", "gemma2:2b",
        "qwen2.5-coder:3b", "qwen2.5:7b",
        "phi4-mini", "mistral:7b-instruct",
    ),
)

private const val LOCAL_MODEL_KEY = "model.local"

private val ROUTING_CHOICES = listOf(
    "AUTO", "LOCAL", "GEMINI", "GROK", "DEEPSEEK", "OPENAI",
)

/**
 * Tab 5 — Settings & API Vault.
 *
 * Layout rule (user request): every provider's API key + model picker live in the
 * SAME section, so nothing is scattered. Keys are AES-256-GCM sealed through the
 * Android Keystore on Save; the UI never echoes a stored value back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    hasKey: (String) -> Boolean,
    onSave: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    providerOrder: String,
    onProviderOrderChanged: (String) -> Unit,
    read: (String) -> String? = { null },
    routingMode: String = "AUTO",
    onRoutingChanged: (String) -> Unit = {},
    localModelState: String = "EMPTY",
    onLoadLocal: (String) -> Unit = {},
    onUnloadLocal: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val visible = remember { mutableStateMapOf<String, Boolean>() }
    val drafts = remember { mutableStateMapOf<String, String>() }
    var savedFlash by remember { mutableStateOf<String?>(null) }

    var localPath by remember { mutableStateOf(read(LOCAL_MODEL_KEY) ?: "") }
    var localStatus by remember { mutableStateOf<String?>(null) }
    var localFiles by remember { mutableStateOf(listLocalModels(context)) }

    val ggufPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            localStatus = "importing file into app storage…"
            val result = withContext(Dispatchers.IO) { importGguf(context, uri) }
            result.onSuccess { file ->
                localFiles = listLocalModels(context)
                localPath = file.absolutePath
                onSave(LOCAL_MODEL_KEY, file.absolutePath)
                savedFlash = LOCAL_MODEL_KEY
                localStatus = "imported ${file.name} (${file.length() / (1024 * 1024)} MB)"
                onLoadLocal(file.absolutePath)
            }.onFailure { e ->
                localStatus = "import failed: ${e.message}"
            }
        }
    }

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

        // ------------------------------ routing ------------------------------
        item {
            SectionHeader("Routing — fallback chain vs manual pin", NeonCyan)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ROUTING_CHOICES.forEach { choice ->
                    Chip(
                        label = if (choice == "AUTO") "AUTO · fallback chain" else choice,
                        selected = routingMode == choice,
                    ) { onRoutingChanged(choice) }
                }
            }
            Text(
                "AUTO walks the order below and falls through on any error/timeout. " +
                    "Pin a provider to force it exclusively (no fallback).",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            OutlinedTextField(
                value = providerOrder,
                onValueChange = onProviderOrderChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("LOCAL,GEMINI,DEEPSEEK,GROK,OPENAI", color = TextMuted) },
                label = { Text("Fallback order (first = preferred)", color = TextMuted) },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = FontFamily.Monospace,
                ),
                colors = fieldColors(),
            )
        }

        // ------------------- providers: key + model together ------------------
        item { SectionHeader("Gemini · API key + model", NeonCyan) }
        item {
            VaultRow(vaultField("provider.gemini", "Gemini API key", "AIza…"),
                hasKey, drafts, visible, savedFlash) { k, v -> onSave(k, v); savedFlash = k }
        }
        item { ModelRow(GEMINI_MODELS, read, onSave) { savedFlash = it } }

        item { SectionHeader("Grok · API key + model", NeonCyan) }
        item {
            VaultRow(vaultField("provider.grok", "Grok API key", "xai-…"),
                hasKey, drafts, visible, savedFlash) { k, v -> onSave(k, v); savedFlash = k }
        }
        item { ModelRow(GROK_MODELS, read, onSave) { savedFlash = it } }

        item { SectionHeader("DeepSeek · API key + model", NeonCyan) }
        item {
            VaultRow(vaultField("provider.deepseek", "DeepSeek API key", "sk-…"),
                hasKey, drafts, visible, savedFlash) { k, v -> onSave(k, v); savedFlash = k }
        }
        item { ModelRow(DEEPSEEK_MODELS, read, onSave) { savedFlash = it } }

        item { SectionHeader("OpenAI · API key + model", NeonCyan) }
        item {
            VaultRow(vaultField("provider.openai", "OpenAI API key", "sk-…"),
                hasKey, drafts, visible, savedFlash) { k, v -> onSave(k, v); savedFlash = k }
        }
        item { ModelRow(OPENAI_MODELS, read, onSave) { savedFlash = it } }

        item { SectionHeader("Ollama / LM Studio · endpoint + model", NeonCyan) }
        item {
            VaultRow(
                vaultField("provider.ollama.endpoint", "Endpoint", "http://192.168.1.5:11434", false),
                hasKey, drafts, visible, savedFlash,
            ) { k, v -> onSave(k, v); savedFlash = k }
        }
        item { ModelRow(OLLAMA_MODELS, read, onSave) { savedFlash = it } }

        // -------------------- local GGUF: browse + manage --------------------
        item { SectionHeader("Local AI model (llama.cpp GGUF · on-device)", NeonLime) }
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
                            "Weights",
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

                    // Already-imported GGUF files in app storage — tap to select.
                    if (localFiles.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            localFiles.forEach { f ->
                                Chip(
                                    label = f.name,
                                    selected = localPath == f.absolutePath,
                                ) {
                                    localPath = f.absolutePath
                                    onSave(LOCAL_MODEL_KEY, f.absolutePath)
                                    savedFlash = LOCAL_MODEL_KEY
                                }
                            }
                        }
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
                                Text("…/models/gemma3-4b-q4_K_M.gguf", color = TextMuted)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onBackground,
                                fontFamily = FontFamily.Monospace,
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = fieldColors(),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        TextButtonPill("Browse GGUF…") {
                            ggufPicker.launch(arrayOf("*/*"))
                        }
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
                    localStatus?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = NeonAmber)
                    }
                    Text(
                        "Browse → pick any .gguf (Llama / Gemma / Qwen …) from Files; " +
                            "it is copied into private app storage, then loaded. " +
                            "Evicted automatically on background / thermal throttle.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        }

        // ------------------------------ exchange ------------------------------
        item { SectionHeader("Exchange keys", NeonRose) }
        val exchangeFields = listOf(
            vaultField("exchange.binance.api_key", "Binance API key", "…"),
            vaultField("exchange.binance.api_secret", "Binance API secret", "…"),
            vaultField("exchange.bitget.api_key", "Bitget API key", "…"),
            vaultField("exchange.bitget.api_secret", "Bitget API secret", "…"),
            vaultField("exchange.bitget.passphrase", "Bitget passphrase", "…"),
            vaultField("exchange.bybit.api_key", "Bybit API key", "…"),
            vaultField("exchange.bybit.api_secret", "Bybit API secret", "…"),
            vaultField("exchange.exness.token", "Exness token", "…"),
        )
        items(exchangeFields.size) { i ->
            VaultRow(exchangeFields[i], hasKey, drafts, visible, savedFlash) { k, v ->
                onSave(k, v); savedFlash = k
            }
        }

        // ------------------------------ storage -------------------------------
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

private fun vaultField(key: String, label: String, hint: String, secret: Boolean = true) =
    VaultField(key, label, hint, secret)

private fun listLocalModels(context: android.content.Context): List<File> =
    runCatching {
        File(context.filesDir, "models")
            .listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }.getOrDefault(emptyList())

/**
 * Streams a SAF document into filesDir/models/<name> so llama.cpp gets a real
 * filesystem path (content:// URIs cannot be mmap'd by the native engine).
 */
private fun importGguf(context: android.content.Context, uri: android.net.Uri): Result<File> =
    runCatching {
        val resolver = context.contentResolver
        val name = resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: "model-${System.currentTimeMillis()}.gguf"
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "model.gguf" }
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val out = File(dir, safe)
        val input = resolver.openInputStream(uri) ?: error("cannot open $uri")
        input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
        out
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) NeonCyan.copy(alpha = 0.20f) else RaisedPanel)
            .border(1.dp, if (selected) NeonCyan else StrokeDim, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) NeonCyan else TextSecondary,
            fontFamily = FontFamily.Monospace,
        )
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

/** Model picker: active value + preset chips (tap = apply) + custom id field. */
@OptIn(ExperimentalLayoutApi::class)
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                field.presets.forEach { preset ->
                    Chip(label = preset, selected = current == preset) {
                        current = preset
                        onSave(field.key, preset)
                        onSaved(field.key)
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
