package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.CsvExportService
import com.example.data.CsvPeriod
import com.example.i18n.AppStrings
import com.example.ui.components.KpiTile
import com.example.ui.components.formatMoney
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.DailyTrendPoint
import com.example.viewmodel.HourlyBurnPoint
import com.example.viewmodel.KitchenUiState
import java.util.Locale

@Composable
fun DashboardScreen(
    uiState: KitchenUiState,
    strings: AppStrings,
    isExpanded: Boolean,
    onGenerateCsv: (CsvPeriod) -> String,
    onCsvFeedback: (String) -> Unit
) {
    val context = LocalContext.current
    val currency = uiState.profile?.currencySymbol ?: "$"

    var pendingCsvContent by remember { mutableStateOf("") }

    val saveCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null && pendingCsvContent.isNotEmpty()) {
            CsvExportService.writeCsvToUri(context, uri, pendingCsvContent)
                .onSuccess { onCsvFeedback("CSV report saved successfully") }
                .onFailure { onCsvFeedback(it.localizedMessage ?: "Failed to save CSV") }
        }
    }

    if (!uiState.activePermissions.canViewReports) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = strings.managerOnlyLocked,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = strings.managerOnlyLocked,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Your manager-assigned role (${uiState.activePermissions.role.name.replace('_', ' ')}) does not include Financial Reports or CSV export access. Ask your group manager if you need access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    val topWasteItems = remember(uiState.inventory) {
        uiState.inventory
            .filter { it.wasteCost > 0.0 }
            .sortedByDescending { it.wasteCost }
            .take(5)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "dash_header") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = strings.insightsTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Cost efficiency, waste trends & margin overview",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 4 KPI Cards (2x2 on phone, 4x1 on tablet/desktop)
        item(key = "dash_kpis") {
            if (isExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KpiTile(
                        label = "Stock Value",
                        value = formatMoney(uiState.totalInventoryValue, currency),
                        accent = MaterialTheme.colorScheme.primary,
                        icon = Icons.Default.AttachMoney,
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        label = "Waste Cost",
                        value = formatMoney(uiState.totalWasteCost, currency),
                        accent = MaterialTheme.colorScheme.error,
                        icon = Icons.Default.TrendingDown,
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        label = strings.netUsage,
                        value = "${String.format(Locale.US, "%.1f", uiState.overallNetYieldPct)}%",
                        accent = MaterialTheme.colorScheme.secondary,
                        icon = Icons.Default.Verified,
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        label = "Avg Margin",
                        value = "${String.format(Locale.US, "%.1f", uiState.averageRecipeMarginPct)}%",
                        accent = MaterialTheme.colorScheme.tertiary,
                        icon = Icons.Default.TrendingUp,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        KpiTile(
                            label = "Stock Value",
                            value = formatMoney(uiState.totalInventoryValue, currency),
                            accent = MaterialTheme.colorScheme.primary,
                            icon = Icons.Default.AttachMoney,
                            modifier = Modifier.weight(1f)
                        )
                        KpiTile(
                            label = "Waste Cost",
                            value = formatMoney(uiState.totalWasteCost, currency),
                            accent = MaterialTheme.colorScheme.error,
                            icon = Icons.Default.TrendingDown,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        KpiTile(
                            label = strings.netUsage,
                            value = "${String.format(Locale.US, "%.1f", uiState.overallNetYieldPct)}%",
                            accent = MaterialTheme.colorScheme.secondary,
                            icon = Icons.Default.Verified,
                            modifier = Modifier.weight(1f)
                        )
                        KpiTile(
                            label = "Avg Margin",
                            value = "${String.format(Locale.US, "%.1f", uiState.averageRecipeMarginPct)}%",
                            accent = MaterialTheme.colorScheme.tertiary,
                            icon = Icons.Default.TrendingUp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Weekly & Monthly CSV Exports Card
        if (uiState.activePermissions.canExportCsv) {
            item(key = "csv_export_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "CSV Reports (Excel UTF-8 Bilingual Ready)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        // Weekly CSV row
                        CsvActionRow(
                            title = strings.exportWeeklyCsv,
                            saveLabel = strings.saveCsv,
                            shareLabel = strings.shareCsv,
                            testTagPrefix = "weekly_csv",
                            onSave = {
                                pendingCsvContent = onGenerateCsv(CsvPeriod.WEEKLY)
                                saveCsvLauncher.launch(CsvExportService.suggestedFileName(CsvPeriod.WEEKLY))
                            },
                            onShare = {
                                val csv = onGenerateCsv(CsvPeriod.WEEKLY)
                                CsvExportService.createShareIntent(context, CsvPeriod.WEEKLY, csv)
                                    .onSuccess { intent -> context.startActivity(android.content.Intent.createChooser(intent, strings.exportWeeklyCsv)) }
                                    .onFailure { onCsvFeedback(it.localizedMessage ?: "Share failed") }
                            }
                        )
                        // Monthly CSV row
                        CsvActionRow(
                            title = strings.exportMonthlyCsv,
                            saveLabel = strings.saveCsv,
                            shareLabel = strings.shareCsv,
                            testTagPrefix = "monthly_csv",
                            onSave = {
                                pendingCsvContent = onGenerateCsv(CsvPeriod.MONTHLY)
                                saveCsvLauncher.launch(CsvExportService.suggestedFileName(CsvPeriod.MONTHLY))
                            },
                            onShare = {
                                val csv = onGenerateCsv(CsvPeriod.MONTHLY)
                                CsvExportService.createShareIntent(context, CsvPeriod.MONTHLY, csv)
                                    .onSuccess { intent -> context.startActivity(android.content.Intent.createChooser(intent, strings.exportMonthlyCsv)) }
                                    .onFailure { onCsvFeedback(it.localizedMessage ?: "Share failed") }
                            }
                        )
                    }
                }
            }
        }

        // 7-Day Cost vs Waste Trend Chart
        item(key = "trend_chart") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.costVsWasteTrend,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LegendDot(color = MaterialTheme.colorScheme.secondary, label = "Cost")
                            LegendDot(color = MaterialTheme.colorScheme.error, label = "Waste")
                        }
                    }
                    CostWasteBarChart(
                        points = uiState.dailyTrends,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                    )
                }
            }
        }

        // Top Waste Cost Drivers
        item(key = "top_waste_offenders") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = strings.topWasteOffenders,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (topWasteItems.isEmpty()) {
                        Text(
                            text = "Zero waste logged so far — great kitchen control!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        val maxWaste = topWasteItems.maxOf { it.wasteCost }.coerceAtLeast(1.0)
                        topWasteItems.forEach { item ->
                            val frac = (item.wasteCost / maxWaste).toFloat().coerceIn(0.05f, 1f)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${item.nameEn}${if (item.nameAr.isNotBlank()) " • ${item.nameAr}" else ""}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${formatMoney(item.wasteCost, currency)} (${String.format(Locale.US, "%.1f", item.wastePercent)}%)",
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(frac)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(MaterialTheme.colorScheme.error)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Peak Hours Inventory Burn Monitor
        item(key = "peak_hours_burn") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = strings.peakHourBurn,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = strings.peakHourBurn,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    PeakHoursChart(
                        points = uiState.hourlyBurn,
                        currency = currency
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun CsvActionRow(
    title: String,
    saveLabel: String,
    shareLabel: String,
    testTagPrefix: String,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onSave,
                modifier = Modifier.testTag("${testTagPrefix}_save_btn")
            ) {
                Icon(Icons.Default.Download, contentDescription = saveLabel, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(saveLabel)
            }
            Button(
                onClick = onShare,
                modifier = Modifier.testTag("${testTagPrefix}_share_btn")
            ) {
                Icon(Icons.Default.Share, contentDescription = shareLabel, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(shareLabel)
            }
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CostWasteBarChart(
    points: List<DailyTrendPoint>,
    modifier: Modifier = Modifier
) {
    val costColor = MaterialTheme.colorScheme.secondary
    val wasteColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (points.isEmpty()) return@Canvas
            val maxVal = points.maxOf { maxOf(it.costAmount, it.wasteCostAmount) }.coerceAtLeast(10.0)
            val slotWidth = size.width / points.size
            val barWidth = (slotWidth * 0.28f).coerceAtLeast(6f)
            val bottomY = size.height - 6f

            // Subtle baseline
            drawLine(
                color = gridColor,
                start = Offset(0f, bottomY),
                end = Offset(size.width, bottomY),
                strokeWidth = 2f
            )

            points.forEachIndexed { idx, pt ->
                val centerX = slotWidth * idx + slotWidth / 2f
                val costHeight = ((pt.costAmount / maxVal) * (bottomY - 8f)).toFloat().coerceAtLeast(if (pt.costAmount > 0) 6f else 2f)
                val wasteHeight = ((pt.wasteCostAmount / maxVal) * (bottomY - 8f)).toFloat().coerceAtLeast(if (pt.wasteCostAmount > 0) 6f else 2f)

                drawRoundRect(
                    color = costColor,
                    topLeft = Offset(centerX - barWidth - 3f, bottomY - costHeight),
                    size = Size(barWidth, costHeight),
                    cornerRadius = CornerRadius(6f, 6f)
                )
                drawRoundRect(
                    color = wasteColor,
                    topLeft = Offset(centerX + 3f, bottomY - wasteHeight),
                    size = Size(barWidth, wasteHeight),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            points.forEach { pt ->
                Text(
                    text = pt.dayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PeakHoursChart(
    points: List<HourlyBurnPoint>,
    currency: String
) {
    val maxBurn = points.maxOfOrNull { it.totalUsageCost + it.wasteCost }?.coerceAtLeast(1.0) ?: 1.0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        points.forEach { pt ->
            val intensity = ((pt.totalUsageCost + pt.wasteCost) / maxBurn).toFloat().coerceIn(0.08f, 1f)
            val barColor = if (pt.wasteCost > pt.totalUsageCost * 0.25 && pt.wasteCost > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height((60 * intensity).dp)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(barColor.copy(alpha = 0.85f))
                    )
                }
                Text(
                    text = pt.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatMoney(pt.totalUsageCost + pt.wasteCost, currency),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    maxLines = 1
                )
            }
        }
    }
}
