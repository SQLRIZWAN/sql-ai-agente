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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sqlai.agente.core.trading.BotState
import com.sqlai.agente.core.trading.Exchange
import com.sqlai.agente.core.trading.Ticker
import com.sqlai.agente.ui.components.MetricTile
import com.sqlai.agente.ui.components.SectionHeader
import com.sqlai.agente.ui.theme.DeepPanel
import com.sqlai.agente.ui.theme.LossRed
import com.sqlai.agente.ui.theme.NeonAmber
import com.sqlai.agente.ui.theme.NeonCyan
import com.sqlai.agente.ui.theme.NeonLime
import com.sqlai.agente.ui.theme.NeonViolet
import com.sqlai.agente.ui.theme.ProfitGreen
import com.sqlai.agente.ui.theme.RaisedPanel
import com.sqlai.agente.ui.theme.StrokeDim
import com.sqlai.agente.ui.theme.TextMuted
import com.sqlai.agente.ui.theme.TextSecondary
import java.util.Locale

/**
 * Tab 3 — Trading Dashboard.
 * Live tickers, connected exchanges, running bots with their last signal,
 * position PnL and a quick strategy launcher.
 */
@Composable
fun TradingScreen(
    tickers: Map<String, Ticker>,
    bots: List<BotState>,
    connected: Set<Exchange>,
    logs: List<String>,
    mode: com.sqlai.agente.core.trading.TradeMode = com.sqlai.agente.core.trading.TradeMode.PAPER,
    balance: Double = 10_000.0,
    goldSymbols: List<String> = emptyList(),
    onSetMode: (com.sqlai.agente.core.trading.TradeMode) -> Unit = {},
    onCreateBot: (name: String, exchange: Exchange, symbol: String, riskPct: Double) -> Unit = { _, _, _, _ -> },
    onStartBot: (BotState) -> Unit,
    onStopBot: (String) -> Unit,
    onRemoveBot: (String) -> Unit,
    onConnect: (Exchange) -> Unit,
) {
    val totalPnl = bots.sumOf { it.pnlToday }
    val openPositions = bots.sumOf { it.positions.size }
    val activeBots = bots.count { it.running }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Spacer10() }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile("PNL today", fmtMoney(totalPnl), if (totalPnl >= 0) ProfitGreen else LossRed, Modifier.weight(1f))
                MetricTile("open pos", "$openPositions", NeonCyan, Modifier.weight(1f))
                MetricTile("bots live", "$activeBots/${bots.size}", NeonViolet, Modifier.weight(1f))
            }
        }

        // --------------------------- paper / live mode ---------------------------
        item {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DeepPanel,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (mode == com.sqlai.agente.core.trading.TradeMode.LIVE) LossRed.copy(alpha = 0.7f)
                    else NeonLime.copy(alpha = 0.5f),
                ),
            ) {
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Trading mode",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (mode == com.sqlai.agente.core.trading.TradeMode.PAPER)
                                "virtual $" + String.format(Locale.US, "%,.2f", balance)
                            else "REAL ORDERS",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (mode == com.sqlai.agente.core.trading.TradeMode.PAPER) NeonLime else LossRed,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ModeChip(
                            "PAPER · demo (safe)",
                            mode == com.sqlai.agente.core.trading.TradeMode.PAPER,
                        ) { onSetMode(com.sqlai.agente.core.trading.TradeMode.PAPER) }
                        ModeChip(
                            "LIVE · real money",
                            mode == com.sqlai.agente.core.trading.TradeMode.LIVE,
                        ) { onSetMode(com.sqlai.agente.core.trading.TradeMode.LIVE) }
                    }
                    Text(
                        "PAPER simulates fills on a $10,000 virtual balance — test here first. " +
                            "LIVE signs real orders on the exchange (keys must be set in Vault).",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        }

        item {
            SectionHeader("Exchanges", NeonCyan)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Exchange.entries) { ex ->
                    val on = ex in connected
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (on) NeonCyan.copy(alpha = 0.14f) else DeepPanel,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (on) NeonCyan else StrokeDim,
                        ),
                        modifier = Modifier.clickable { if (!on) onConnect(ex) },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (on) NeonLime else TextMuted)
                            )
                            Text(
                                ex.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (on) NeonCyan else TextSecondary,
                            )
                        }
                    }
                }
            }
            if (com.sqlai.agente.core.trading.Exchange.BITGET in connected && goldSymbols.isNotEmpty()) {
                Text(
                    "gold found: ${goldSymbols.joinToString()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonAmber,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        item {
            SectionHeader("Live tickers", NeonAmber)
            if (tickers.isEmpty()) {
                Text(
                    "connect an exchange to stream prices",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
        }
        items(tickers.values.take(8).toList()) { t -> TickerRow(t) }

        item { BotBuilder(goldSymbols, connected, onCreateBot) }

        item {
            SectionHeader("Strategy bots", NeonViolet)
        }
        items(bots) { b ->
            BotCard(b, onStop = { onStopBot(b.id) }, onRemove = { onRemoveBot(b.id) })
        }
        if (bots.isEmpty()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DeepPanel)
                        .border(1.dp, StrokeDim, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = NeonViolet)
                    Text(
                        "No bots yet — build one above (name → symbol → Create & Start)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item { SectionHeader("Engine log", NeonLime) }
        items(logs.takeLast(10).asReversed()) { l ->
            Text(
                l,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
            )
        }
        item { Spacer10() }
    }
}

@Composable
private fun TickerRow(t: Ticker) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DeepPanel)
            .border(1.dp, StrokeDim, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            t.symbol,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(
            t.price.toDisplay(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Box(Modifier.size(8.dp))
        Text(
            String.format(Locale.US, "%+.2f%%", t.change24h),
            style = MaterialTheme.typography.labelSmall,
            color = if (t.change24h >= 0) ProfitGreen else LossRed,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun BotCard(b: BotState, onStop: () -> Unit, onRemove: () -> Unit) {
    val signal = b.lastSignal
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DeepPanel,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (b.running) NeonViolet.copy(alpha = 0.55f) else StrokeDim,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (b.running) NeonLime else TextMuted)
                )
                Spacer8()
                Text(
                    b.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    b.strategy,
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonCyan,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer8()
                IconButton(
                    onClick = if (b.running) onStop else onStop,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        if (b.running) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (b.running) "Stop" else "Start",
                        tint = if (b.running) NeonAmber else NeonLime,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LabelValue("exchange", b.exchange.label)
                LabelValue("symbol", b.symbol)
                LabelValue("risk", "${b.riskPct}%")
                LabelValue("trades", "${b.tradeCount}")
                LabelValue(
                    "pnl",
                    String.format(Locale.US, "%+.2f", b.pnlToday),
                    if (b.pnlToday >= 0) ProfitGreen else LossRed,
                )
            }
            if (signal != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(RaisedPanel)
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        signal.side.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (signal.side) {
                            com.sqlai.agente.core.trading.SignalSide.LONG -> ProfitGreen
                            com.sqlai.agente.core.trading.SignalSide.SHORT -> LossRed
                            com.sqlai.agente.core.trading.SignalSide.FLAT -> TextMuted
                        },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        String.format(Locale.US, "%.0f%%", signal.strength * 100),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonAmber,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        signal.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            b.positions.forEach { p ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${p.side} ${p.symbol}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        String.format(Locale.US, "entry %.4f  pnl %+.4f (%.2f%%)",
                            p.entryPrice, p.pnl, p.pnlPercent),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (p.pnl >= 0) ProfitGreen else LossRed,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) NeonCyan.copy(alpha = 0.20f) else RaisedPanel)
            .border(
                1.dp,
                if (selected) NeonCyan else StrokeDim,
                RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) NeonCyan else TextSecondary,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Bot builder: name → exchange → symbol (gold chips appear after Bitget connect
 * discovers them) → risk → Create & Start. Every field has a sane default so a
 * single tap on "Create & Start" launches a working bot.
 */
@Composable
private fun BotBuilder(
    goldSymbols: List<String>,
    connected: Set<Exchange>,
    onCreateBot: (String, Exchange, String, Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var risk by remember { mutableStateOf("1") }
    var exchange by remember { mutableStateOf(Exchange.BITGET) }

    val suggestions = (
        goldSymbols + listOf("BTCUSDT", "ETHUSDT", "PAXGUSDT", "XAUUSDT")
        ).distinct()

    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DeepPanel,
        border = androidx.compose.foundation.BorderStroke(1.dp, NeonViolet.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Bot builder",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Bitget", exchange == Exchange.BITGET) { exchange = Exchange.BITGET }
                ModeChip("Binance", exchange == Exchange.BINANCE) { exchange = Exchange.BINANCE }
                ModeChip("Bybit", exchange == Exchange.BYBIT) { exchange = Exchange.BYBIT }
            }

            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        if (goldSymbols.isNotEmpty()) goldSymbols.first() else "BTCUSDT",
                        color = TextMuted,
                    )
                },
                label = { Text("Symbol (gold / crypto)", color = TextMuted) },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = FontFamily.Monospace,
                ),
                colors = builderFieldColors(),
            )

            // Quick-pick chips — gold chips show first (discovered on connect).
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.take(5).forEach { s ->
                    ModeChip(s, symbol == s) { symbol = s }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (symbol.contains("XAU") || symbol.contains("PAXG")) "Gold bot" else "My bot", color = TextMuted) },
                    label = { Text("Bot name", color = TextMuted) },
                    singleLine = true,
                    colors = builderFieldColors(),
                )
                OutlinedTextField(
                    value = risk,
                    onValueChange = { risk = it },
                    modifier = Modifier.width(90.dp),
                    placeholder = { Text("1", color = TextMuted) },
                    label = { Text("risk %", color = TextMuted) },
                    singleLine = true,
                    colors = builderFieldColors(),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill("Create & Start") {
                    val sym = symbol.trim().ifEmpty {
                        goldSymbols.firstOrNull() ?: "BTCUSDT"
                    }
                    val nm = name.trim().ifEmpty {
                        when {
                            sym.contains("XAU", true) || sym.contains("PAXG", true) -> "Gold bot"
                            else -> "$sym bot"
                        }
                    }
                    val riskPct = risk.trim().toDoubleOrNull()?.coerceIn(0.1, 10.0) ?: 1.0
                    onCreateBot(nm, exchange, sym, riskPct)
                    name = ""; symbol = ""; risk = "1"
                }
                Text(
                    if (connected.isEmpty()) "connect an exchange first (above)"
                    else "strategy: RSI + MACD + EMA cross · SL/TP gates",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(NeonLime.copy(alpha = 0.16f))
            .border(1.dp, NeonLime.copy(alpha = 0.55f), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = NeonLime)
    }
}

@Composable
private fun builderFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NeonViolet,
    unfocusedBorderColor = StrokeDim,
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    cursorColor = NeonViolet,
    focusedContainerColor = RaisedPanel,
    unfocusedContainerColor = RaisedPanel,
)

@Composable
private fun LabelValue(label: String, value: String, color: Color = TextSecondary) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 9.sp)
        Text(value, style = MaterialTheme.typography.labelSmall, color = color, fontFamily = FontFamily.Monospace)
    }
}

private fun Double.toDisplay(): String = when {
    this >= 1000 -> String.format(Locale.US, "%,.2f", this)
    this >= 1 -> String.format(Locale.US, "%.4f", this)
    else -> String.format(Locale.US, "%.6f", this)
}

fun fmtMoney(v: Double): String = String.format(Locale.US, "%+.2f", v)

@Composable
private fun Spacer10() = Box(Modifier.size(4.dp))

@Composable
private fun Spacer8() = Box(Modifier.size(6.dp))
