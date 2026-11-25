package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.model.InventoryItem
import com.example.model.LedgerEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CsvPeriod(val daysBack: Int, val filePrefix: String) {
    WEEKLY(7, "chefsmate_weekly_report"),
    MONTHLY(30, "chefsmate_monthly_report")
}

object CsvExportService {

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val fileDateFormatter = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    /**
     * Builds an RFC-4180 CSV string with UTF-8 BOM so Excel renders Arabic ingredient names cleanly.
     */
    fun buildReportCsv(
        period: CsvPeriod,
        inventory: List<InventoryItem>,
        entries: List<LedgerEntry>,
        currencySymbol: String
    ): String {
        val cutoffMillis = System.currentTimeMillis() - period.daysBack * 24L * 60L * 60L * 1000L
        val filteredEntries = entries.filter { entry ->
            val ts = entry.createdAt?.toDate()?.time ?: System.currentTimeMillis()
            ts >= cutoffMillis
        }

        val sb = StringBuilder()
        // UTF-8 BOM for proper Arabic display in Microsoft Excel & Google Sheets
        sb.append('\uFEFF')

        // Section 1: Inventory Snapshot (Cost, Waste, Net Usage)
        sb.appendLine("SECTION,ITEM_EN,ITEM_AR,CATEGORY,UNIT,GROSS_QTY,WASTE_QTY,NET_USAGE_QTY,CURRENT_STOCK,WASTE_PCT,TOTAL_COST($currencySymbol),WASTE_COST($currencySymbol),EFFECTIVE_NET_UNIT_COST($currencySymbol),MIN_STOCK,LOW_STOCK_ALERT")
        for (item in inventory) {
            sb.appendLine(
                listOf(
                    "INVENTORY_SUMMARY",
                    item.nameEn,
                    item.nameAr,
                    item.category,
                    item.unit,
                    String.format(Locale.US, "%.3f", item.grossQuantity),
                    String.format(Locale.US, "%.3f", item.wasteQuantity),
                    String.format(Locale.US, "%.3f", item.netUsableQuantity),
                    String.format(Locale.US, "%.3f", item.currentStock),
                    String.format(Locale.US, "%.1f%%", item.wastePercent),
                    String.format(Locale.US, "%.2f", item.totalCost),
                    String.format(Locale.US, "%.2f", item.wasteCost),
                    String.format(Locale.US, "%.2f", item.effectiveNetUnitCost),
                    String.format(Locale.US, "%.3f", item.minStockLevel),
                    if (item.lowStock) "LOW_STOCK" else "OK"
                ).joinToString(",") { escapeCsv(it) }
            )
        }

        sb.appendLine()
        // Section 2: Period Ledger Transactions
        sb.appendLine("TIMESTAMP,EVENT_TYPE,ITEM_EN,ITEM_AR,RAW_LABEL,CATEGORY,UNIT,GROSS_DELTA,WASTE_DELTA,COST($currencySymbol),WASTE_COST($currencySymbol),STAFF_ROLE,STAFF_NAME,HOUR_OF_DAY,REASON")
        for (entry in filteredEntries) {
            val formattedDate = dateFormatter.format(entry.createdAt?.toDate() ?: Date())
            sb.appendLine(
                listOf(
                    formattedDate,
                    entry.entryType,
                    entry.itemNameEn,
                    entry.itemNameAr,
                    entry.rawScannedLabel,
                    entry.category,
                    entry.unit,
                    String.format(Locale.US, "%.3f", entry.grossDelta),
                    String.format(Locale.US, "%.3f", entry.wasteDelta),
                    String.format(Locale.US, "%.2f", entry.costAmount),
                    String.format(Locale.US, "%.2f", entry.wasteCostAmount),
                    entry.loggedByRole,
                    entry.staffName.orEmpty(),
                    entry.hourOfDay.toString(),
                    entry.reason.orEmpty()
                ).joinToString(",") { escapeCsv(it) }
            )
        }

        return sb.toString()
    }

    fun suggestedFileName(period: CsvPeriod): String =
        "${period.filePrefix}_${fileDateFormatter.format(Date())}.csv"

    fun writeCsvToUri(context: Context, uri: Uri, csvContent: String): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(csvContent.toByteArray(Charsets.UTF_8))
            stream.flush()
        } ?: error("Unable to open destination file for CSV export.")
    }

    fun createShareIntent(context: Context, period: CsvPeriod, csvContent: String): Result<Intent> = runCatching {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, suggestedFileName(period))
        file.writeText(csvContent, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ChefsMate ${period.name.lowercase().replaceFirstChar { it.uppercase() }} Report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
