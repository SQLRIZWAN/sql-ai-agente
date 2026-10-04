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
                        "No bots yet — launch one from the strategy builder",
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
