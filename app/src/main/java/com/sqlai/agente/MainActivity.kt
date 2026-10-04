package com.sqlai.agente

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.sqlai.agente.core.automation.JarvisAccessibilityService
import com.sqlai.agente.core.trading.Exchange
import com.sqlai.agente.core.worker.MaintenanceWorker
import com.sqlai.agente.core.ai.ProviderPriority
import com.sqlai.agente.data.db.AppDatabase
import com.sqlai.agente.ui.screens.AutomationScreen
import com.sqlai.agente.ui.screens.ChatLine
import com.sqlai.agente.ui.screens.SettingsScreen
import com.sqlai.agente.ui.screens.TerminalScreen
import com.sqlai.agente.ui.screens.TradingScreen
import com.sqlai.agente.ui.screens.WorkspaceScreen
import com.sqlai.agente.ui.theme.NeonAmber
import com.sqlai.agente.ui.theme.NeonCyan
import com.sqlai.agente.ui.theme.NeonRose
import com.sqlai.agente.ui.theme.NeonViolet
import com.sqlai.agente.ui.theme.NeonLime
import com.sqlai.agente.ui.theme.RaisedPanel
import com.sqlai.agente.ui.theme.SqlAiTheme
import com.sqlai.agente.ui.theme.StrokeDim
import com.sqlai.agente.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val app = application as JarvisApp
        runCatching { MaintenanceWorker.schedule(this) }
            .onFailure { android.util.Log.w("SqlAi", "WorkManager schedule skipped", it) }

        setContent {
            SqlAiTheme {
                AppRoot(app)
            }
        }

        // If the previous run crashed, surface the captured stack trace so the
        // user can screenshot it instead of the app dying silently again.
        window.decorView.post { showLastCrashIfAny() }
    }

    private fun showLastCrashIfAny() {
        val file = java.io.File(filesDir, JarvisApp.CRASH_FILE)
        if (!file.exists()) return
        val report = runCatching { file.readText() }.getOrDefault("")
        runCatching { file.delete() }
        if (report.isBlank()) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Crash report — please screenshot & share")
            .setMessage(report.take(3500))
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        // Only tear down the DB when the activity is going away for good.
        if (isFinishing) {
            runCatching { (application as? JarvisApp)?.linuxSubsystem?.closeSession() }
        }
        super.onDestroy()
    }
}

private data class Tab(val label: String, val icon: ImageVector, val accent: androidx.compose.ui.graphics.Color)

private val TABS = listOf(
    Tab("Agent", Icons.Default.Chat, NeonCyan),
    Tab("Terminal", Icons.Default.Terminal, NeonLime),
    Tab("Trading", Icons.Default.AccountBalance, NeonAmber),
    Tab("Auto", Icons.Default.Apps, NeonViolet),
    Tab("Vault", Icons.Default.Lock, NeonRose),
)

@Composable
private fun AppRoot(app: JarvisApp) {
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val stats by app.systemMonitor.stats.collectAsState()
    val tickers by app.tradingEngine.tickers.collectAsState()
    val bots by app.tradingEngine.bots.collectAsState()
    val connected by app.tradingEngine.connected.collectAsState()

    val chat = remember { mutableStateListOf<ChatLine>() }
    val thinking = remember { mutableStateListOf<String>() }
    val tradeLogs = remember { mutableStateListOf<String>() }
    val termOut = remember { mutableStateOf("") }
    val autoLogs = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var activeProvider by remember { mutableStateOf("—") }
    var termRunning by remember { mutableStateOf(false) }
    var termBanner by remember { mutableStateOf("") }

    val db = remember { runCatching { AppDatabase.open(app) }.getOrNull() }
    val a11ySvc = JarvisAccessibilityService.instance
    val a11yTree by (a11ySvc?.tree
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.sqlai.agente.core.automation.UiNode>()) })
        .collectAsState()
    val a11yActive by (a11ySvc?.active
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) })
        .collectAsState()

    LaunchedEffect(Unit) {
        // Restore recent conversation locally (encrypted at rest).
        withContext(Dispatchers.IO) {
            val rows = runCatching { db?.recentMessages(40) }.getOrNull() ?: emptyList()
            withContext(Dispatchers.Main) {
                rows.forEach { (role, content, provider) ->
                    chat += ChatLine(role, content, provider)
                }
            }
        }
        thinking += "boot: vault unlocked, providers registered"
    }

    LaunchedEffect(Unit) {
        app.tradingEngine.logs.collect { line ->
            if (tradeLogs.size > 200) tradeLogs.removeAt(0)
            tradeLogs += line
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = {
                            Text(
                                t.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = t.accent,
                            selectedTextColor = t.accent,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = t.accent.copy(alpha = 0.14f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (tab) {
                0 -> WorkspaceScreen(
                    stats = stats,
                    history = chat,
                    thinking = thinking,
                    busy = busy,
                    activeProvider = activeProvider,
                    onSend = { prompt ->
                        chat += ChatLine("user", prompt)
                        busy = true
                        scope.launch {
                            try {
                                thinking += "route: " +
                                    runCatching {
                                        app.providerRegistry.priorityOrder().joinToString(">")
                                    }.getOrDefault("—")
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        app.providerRegistry.chat(
                                            listOf(
                                                com.sqlai.agente.core.ai.ChatMessage("user", prompt)
                                            )
                                        )
                                    }.getOrElse { e ->
                                        com.sqlai.agente.core.ai.ProviderResult(
                                            provider = "local",
                                            text = "",
                                            latencyMs = 0L,
                                            fallbackUsed = false,
                                            error = e.message,
                                        )
                                    }
                                }
                                activeProvider = result.provider
                                if (result.text.isNotBlank()) {
                                    chat += ChatLine(
                                        "assistant",
                                        result.text,
                                        buildString {
                                            append(result.provider)
                                            append(" · ${result.latencyMs}ms")
                                            if (result.fallbackUsed) append(" · fallback")
                                        },
                                    )
                                    thinking += "reply from ${result.provider} in ${result.latencyMs}ms"
                                    withContext(Dispatchers.IO) {
                                        runCatching { db?.insertMessage("user", prompt, null) }
                                        runCatching { db?.insertMessage("assistant", result.text, result.provider) }
                                    }
                                } else {
                                    chat += ChatLine(
                                        "system",
                                        "All providers failed: ${result.error ?: "unknown"}",
                                    )
                                    thinking += "error: ${result.error}"
                                }
                            } catch (t: Throwable) {
                                chat += ChatLine("system", "error: ${t.message}")
                                thinking += "error: ${t.message}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onVoice = {
                        thinking += "voice: on-device STT (whisper.cpp) not loaded — type instead"
                    },
                )

                1 -> TerminalScreen(
                    output = termOut.value,
                    running = termRunning,
                    banner = termBanner,
                    onCommand = { cmd ->
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                app.linuxSubsystem.execOnce(cmd.removePrefix("!"))
                            }
                            termOut.value += "\n$ ${cmd.removePrefix("!")}\n" +
                                (r.getOrElse { e -> "error: ${e.message}" })
                        }
                    },
                    onStart = {
                        scope.launch {
                            termRunning = true
                            val r = withContext(Dispatchers.IO) { app.linuxSubsystem.bootstrap() }
                            r.onSuccess {
                                termBanner = app.linuxSubsystem.state.value.banner
                                termOut.value += "\n[sandbox] bootstrapped\n"
                            }.onFailure { e ->
                                termOut.value += "\n[sandbox] boot failed: ${e.message}\n"
                            }
                            termRunning = app.linuxSubsystem.state.value.running
                        }
                    },
                    onStop = {
                        app.linuxSubsystem.closeSession()
                        termRunning = false
                        termOut.value += "\n[sandbox] stopped\n"
                    },
                    onRefresh = {
                        scope.launch {
                            termOut.value += app.linuxSubsystem.readOutput()
                        }
                    },
                )

                2 -> TradingScreen(
                    tickers = tickers,
                    bots = bots,
                    connected = connected,
                    logs = tradeLogs,
                    onStartBot = { b -> app.tradingEngine.startBot(b) },
                    onStopBot = { id -> app.tradingEngine.stopBot(id) },
                    onRemoveBot = { id -> app.tradingEngine.removeBot(id) },
                    onConnect = { ex ->
                        val symbols = when (ex) {
                            Exchange.BINANCE -> listOf("BTCUSDT", "ETHUSDT")
                            Exchange.BITGET -> listOf("BTCUSDT", "ETHUSDT")
                            Exchange.BYBIT -> listOf("BTCUSDT", "ETHUSDT")
                            Exchange.EXNESS -> listOf("BTCUSD")
                        }
                        app.tradingEngine.connect(ex, symbols)
                    },
                )

                3 -> AutomationScreen(
                    serviceActive = a11yActive,
                    currentPackage = a11ySvc?.currentPackage ?: "",
                    nodes = a11yTree,
                    workflows = runCatching { db?.listWorkflows() }.getOrNull() ?: emptyList(),
                    logs = autoLogs,
                    onTapNode = { n ->
                        autoLogs += "tap ${n.text ?: n.viewId}"
                        a11ySvc?.tap(n.bounds.centerX.toFloat(), n.bounds.centerY.toFloat()) { ok ->
                            autoLogs += if (ok) "tap ok" else "tap failed"
                        }
                    },
                    onOpenSettings = {
                        val ctx = app.applicationContext
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure {
                                ctx.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${ctx.packageName}"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                    },
                    onRunWorkflow = { name -> autoLogs += "run workflow '$name'" },
                )

                4 -> SettingsScreen(
                    hasKey = { k ->
                        runCatching { app.vault.hasSecret(k) || app.vault.getPlain(k) != null }
                            .getOrDefault(false)
                    },
                    onSave = { k, v ->
                        runCatching {
                            if (v.isEmpty()) app.vault.deleteSecret(k)
                            else app.vault.putSecret(k, v)
                        }
                    },
                    onDelete = { k ->
                        runCatching {
                            when (k) {
                                "__PURGE__" -> db?.clearAll()
                                else -> app.vault.deleteSecret(k)
                            }
                        }
                    },
                    providerOrder = runCatching {
                        app.providerRegistry.priorityOrder().joinToString(",") { it.name }
                    }.getOrDefault(""),
                    onProviderOrderChanged = { raw ->
                        runCatching {
                            val parsed = raw.split(",")
                                .mapNotNull {
                                    runCatching { ProviderPriority.valueOf(it.trim().uppercase()) }.getOrNull()
                                }
                            if (parsed.isNotEmpty()) app.providerRegistry.setPriorityOrder(parsed)
                        }
                    },
                )
            }
        }
    }
}

/**
 * Process-wide accessibility handle is read directly from
 * [JarvisAccessibilityService.instance] so no extra holder (and no leak path) exists.
 */
