package com.sqlai.agente.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sqlai.agente.core.monitor.SystemStats
import com.sqlai.agente.ui.theme.DeepPanel
import com.sqlai.agente.ui.theme.NeonAmber
import com.sqlai.agente.ui.theme.NeonCyan
import com.sqlai.agente.ui.theme.NeonRose
import com.sqlai.agente.ui.theme.NeonViolet
import com.sqlai.agente.ui.theme.RaisedPanel
import com.sqlai.agente.ui.theme.StrokeDim
import com.sqlai.agente.ui.theme.TextMuted
import com.sqlai.agente.ui.theme.TextSecondary

/** Glowing metric tile used across the dashboard. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = DeepPanel,
        border = androidx.compose.foundation.BorderStroke(1.dp, StrokeDim),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            Text(
                value,
                color = accent,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
        }
    }
}

/** Compact horizontal gauge with a glow bar. */
@Composable
fun GaugeRow(label: String, fraction: Float, accent: Color) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "gauge",
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(
                "${(animated * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(RaisedPanel)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(listOf(accent.copy(alpha = 0.6f), accent))
                    )
            )
        }
    }
}

/** Circular CPU ring for the system monitor header. */
@Composable
fun CpuRing(percent: Int, modifier: Modifier = Modifier) {
    val sweep by animateFloatAsState(
        targetValue = (percent.coerceIn(0, 100)) * 3.6f,
        animationSpec = tween(700),
        label = "cpuRing",
    )
    val color = when {
        percent >= 85 -> NeonRose
        percent >= 60 -> NeonAmber
        else -> NeonCyan
    }
    Box(modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(72.dp)) {
            val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = StrokeDim,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = stroke,
                topLeft = Offset(0f, 0f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$percent%",
                color = color,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Text("CPU", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Live system monitor strip shown at the top of the workspace. */
@Composable
fun SystemMonitorPanel(stats: SystemStats, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = DeepPanel,
        border = androidx.compose.foundation.BorderStroke(1.dp, StrokeDim),
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CpuRing(stats.cpuPercent)
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GaugeRow("RAM", stats.ramPercent / 100f, NeonViolet)
                GaugeRow(
                    "PSS",
                    if (stats.totalRamBytes > 0) stats.appPssBytes.toFloat() / stats.totalRamBytes else 0f,
                    NeonAmber,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatChip("threads", "${stats.threadCount}", TextSecondary)
                    StatChip("bots", "${stats.activeBots}", NeonCyan)
                    StatChip(
                        "model",
                        stats.loadedModel?.take(12) ?: "—",
                        if (stats.loadedModel != null) NeonViolet else TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(value, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}

/** Section header with a neon rule. */
@Composable
fun SectionHeader(title: String, accent: Color = NeonCyan) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(4.dp, 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 1.5.sp,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(StrokeDim)
        )
    }
}

@Composable
fun ThinBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
        color = color,
        trackColor = RaisedPanel,
        strokeCap = StrokeCap.Round,
    )
}
