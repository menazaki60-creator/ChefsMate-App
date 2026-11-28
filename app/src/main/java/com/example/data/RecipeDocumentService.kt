package com.example.data

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.domain.PlateProfitabilityReport
import com.example.domain.RecipeCostEngine
import com.example.domain.SubRecipeCostSummary
import com.example.model.InventoryItem
import com.example.model.Recipe
import com.example.model.RecipeIngredientLine
import com.example.model.RecipeLineSource
import com.example.model.RecipeType
import com.example.model.UnitType
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val RECIPE_DOC_TAG = "RecipeDocumentService"

data class ParsedRecipeDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val nameEn: String,
    val nameAr: String = "",
    val recipeType: RecipeType = RecipeType.MENU_ITEM,
    val servings: Int = 1,
    val yieldQuantity: Double = 1000.0,
    val yieldUnit: UnitType = UnitType.G,
    val menuPrice: Double = 0.0,
    val posSku: String = "",
    val category: String = "",
    val lines: List<RecipeIngredientLine> = emptyList(),
    val matchedIngredientsCount: Int = 0,
    val unmatchedNames: List<String> = emptyList()
)

data class RecipeImportResult(
    val drafts: List<ParsedRecipeDraft>,
    val sourceLabel: String,
    val canRetryWithCloudAi: Boolean
)

data class SavedExportFile(
    val fileName: String,
    val displayLocation: String,
    val mimeType: String,
    val shareableUri: Uri
)

class RecipeDocumentService(
    private val mediaLoader: InvoiceScannerService = InvoiceScannerService()
) {
    private val fileDateFmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
    private val displayDateFmt = SimpleDateFormat("MMM dd, yyyy", Locale.US)

    // --- 1. IMPORT RECIPES FROM EXCEL (.xlsx/.csv), WORD (.docx), PDF, OR PHOTO ---

    suspend fun importRecipesLocallyFromUri(
        context: Context,
        uri: Uri,
        inventory: List<InventoryItem>,
        existingRecipes: List<Recipe>
    ): Result<RecipeImportResult> = withContext(Dispatchers.Default) {
        try {
            val fileName = LocalOcrAndDocumentParser.queryFileName(context, uri)
            val kind = LocalOcrAndDocumentParser.detectDocumentKind(context, uri)
            val fallbackTitle = fileName
                .substringBeforeLast('.')
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .ifBlank { "Imported Recipe" }

            when (kind) {
                DocumentKind.EXCEL_XLSX -> {
                    val sheets = LocalOcrAndDocumentParser.readXlsxSheets(context, uri)
                    val drafts = parseRecipesFromSheets(sheets, fallbackTitle, inventory, existingRecipes)
                    Result.success(
                        RecipeImportResult(
                            drafts = drafts,
                            sourceLabel = "Excel: $fileName",
                            canRetryWithCloudAi = false
                        )
                    )
                }

                DocumentKind.CSV_OR_TEXT -> {
                    val rows = LocalOcrAndDocumentParser.readCsvOrTextRows(context, uri)
                    val drafts = if (looksTabularSpreadsheet(rows)) {
                        parseRecipesFromSheets(listOf("CSV" to rows), fallbackTitle, inventory, existingRecipes)
                    } else {
                        val textLines = rows.map { it.joinToString(" ").trim() }.filter { it.isNotEmpty() }
                        parseRecipesFromFreeformLines(textLines, fallbackTitle, inventory, existingRecipes)
                    }
                    Result.success(
                        RecipeImportResult(
                            drafts = drafts,
                            sourceLabel = "Spreadsheet: $fileName",
                            canRetryWithCloudAi = false
                        )
                    )
                }

                DocumentKind.WORD_DOCX -> {
                    val lines = LocalOcrAndDocumentParser.readDocxLines(context, uri)
                    val drafts = parseRecipesFromFreeformLines(lines, fallbackTitle, inventory, existingRecipes)
                    Result.success(
                        RecipeImportResult(
                            drafts = drafts,
                            sourceLabel = "Word: $fileName",
                            canRetryWithCloudAi = false
                        )
                    )
                }

                DocumentKind.PDF -> {
                    val digitalLines = LocalOcrAndDocumentParser.extractDigitalPdfText(context, uri)
                    if (digitalLines.isNotEmpty()) {
                        val digitalDrafts = parseRecipesFromFreeformLines(digitalLines, fallbackTitle, inventory, existingRecipes)
                        if (digitalDrafts.isNotEmpty()) {
                            return@withContext Result.success(
                                RecipeImportResult(
                                    drafts = digitalDrafts,
                                    sourceLabel = "PDF: $fileName",
                                    canRetryWithCloudAi = false
                                )
                            )
                        }
                    }
                    val media = mediaLoader.loadMediaFromUri(context, uri, forcePdf = true).getOrThrow()
                    val ocrLines = LocalOcrAndDocumentParser.recognizeLinesLocally(media.pages)
                    val drafts = parseRecipesFromFreeformLines(ocrLines, fallbackTitle, inventory, existingRecipes)
                    Result.success(
                        RecipeImportResult(
                            drafts = drafts,
                            sourceLabel = "PDF: $fileName",
                            canRetryWithCloudAi = false
                        )
                    )
                }

                DocumentKind.IMAGE -> {
                    val media = mediaLoader.loadMediaFromUri(context, uri, forcePdf = false).getOrThrow()
                    val ocrLines = LocalOcrAndDocumentParser.recognizeLinesLocally(media.pages)
                    val drafts = parseRecipesFromFreeformLines(ocrLines, fallbackTitle, inventory, existingRecipes)
                    Result.success(
                        RecipeImportResult(
                            drafts = drafts,
                            sourceLabel = "Photo: $fileName",
                            canRetryWithCloudAi = false
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(RECIPE_DOC_TAG, "Failed to import recipes locally from $uri", e)
            Result.failure(
                IllegalStateException(
                    e.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "Could not read recipes from this file. Make sure it is a valid Excel (.xlsx/.csv), Word (.docx), PDF, or Photo."
                )
            )
        }
    }

    /**
     * Optional Cloud AI fallback for handwritten or Arabic-script recipe photos & PDFs.
     */
    suspend fun importRecipesWithCloudAiFromUri(
        context: Context,
        uri: Uri,
        inventory: List<InventoryItem>,
        existingRecipes: List<Recipe>
    ): Result<RecipeImportResult> = withContext(Dispatchers.Default) {
        try {
            val fileName = LocalOcrAndDocumentParser.queryFileName(context, uri)
            val media = mediaLoader.loadMediaFromUri(context, uri).getOrThrow()

            val recipeSchema = Schema.obj(
                properties = mapOf(
                    "recipes" to Schema.array(
                        description = "Recipes extracted from the photo or PDF",
                        items = Schema.obj(
                            properties = mapOf(
                                "nameEn" to Schema.string(description = "Recipe title in English"),
                                "nameAr" to Schema.string(description = "Arabic title if present, otherwise empty"),
                                "recipeType" to Schema.enumeration(
                                    values = listOf("MENU_ITEM", "SUB_RECIPE"),
                                    description = "SUB_RECIPE for sauces/stocks/marinades/prep batches, MENU_ITEM for selling dishes"
                                ),
                                "servings" to Schema.integer(description = "Number of servings (default 1)"),
                                "yieldQuantity" to Schema.double(description = "Total yield quantity for sub-recipes (e.g. 1000)"),
                                "yieldUnit" to Schema.enumeration(values = listOf("kg", "g", "L", "mL", "pcs")),
                                "menuPrice" to Schema.double(description = "Menu selling price if listed, otherwise 0.0"),
                                "ingredients" to Schema.array(
                                    items = Schema.obj(
                                        properties = mapOf(
                                            "name" to Schema.string(description = "Ingredient or sauce name"),
                                            "quantity" to Schema.double(description = "Numeric quantity"),
                                            "unit" to Schema.enumeration(values = listOf("kg", "g", "L", "mL", "pcs"))
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )

            val aiModel = Firebase.ai.generativeModel(
                modelName = "gemini-3.8-flash",
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                    responseSchema = recipeSchema
                }
            )

            val response = withTimeout(45_000L) {
                aiModel.generateContent(
                    content {
                        media.pages.take(3).forEach { image(it) }
                        text(
                            "Extract every recipe (dish or sauce/sub-recipe) and its ingredient quantities and units from these pages. " +
                                "Translate Arabic or French recipe titles cleanly into nameEn while keeping Arabic in nameAr. Return strict JSON."
                        )
                    }
                )
            }

            val rawJson = response.text ?: throw IllegalStateException("Cloud AI returned an empty response.")
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("recipes") ?: return@withContext Result.success(
                RecipeImportResult(emptyList(), "Cloud AI: $fileName", false)
            )

            val drafts = mutableListOf<ParsedRecipeDraft>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val nameEn = obj.optString("nameEn", "").trim()
                val nameAr = obj.optString("nameAr", "").trim()
                if (nameEn.isEmpty() && nameAr.isEmpty()) continue
                val rType = RecipeType.fromRaw(obj.optString("recipeType", "MENU_ITEM"))
                val servings = obj.optInt("servings", 1).coerceAtLeast(1)
                val yieldQty = obj.optDouble("yieldQuantity", 1000.0).takeIf { !it.isNaN() && it > 0.0 } ?: 1000.0
                val yieldUnit = UnitType.fromSymbol(obj.optString("yieldUnit", "g"))
                val menuPrice = obj.optDouble("menuPrice", 0.0).takeIf { !it.isNaN() && it >= 0.0 } ?: 0.0

                val ingArr = obj.optJSONArray("ingredients")
                val resolvedLines = mutableListOf<RecipeIngredientLine>()
                val unmatched = mutableListOf<String>()
                var matchedCount = 0

                if (ingArr != null) {
                    for (j in 0 until ingArr.length()) {
                        val ing = ingArr.optJSONObject(j) ?: continue
                        val ingName = ing.optString("name", "").trim()
                        if (ingName.isEmpty()) continue
                        val qty = ing.optDouble("quantity", 1.0).takeIf { !it.isNaN() && it > 0.0 } ?: 1.0
                        val unit = UnitType.fromSymbol(ing.optString("unit", "g"))
                        val (line, isMatched) = resolveIngredientLine(ingName, qty, unit, inventory, existingRecipes)
                        resolvedLines.add(line)
                        if (isMatched) matchedCount++ else unmatched.add(ingName)
                    }
                }

                drafts.add(
                    ParsedRecipeDraft(
                        nameEn = nameEn.ifBlank { nameAr },
                        nameAr = nameAr,
                        recipeType = rType,
                        servings = servings,
                        yieldQuantity = yieldQty,
                        yieldUnit = yieldUnit,
                        menuPrice = menuPrice,
                        lines = resolvedLines,
                        matchedIngredientsCount = matchedCount,
                        unmatchedNames = unmatched
                    )
                )
            }

            Result.success(
                RecipeImportResult(
                    drafts = drafts,
                    sourceLabel = "Cloud AI: $fileName",
                    canRetryWithCloudAi = false
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(RECIPE_DOC_TAG, "Cloud AI recipe import failed", e)
            Result.failure(
                IllegalStateException(
                    e.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "Cloud AI recipe scan failed. Try uploading an Excel, Word, or clearer PDF file."
                )
            )
        }
    }

    // --- Spreadsheet Recipe Parser (Supports ChefsMate 2-Sheet Export + General Single-Sheet Excel/CSV) ---

    private fun looksTabularSpreadsheet(rows: List<List<String>>): Boolean {
        if (rows.size < 2) return false
        val firstRow = rows.first()
        if (firstRow.size < 2) return false
        val headerJoined = firstRow.joinToString(" ").lowercase(Locale.US)
        return headerJoined.contains("recipe") ||
            headerJoined.contains("ingredient") ||
            headerJoined.contains("dish") ||
            headerJoined.contains("qty") ||
            headerJoined.contains("quantity")
    }

    private fun parseRecipesFromSheets(
        sheets: List<Pair<String, List<List<String>>>>,
        fallbackTitle: String,
        inventory: List<InventoryItem>,
        existingRecipes: List<Recipe>
    ): List<ParsedRecipeDraft> {
        val metaByName = linkedMapOf<String, MutableRecipeBuilder>()

        // Detect ChefsMate 2-sheet export or general sheets
        val summarySheet = sheets.firstOrNull { (name, rows) ->
            name.contains("summary", ignoreCase = true) ||
                rows.firstOrNull()?.any { it.contains("menu price", ignoreCase = true) || it.contains("servings", ignoreCase = true) } == true
        }
        val ingredientSheet = sheets.firstOrNull { (name, rows) ->
            name.contains("ingredient", ignoreCase = true) ||
                rows.firstOrNull()?.any { it.contains("ingredient", ignoreCase = true) } == true
        }

        // Parse summary sheet metadata first if present
        if (summarySheet != null && summarySheet != ingredientSheet) {
            val rows = summarySheet.second
            if (rows.size >= 2) {
                val headers = rows.first().map { it.trim().lowercase(Locale.US) }
                val colName = findCol(headers, "recipe name", "recipe", "dish", "name")
                val colAr = findCol(headers, "arabic", "name ar", "name_ar")
                val colType = findCol(headers, "type", "recipe type")
                val colCat = findCol(headers, "category")
                val colServings = findCol(headers, "servings", "portions")
                val colYield = findCol(headers, "batch yield", "yield qty", "yield")
                val colYieldUnit = findCol(headers, "yield unit")
                val colPrice = findCol(headers, "menu price", "selling price", "price")
                val colSku = findCol(headers, "sku", "plu")

                for (r in rows.drop(1)) {
                    val rName = r.getOrNull(colName)?.trim().orEmpty()
                    if (rName.isEmpty()) continue
                    val key = rName.lowercase(Locale.US)
                    val b = metaByName.getOrPut(key) { MutableRecipeBuilder(nameEn = rName) }
                    if (colAr >= 0) r.getOrNull(colAr)?.trim()?.takeIf { it.isNotEmpty() }?.let { b.nameAr = it }
                    if (colType >= 0) {
                        val t = r.getOrNull(colType)?.trim().orEmpty()
                        if (t.contains("sub", true) || t.contains("prep", true) || t.contains("sauce", true)) {
                            b.recipeType = RecipeType.SUB_RECIPE
                        }
                    }
                    if (colCat >= 0) r.getOrNull(colCat)?.trim()?.let { b.category = it }
                    if (colServings >= 0) {
                        val s = LocalOcrAndDocumentParser.parseNumber(r.getOrNull(colServings).orEmpty()).toInt()
                        if (s > 0) b.servings = s
                    }
                    if (colYield >= 0) {
                        val y = LocalOcrAndDocumentParser.parseNumber(r.getOrNull(colYield).orEmpty())
                        if (y > 0.0) b.yieldQuantity = y
                    }
                    if (colYieldUnit >= 0) {
                        val u = r.getOrNull(colYieldUnit)?.trim().orEmpty()
                        if (u.isNotEmpty()) b.yieldUnit = LocalOcrAndDocumentParser.parseFlexibleUnit(u)
                    }
                    if (colPrice >= 0) {
                        val p = LocalOcrAndDocumentParser.parseNumber(r.getOrNull(colPrice).orEmpty())
                        if (p > 0.0) b.menuPrice = p
                    }
                    if (colSku >= 0) r.getOrNull(colSku)?.trim()?.let { b.posSku = it }
                }
            }
        }

        // Parse ingredient rows (from dedicated ingredient sheet OR first sheet)
        val primaryRows = ingredientSheet?.second ?: sheets.firstOrNull()?.second ?: emptyList()
        if (primaryRows.isEmpty()) return emptyList()

        val headers = primaryRows.first().map { it.trim().lowercase(Locale.US) }
        val hasHeaderRow = headers.any {
            it.contains("ingredient") || it.contains("recipe") || it.contains("qty") || it.contains("quantity") || it.contains("unit")
        }
        val dataRows = if (hasHeaderRow) primaryRows.drop(1) else primaryRows

        val colRecipe = if (hasHeaderRow) findCol(headers, "recipe name", "recipe", "dish", "plate") else -1
        val colIng = if (hasHeaderRow) findCol(headers, "ingredient", "item", "material") else 0
        val colQty = if (hasHeaderRow) findCol(headers, "quantity", "qty", "amount", "weight") else 1
        val colUnit = if (hasHeaderRow) findCol(headers, "unit", "uom", "measure") else 2
        val colType = if (hasHeaderRow) findCol(headers, "type", "recipe type") else -1
        val colPrice = if (hasHeaderRow) findCol(headers, "menu price", "selling price", "price") else -1
        val colServings = if (hasHeaderRow) findCol(headers, "servings", "portions") else -1
        val colSku = if (hasHeaderRow) findCol(headers, "sku", "plu") else -1

        var lastRecipeName = fallbackTitle
        for (row in dataRows) {
            if (row.all { it.isBlank() }) continue
            val rowRecipe = if (colRecipe >= 0) row.getOrNull(colRecipe)?.trim().orEmpty() else ""
            if (rowRecipe.isNotEmpty()) lastRecipeName = rowRecipe

            val ingRaw = row.getOrNull(if (colIng >= 0) colIng else 0)?.trim().orEmpty()
            if (ingRaw.isEmpty()) continue

            val key = lastRecipeName.lowercase(Locale.US)
            val b = metaByName.getOrPut(key) { MutableRecipeBuilder(nameEn = lastRecipeName) }

            if (colType >= 0) {
                val t = row.getOrNull(colType)?.trim().orEmpty()
                if (t.contains("sub", true) || t.contains("prep", true) || t.contains("sauce", true)) {
                    b.recipeType = RecipeType.SUB_RECIPE
                }
            }
            if (colPrice >= 0 && b.menuPrice <= 0.0) {
                val p = LocalOcrAndDocumentParser.parseNumber(row.getOrNull(colPrice).orEmpty())
                if (p > 0.0) b.menuPrice = p
            }
            if (colServings >= 0 && b.servings <= 1) {
                val s = LocalOcrAndDocumentParser.parseNumber(row.getOrNull(colServings).orEmpty()).toInt()
                if (s > 0) b.servings = s
            }
            if (colSku >= 0 && b.posSku.isBlank()) {
                row.getOrNull(colSku)?.trim()?.takeIf { it.isNotEmpty() }?.let { b.posSku = it }
            }

            // Extract qty & unit (support combined "250 g" inside qty column too)
            val rawQtyCell = row.getOrNull(colQty)?.trim().orEmpty()
            val rawUnitCell = row.getOrNull(colUnit)?.trim().orEmpty()
            val (qty, unit) = parseQtyAndUnitFromCells(rawQtyCell, rawUnitCell, ingRaw)

            val (line, isMatched) = resolveIngredientLine(
                rawIngredientName = stripQtyUnitPrefix(ingRaw),
                quantity = qty,
                unit = unit,
                inventory = inventory,
                existingRecipes = existingRecipes
            )
            b.lines.add(line)
            if (isMatched) b.matchedCount++ else b.unmatchedNames.add(line.itemName)
        }

        // If spreadsheet had unstructured freeform lines (e.g. a recipe card typed in Excel cells), fallback to freeform parser
        if (metaByName.isEmpty() || metaByName.values.all { it.lines.isEmpty() }) {
            val flatLines = primaryRows.map { it.filter { c -> c.isNotBlank() }.joinToString(" ") }
                .filter { it.isNotBlank() }
            return parseRecipesFromFreeformLines(flatLines, fallbackTitle, inventory, existingRecipes)
        }

        return metaByName.values.map { it.toDraft() }
    }

    private fun findCol(headers: List<String>, vararg candidates: String): Int {
        for (cand in candidates) {
            val idx = headers.indexOfFirst { it == cand || it.contains(cand) }
            if (idx >= 0) return idx
        }
        return -1
    }

    private fun parseQtyAndUnitFromCells(qtyCell: String, unitCell: String, ingCell: String): Pair<Double, UnitType> {
        val combined = "$qtyCell $unitCell".trim()
        val match = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(kg|kilo|g|gr|gm|l|lt|liter|ml|pcs|pc|cup|tbsp|tsp|oz|lb|كغ|غ|جم|لتر|مل|حبة)?""")
            .find(LocalOcrAndDocumentParser.normalizeDigits(combined))
        val qty = match?.groupValues?.get(1)?.let { LocalOcrAndDocumentParser.parseNumber(it) }
            ?.takeIf { it > 0.0 }
            ?: 100.0
        val unitRaw = unitCell.ifBlank { match?.groupValues?.get(2).orEmpty() }
        val unit = if (unitRaw.isNotBlank()) convertCulinaryUnit(qty, unitRaw).second else UnitType.G
        val adjustedQty = if (unitRaw.isNotBlank()) convertCulinaryUnit(qty, unitRaw).first else qty
        return adjustedQty to unit
    }

    // --- Freeform Text / Word / PDF / Photo Recipe Parser ---

    private val ingredientLeadQtyRegex = Regex(
        """(?i)^\s*[-•*+]?\s*(\d+(?:[.,/]\d+)?)\s*(kg|kilo|kilos|g|gr|gm|grams|gram|l|lt|ltr|liter|litre|ml|pcs|pc|piece|pieces|tbsp|tsp|cup|cups|oz|lb|cloves?|bunch|كغ|كجم|كيلو|جم|غم|غ|لتر|مل|حبة|ملعقة)?\s+(?:of\s+)?(.+)$"""
    )

    private val ingredientTrailQtyRegex = Regex(
        """(?i)^\s*[-•*+]?\s*(.+?)\s*[:\-–|]\s*(\d+(?:[.,/]\d+)?)\s*(kg|kilo|g|gr|gm|grams|l|lt|liter|ml|pcs|pc|tbsp|tsp|cup|cups|oz|lb|كغ|كجم|جم|غم|غ|لتر|مل|حبة)\b.*$"""
    )

    private val ingredientSpaceTrailQtyRegex = Regex(
        """(?i)^\s*[-•*+]?\s*([A-Za-z\u0600-\u06FF][A-Za-z\u0600-\u06FF\s/()'-]{1,45}?)\s{2,}(\d+(?:[.,/]\d+)?)\s*(kg|kilo|g|gr|gm|l|lt|ml|pcs|pc|tbsp|tsp|cup|oz|lb|كغ|غ|جم|لتر|مل|حبة)\b.*$"""
    )

    private val servingsRegex = Regex("""(?i)(?:servings?|serves|portions?)\s*[:\-]?\s*(\d+)""")
    private val yieldRegex = Regex("""(?i)(?:yield|batch|makes?)\s*[:\-]?\s*(\d+(?:[.,]\d+)?)\s*(kg|g|l|ml|pcs|كغ|غ|لتر|مل)?""")
    private val priceRegex = Regex("""(?i)(?:menu\s*price|selling\s*price|price)\s*[:\-]?\s*\$?\s*(\d+(?:[.,]\d+)?)""")

    private fun parseRecipesFromFreeformLines(
        rawLines: List<String>,
        fallbackTitle: String,
        inventory: List<InventoryItem>,
        existingRecipes: List<Recipe>
    ): List<ParsedRecipeDraft> {
        val builders = mutableListOf<MutableRecipeBuilder>()
        var current: MutableRecipeBuilder? = null
        var inInstructionsSection = false

        for (rawOriginal in rawLines) {
            val line = LocalOcrAndDocumentParser.normalizeDigits(rawOriginal).trim()
            if (line.length < 2) continue
            val lower = line.lowercase(Locale.US)

            // Section transitions
            if (lower.startsWith("instructions") || lower.startsWith("method") ||
                lower.startsWith("directions") || lower.startsWith("steps") ||
                lower.startsWith("preparation") || lower.startsWith("طريقة التحضير")
            ) {
                inInstructionsSection = true
                continue
            }
            if (lower.startsWith("ingredients") || lower.startsWith("المكونات") || lower.startsWith("المقادير")) {
                inInstructionsSection = false
                if (current == null) current = MutableRecipeBuilder(nameEn = fallbackTitle)
                continue
            }

            // Explicit recipe title prefix (e.g. "Recipe: Chicken Shawarma", "Sub-Recipe: Garlic Toum")
            if (lower.startsWith("recipe:") || lower.startsWith("dish:") ||
                lower.startsWith("sub-recipe:") || lower.startsWith("sub recipe:") ||
                lower.startsWith("sauce:") || lower.startsWith("prep:")
            ) {
                current?.takeIf { it.lines.isNotEmpty() }?.let { builders.add(it) }
                val title = line.substringAfter(':').trim().ifBlank { fallbackTitle }
                val isSub = lower.startsWith("sub") || lower.startsWith("sauce") || lower.startsWith("prep")
                current = MutableRecipeBuilder(
                    nameEn = title,
                    recipeType = if (isSub) RecipeType.SUB_RECIPE else RecipeType.MENU_ITEM
                )
                inInstructionsSection = false
                continue
            }

            // Metadata lines (Servings / Yield / Menu Price)
            servingsRegex.find(line)?.let { m ->
                val s = m.groupValues[1].toIntOrNull()
                if (s != null && s > 0) {
                    if (current == null) current = MutableRecipeBuilder(nameEn = fallbackTitle)
                    current!!.servings = s
                }
            }
            yieldRegex.find(line)?.let { m ->
                val y = LocalOcrAndDocumentParser.parseNumber(m.groupValues[1])
                if (y > 0.0) {
                    if (current == null) current = MutableRecipeBuilder(nameEn = fallbackTitle)
                    current!!.yieldQuantity = y
                    val u = m.groupValues[2]
                    if (u.isNotBlank()) current!!.yieldUnit = LocalOcrAndDocumentParser.parseFlexibleUnit(u)
                    if (lower.contains("batch") || lower.contains("sauce") || lower.contains("yield")) {
                        current!!.recipeType = RecipeType.SUB_RECIPE
                    }
                }
            }
            priceRegex.find(line)?.let { m ->
                val p = LocalOcrAndDocumentParser.parseNumber(m.groupValues[1])
                if (p > 0.0) {
                    if (current == null) current = MutableRecipeBuilder(nameEn = fallbackTitle)
                    current!!.menuPrice = p
                }
            }
            if (servingsRegex.containsMatchIn(line) || yieldRegex.containsMatchIn(line) || priceRegex.containsMatchIn(line)) {
                if (line.length < 48) continue
            }

            if (inInstructionsSection) continue

            // Try parsing as an ingredient line
            val parsedIng = tryParseFreeformIngredientLine(line)
            if (parsedIng != null) {
                if (current == null) current = MutableRecipeBuilder(nameEn = fallbackTitle)
                val (ingName, qty, unit) = parsedIng
                val (resolvedLine, isMatched) = resolveIngredientLine(
                    rawIngredientName = ingName,
                    quantity = qty,
                    unit = unit,
                    inventory = inventory,
                    existingRecipes = existingRecipes
                )
                current!!.lines.add(resolvedLine)
                if (isMatched) current!!.matchedCount++ else current!!.unmatchedNames.add(resolvedLine.itemName)
                continue
            }

            // Otherwise, if this looks like a clean short recipe title heading
            val looksLikeHeading = line.length in 3..55 &&
                line.none { it.isDigit() } &&
                !line.contains('|') &&
                !lower.startsWith("note") &&
                !lower.startsWith("total") &&
                !lower.startsWith("cost")

            if (looksLikeHeading) {
                if (current == null) {
                    val isSub = lower.contains("sauce") || lower.contains("marinade") ||
                        lower.contains("stock") || lower.contains("dressing") || lower.contains("toum")
                    current = MutableRecipeBuilder(
                        nameEn = line.trimEnd(':'),
                        recipeType = if (isSub) RecipeType.SUB_RECIPE else RecipeType.MENU_ITEM
                    )
                } else if (current!!.lines.size >= 2) {
                    // Start a second recipe in a multi-recipe Word/PDF document!
                    builders.add(current!!)
                    val isSub = lower.contains("sauce") || lower.contains("marinade") ||
                        lower.contains("stock") || lower.contains("dressing") || lower.contains("toum")
                    current = MutableRecipeBuilder(
                        nameEn = line.trimEnd(':'),
                        recipeType = if (isSub) RecipeType.SUB_RECIPE else RecipeType.MENU_ITEM
                    )
                }
            }
        }

        current?.takeIf { it.lines.isNotEmpty() }?.let { builders.add(it) }
        return builders.map { it.toDraft() }
    }

    private fun tryParseFreeformIngredientLine(line: String): Triple<String, Double, UnitType>? {
        // Table-style pipe row from Word: "Chicken Breast | 250 | g"
        if (line.contains('|')) {
            val parts = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val p0Lower = parts[0].lowercase(Locale.US)
                if (p0Lower in setOf("ingredient", "item", "name", "material", "recipe")) return null
                val rawQty = parts.getOrNull(1).orEmpty()
                val rawUnit = parts.getOrNull(2).orEmpty()
                val num = LocalOcrAndDocumentParser.parseNumber(rawQty.replace(Regex("[^0-9.,]"), ""))
                if (num > 0.0 && parts[0].any { it.isLetter() }) {
                    val unitToken = rawUnit.ifBlank {
                        Regex("""(?i)(kg|g|gr|l|ml|pcs|tbsp|tsp|cup|oz|lb)""").find(rawQty)?.value.orEmpty()
                    }
                    val (adjQty, adjUnit) = convertCulinaryUnit(num, unitToken)
                    return Triple(parts[0], adjQty, adjUnit)
                }
            }
        }

        // Pattern 1: "Chicken Breast - 250 g" or "Garlic: 50g"
        ingredientTrailQtyRegex.find(line)?.let { m ->
            val name = m.groupValues[1].trim()
            val rawQty = parseFractionOrDecimal(m.groupValues[2])
            val unitToken = m.groupValues[3]
            if (name.length >= 2 && rawQty > 0.0) {
                val (adjQty, adjUnit) = convertCulinaryUnit(rawQty, unitToken)
                return Triple(name, adjQty, adjUnit)
            }
        }

        // Pattern 2: "Chicken Breast    250 g" (OCR columns)
        ingredientSpaceTrailQtyRegex.find(line)?.let { m ->
            val name = m.groupValues[1].trim()
            val rawQty = parseFractionOrDecimal(m.groupValues[2])
            val unitToken = m.groupValues[3]
            if (name.length >= 2 && rawQty > 0.0) {
                val (adjQty, adjUnit) = convertCulinaryUnit(rawQty, unitToken)
                return Triple(name, adjQty, adjUnit)
            }
        }

        // Pattern 3: "250g Chicken Breast" or "2 tbsp Olive Oil" or "3 Eggs"
        ingredientLeadQtyRegex.find(line)?.let { m ->
            val rawQty = parseFractionOrDecimal(m.groupValues[1])
            val unitToken = m.groupValues[2]
            val name = m.groupValues[3]
                .replace(Regex("""\s*\([^)]*\)$"""), "")
                .trim()
            if (name.length >= 2 && rawQty > 0.0 && name.any { it.isLetter() }) {
                val (adjQty, adjUnit) = if (unitToken.isNotBlank()) {
                    convertCulinaryUnit(rawQty, unitToken)
                } else {
                    rawQty to UnitType.PCS
                }
                return Triple(name, adjQty, adjUnit)
            }
        }

        return null
    }

    private fun parseFractionOrDecimal(raw: String): Double {
        if (raw.contains('/')) {
            val parts = raw.split('/')
            val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return 0.0
            val den = parts.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return 0.0
            return num / den
        }
        return LocalOcrAndDocumentParser.parseNumber(raw)
    }

    private fun convertCulinaryUnit(qty: Double, rawUnit: String): Pair<Double, UnitType> {
        val u = rawUnit.trim().lowercase(Locale.US)
        return when (u) {
            "kg", "kilo", "kilos", "kgs", "كغ", "كجم", "كيلو" -> qty to UnitType.KG
            "g", "gr", "gm", "gram", "grams", "جم", "غم", "غ" -> qty to UnitType.G
            "l", "lt", "ltr", "liter", "litre", "لتر" -> qty to UnitType.L
            "ml", "مل" -> qty to UnitType.ML
            "tbsp", "ملعقة" -> (qty * 15.0) to UnitType.ML
            "tsp" -> (qty * 5.0) to UnitType.ML
            "cup", "cups" -> (qty * 240.0) to UnitType.ML
            "oz" -> (qty * 28.35) to UnitType.G
            "lb", "lbs" -> (qty * 453.6) to UnitType.G
            else -> qty to UnitType.PCS
        }
    }

    private fun stripQtyUnitPrefix(raw: String): String {
        return raw.replace(Regex("""^\s*[-•*+]?\s*\d+(?:[.,/]\d+)?\s*(?:kg|g|ml|l|pcs)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun resolveIngredientLine(
        rawIngredientName: String,
        quantity: Double,
        unit: UnitType,
        inventory: List<InventoryItem>,
        existingRecipes: List<Recipe>
    ): Pair<RecipeIngredientLine, Boolean> {
        val norm = BilingualIngredientMatcher.normalize(rawIngredientName)

        // 1. Check existing production sub-recipes first (e.g. "Garlic Sauce")
        val matchedSub = existingRecipes.firstOrNull { sub ->
            sub.recipeTypeEnum == RecipeType.SUB_RECIPE && (
                BilingualIngredientMatcher.normalize(sub.nameEn) == norm ||
                    (sub.nameAr.isNotBlank() && BilingualIngredientMatcher.normalize(sub.nameAr) == norm)
                )
        }
        if (matchedSub != null) {
            return RecipeIngredientLine(
                itemId = matchedSub.id,
                itemName = matchedSub.nameEn,
                quantity = quantity,
                unit = unit.symbol,
                sourceType = RecipeLineSource.SUB_RECIPE.id
            ) to true
        }

        // 2. Match against live raw inventory & bilingual spell-checker
        val match = BilingualIngredientMatcher.resolve(
            rawName = rawIngredientName,
            existingInventory = inventory,
            allowAutoCorrectTypos = true
        )
        if (match.matchedExistingId != null) {
            val inv = inventory.first { it.id == match.matchedExistingId }
            return RecipeIngredientLine(
                itemId = inv.id,
                itemName = inv.nameEn,
                quantity = quantity,
                unit = unit.symbol,
                sourceType = RecipeLineSource.INVENTORY.id
            ) to true
        }

        // Unmatched yet: keep clean ingredient name with canonical key ID so chef can still import & price later
        return RecipeIngredientLine(
            itemId = "unlinked_${match.canonicalKey}",
            itemName = match.nameEn.ifBlank { rawIngredientName },
            quantity = quantity,
            unit = unit.symbol,
            sourceType = RecipeLineSource.INVENTORY.id
        ) to false
    }

    private class MutableRecipeBuilder(
        var nameEn: String,
        var nameAr: String = "",
        var recipeType: RecipeType = RecipeType.MENU_ITEM,
        var servings: Int = 1,
        var yieldQuantity: Double = 1000.0,
        var yieldUnit: UnitType = UnitType.G,
        var menuPrice: Double = 0.0,
        var posSku: String = "",
        var category: String = "",
        val lines: MutableList<RecipeIngredientLine> = mutableListOf(),
        var matchedCount: Int = 0,
        val unmatchedNames: MutableList<String> = mutableListOf()
    ) {
        fun toDraft(): ParsedRecipeDraft = ParsedRecipeDraft(
            nameEn = nameEn.trim().take(100),
            nameAr = nameAr.trim().take(100),
            recipeType = recipeType,
            servings = servings.coerceAtLeast(1),
            yieldQuantity = yieldQuantity.coerceAtLeast(1.0),
            yieldUnit = yieldUnit,
            menuPrice = menuPrice.coerceAtLeast(0.0),
            posSku = posSku.trim().take(60),
            category = category.trim().take(50),
            lines = lines.toList(),
            matchedIngredientsCount = matchedCount,
            unmatchedNames = unmatchedNames.distinct()
        )
    }

    // --- 2. EXPORT / DOWNLOAD RECIPES AS REAL NATIVE PDF (.pdf) ---

    fun suggestedPdfFileName(singleRecipeName: String? = null): String {
        val base = singleRecipeName?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]+"), "_")
            ?.trim('_')
            ?.take(24)
            ?.ifBlank { "recipes" } ?: "recipes"
        return "chefsmate_${base}_${fileDateFmt.format(Date())}.pdf"
    }

    fun suggestedXlsxFileName(singleRecipeName: String? = null): String {
        val base = singleRecipeName?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]+"), "_")
            ?.trim('_')
            ?.take(24)
            ?.ifBlank { "recipes" } ?: "recipes"
        return "chefsmate_${base}_${fileDateFmt.format(Date())}.xlsx"
    }

    fun generateRecipesPdfBytes(
        kitchenName: String,
        recipesToExport: List<Recipe>,
        allRecipes: List<Recipe>,
        inventory: List<InventoryItem>,
        recipeReports: Map<String, PlateProfitabilityReport>,
        subRecipeReports: Map<String, SubRecipeCostSummary>,
        currencySymbol: String
    ): ByteArray {
        val pdf = PdfDocument()
        val pageWidth = 595 // A4 pt
        val pageHeight = 842
        val margin = 36f
        val contentWidth = pageWidth - margin * 2

        val headerBgPaint = Paint().apply { color = Color.rgb(20, 42, 66) }
        val sectionBgPaint = Paint().apply { color = Color.rgb(236, 243, 250) }
        val cardBorderPaint = Paint().apply {
            color = Color.rgb(210, 218, 226)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val dividerPaint = Paint().apply {
            color = Color.rgb(225, 230, 236)
            strokeWidth = 0.8f
        }
        val titleWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitleWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(215, 228, 242)
            textSize = 10f
        }
        val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 42, 66)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val recipeTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 32, 42)
            textSize = 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(52, 60, 70)
            textSize = 9.5f
        }
        val boldSmallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 32, 42)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 118, 86)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(110, 120, 132)
            textSize = 8.5f
        }

        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = margin

        fun drawFooter() {
            canvas.drawText(
                "Generated by ChefsMate  •  $kitchenName  •  Page $pageNum",
                margin,
                pageHeight - 20f,
                mutedPaint
            )
        }

        fun ensureSpace(neededHeight: Float) {
            if (y + neededHeight > pageHeight - 44f) {
                drawFooter()
                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                canvas = page.canvas
                y = margin
            }
        }

        // Top Branded Header Banner
        canvas.drawRoundRect(margin, y, pageWidth - margin, y + 66f, 8f, 8f, headerBgPaint)
        canvas.drawText("ChefsMate Recipe Book", margin + 16f, y + 28f, titleWhitePaint)
        val menuList = recipesToExport.filter { it.recipeTypeEnum == RecipeType.MENU_ITEM }
        val subList = recipesToExport.filter { it.recipeTypeEnum == RecipeType.SUB_RECIPE }
        canvas.drawText(
            "$kitchenName  •  ${menuList.size} Menu Dish(es)  •  ${subList.size} Production Sub-Recipe(s)  •  ${displayDateFmt.format(Date())}",
            margin + 16f,
            y + 48f,
            subtitleWhitePaint
        )
        y += 82f

        fun drawSectionBanner(title: String) {
            ensureSpace(34f)
            canvas.drawRoundRect(margin, y, pageWidth - margin, y + 24f, 5f, 5f, sectionBgPaint)
            canvas.drawText(title, margin + 10f, y + 16f, sectionTitlePaint)
            y += 32f
        }

        if (menuList.isNotEmpty()) {
            drawSectionBanner("Menu Dishes (${menuList.size})")
            for (recipe in menuList) {
                val report = recipeReports[recipe.id]
                    ?: RecipeCostEngine.evaluateRecipe(recipe, inventory, allRecipes)
                val estHeight = 64f + (report.lineBreakdowns.size * 15f) + 18f
                ensureSpace(estHeight.coerceAtMost(280f))

                val startY = y
                val titleText = buildString {
                    append(recipe.nameEn)
                    if (recipe.nameAr.isNotBlank()) append("  (${recipe.nameAr})")
                }
                canvas.drawText(titleText.take(72), margin + 10f, y + 16f, recipeTitlePaint)
                val metaLine = buildString {
                    append("Servings: ${recipe.servings}")
                    if (!recipe.posSku.isNullOrBlank()) append("  •  POS SKU: ${recipe.posSku}")
                    if (!recipe.category.isNullOrBlank()) append("  •  ${recipe.category}")
                }
                canvas.drawText(metaLine, margin + 10f, y + 30f, mutedPaint)

                // Pricing strip
                val costStr = "Cost/Plate: $currencySymbol${fmt2(report.wasteAdjustedCostPerPlate)}"
                val minStr = "Min Price: $currencySymbol${fmt2(report.minimumSellingPrice)}"
                val menuStr = "Menu: $currencySymbol${fmt2(report.currentMenuPrice)}"
                val marginStr = "Margin: ${fmt1(report.actualProfitMarginPercent)}%"
                canvas.drawText("$costStr   |   $minStr   |   $menuStr   |   $marginStr", margin + 10f, y + 45f, accentPaint)
                y += 54f

                if (report.lineBreakdowns.isNotEmpty()) {
                    canvas.drawLine(margin + 10f, y, pageWidth - margin - 10f, y, dividerPaint)
                    y += 12f
                    canvas.drawText("Ingredient / Prep", margin + 12f, y, boldSmallPaint)
                    canvas.drawText("Quantity", margin + 290f, y, boldSmallPaint)
                    canvas.drawText("Cost", margin + 400f, y, boldSmallPaint)
                    canvas.drawText("Share", margin + 470f, y, boldSmallPaint)
                    y += 6f
                    for (bd in report.lineBreakdowns) {
                        ensureSpace(18f)
                        y += 13f
                        val prefix = if (bd.isSubRecipe) "[PREP] " else ""
                        canvas.drawText((prefix + bd.line.itemName).take(44), margin + 12f, y, bodyPaint)
                        canvas.drawText("${fmtQty(bd.line.quantity)} ${bd.line.unit}", margin + 290f, y, bodyPaint)
                        canvas.drawText("$currencySymbol${fmt2(bd.wasteAdjustedLineCost)}", margin + 400f, y, bodyPaint)
                        canvas.drawText("${fmt1(bd.shareOfPlatePercent)}%", margin + 470f, y, mutedPaint)
                    }
                }

                y += 10f
                if (y > startY + 20f) {
                    canvas.drawRoundRect(margin, startY, pageWidth - margin, y, 6f, 6f, cardBorderPaint)
                }
                y += 12f
            }
        }

        if (subList.isNotEmpty()) {
            drawSectionBanner("Production Sub-Recipes — Sauces, Stocks & Prep (${subList.size})")
            for (sub in subList) {
                val summary = subRecipeReports[sub.id]
                    ?: RecipeCostEngine.evaluateSubRecipe(sub, inventory, allRecipes)
                val estHeight = 64f + (summary.lineBreakdowns.size * 15f) + 18f
                ensureSpace(estHeight.coerceAtMost(280f))

                val startY = y
                val titleText = buildString {
                    append(sub.nameEn)
                    if (sub.nameAr.isNotBlank()) append("  (${sub.nameAr})")
                }
                canvas.drawText(titleText.take(72), margin + 10f, y + 16f, recipeTitlePaint)
                canvas.drawText(
                    "PREP BATCH (NOT FOR SALE)  •  Used in ${summary.usedInDishCount} dish(es)  •  Available On Hand: ${fmtQty(sub.productionStock)} ${summary.yieldUnit.symbol}",
                    margin + 10f,
                    y + 30f,
                    mutedPaint
                )
                canvas.drawText(
                    "Batch Yield: ${fmtQty(summary.yieldQuantity)} ${summary.yieldUnit.symbol}   |   Batch Cost: $currencySymbol${fmt2(summary.totalWasteAdjustedBatchCost)}   |   Unit Cost: $currencySymbol${fmt2(summary.costPerYieldUnit)}/${summary.yieldUnit.symbol}",
                    margin + 10f,
                    y + 45f,
                    accentPaint
                )
                y += 54f

                if (summary.lineBreakdowns.isNotEmpty()) {
                    canvas.drawLine(margin + 10f, y, pageWidth - margin - 10f, y, dividerPaint)
                    y += 12f
                    canvas.drawText("Ingredient", margin + 12f, y, boldSmallPaint)
                    canvas.drawText("Quantity", margin + 290f, y, boldSmallPaint)
                    canvas.drawText("Cost", margin + 400f, y, boldSmallPaint)
                    canvas.drawText("Share", margin + 470f, y, boldSmallPaint)
                    y += 6f
                    for (bd in summary.lineBreakdowns) {
                        ensureSpace(18f)
                        y += 13f
                        val prefix = if (bd.isSubRecipe) "[PREP] " else ""
                        canvas.drawText((prefix + bd.line.itemName).take(44), margin + 12f, y, bodyPaint)
                        canvas.drawText("${fmtQty(bd.line.quantity)} ${bd.line.unit}", margin + 290f, y, bodyPaint)
                        canvas.drawText("$currencySymbol${fmt2(bd.wasteAdjustedLineCost)}", margin + 400f, y, bodyPaint)
                        canvas.drawText("${fmt1(bd.shareOfPlatePercent)}%", margin + 470f, y, mutedPaint)
                    }
                }

                y += 10f
                if (y > startY + 20f) {
                    canvas.drawRoundRect(margin, startY, pageWidth - margin, y, 6f, 6f, cardBorderPaint)
                }
                y += 12f
            }
        }

        drawFooter()
        pdf.finishPage(page)

        val out = ByteArrayOutputStream()
        pdf.writeTo(out)
        pdf.close()
        return out.toByteArray()
    }

    // --- 3. EXPORT / DOWNLOAD RECIPES AS REAL NATIVE EXCEL WORKBOOK (.xlsx) ---

    /**
     * Builds a real, native Office Open XML (.xlsx) workbook with 2 worksheets:
     *  1. "Recipes Summary" (Menu dishes & sub-recipes with costing, pricing, margins, and production stock)
     *  2. "Recipe Ingredients" (Every ingredient line — 100% round-trip re-importable into ChefsMate!)
     */
    fun generateRecipesXlsxBytes(
        recipesToExport: List<Recipe>,
        allRecipes: List<Recipe>,
        inventory: List<InventoryItem>,
        recipeReports: Map<String, PlateProfitabilityReport>,
        subRecipeReports: Map<String, SubRecipeCostSummary>,
        currencySymbol: String
    ): ByteArray {
        val summaryRows = mutableListOf<List<XlsxCell>>()
        summaryRows.add(
            listOf(
                "Recipe Name",
                "Arabic Name",
                "Type",
                "Category",
                "Servings",
                "Batch Yield",
                "Yield Unit",
                "Available Production Stock",
                "Cost ($currencySymbol)",
                "Min Selling Price ($currencySymbol)",
                "Menu Price ($currencySymbol)",
                "Profit Margin (%)",
                "POS SKU"
            ).map { XlsxCell.Str(it, bold = true) }
        )

        val ingredientRows = mutableListOf<List<XlsxCell>>()
        ingredientRows.add(
            listOf(
                "Recipe Name",
                "Type",
                "Source",
                "Ingredient",
                "Quantity",
                "Unit",
                "Line Cost ($currencySymbol)",
                "Share (%)"
            ).map { XlsxCell.Str(it, bold = true) }
        )

        for (recipe in recipesToExport) {
            val isSub = recipe.recipeTypeEnum == RecipeType.SUB_RECIPE
            if (isSub) {
                val summary = subRecipeReports[recipe.id]
                    ?: RecipeCostEngine.evaluateSubRecipe(recipe, inventory, allRecipes)
                summaryRows.add(
                    listOf(
                        XlsxCell.Str(recipe.nameEn),
                        XlsxCell.Str(recipe.nameAr),
                        XlsxCell.Str("SUB_RECIPE"),
                        XlsxCell.Str(recipe.category.orEmpty()),
                        XlsxCell.Num(1.0),
                        XlsxCell.Num(summary.yieldQuantity),
                        XlsxCell.Str(summary.yieldUnit.symbol),
                        XlsxCell.Num(recipe.productionStock),
                        XlsxCell.Num(round2(summary.totalWasteAdjustedBatchCost)),
                        XlsxCell.Num(0.0),
                        XlsxCell.Num(0.0),
                        XlsxCell.Num(0.0),
                        XlsxCell.Str("")
                    )
                )
                for (bd in summary.lineBreakdowns) {
                    ingredientRows.add(
                        listOf(
                            XlsxCell.Str(recipe.nameEn),
                            XlsxCell.Str("SUB_RECIPE"),
                            XlsxCell.Str(if (bd.isSubRecipe) "Sub-Recipe" else "Raw Stock"),
                            XlsxCell.Str(bd.line.itemName),
                            XlsxCell.Num(bd.line.quantity),
                            XlsxCell.Str(bd.line.unit),
                            XlsxCell.Num(round2(bd.wasteAdjustedLineCost)),
                            XlsxCell.Num(round1(bd.shareOfPlatePercent))
                        )
                    )
                }
            } else {
                val report = recipeReports[recipe.id]
                    ?: RecipeCostEngine.evaluateRecipe(recipe, inventory, allRecipes)
                summaryRows.add(
                    listOf(
                        XlsxCell.Str(recipe.nameEn),
                        XlsxCell.Str(recipe.nameAr),
                        XlsxCell.Str("MENU_ITEM"),
                        XlsxCell.Str(recipe.category.orEmpty()),
                        XlsxCell.Num(recipe.servings.toDouble()),
                        XlsxCell.Num(recipe.servings.toDouble()),
                        XlsxCell.Str("pcs"),
                        XlsxCell.Num(0.0),
                        XlsxCell.Num(round2(report.wasteAdjustedCostPerPlate)),
                        XlsxCell.Num(round2(report.minimumSellingPrice)),
                        XlsxCell.Num(round2(report.currentMenuPrice)),
                        XlsxCell.Num(round1(report.actualProfitMarginPercent)),
                        XlsxCell.Str(recipe.posSku.orEmpty())
                    )
                )
                for (bd in report.lineBreakdowns) {
                    ingredientRows.add(
                        listOf(
                            XlsxCell.Str(recipe.nameEn),
                            XlsxCell.Str("MENU_ITEM"),
                            XlsxCell.Str(if (bd.isSubRecipe) "Sub-Recipe" else "Raw Stock"),
                            XlsxCell.Str(bd.line.itemName),
                            XlsxCell.Num(bd.line.quantity),
                            XlsxCell.Str(bd.line.unit),
                            XlsxCell.Num(round2(bd.wasteAdjustedLineCost)),
                            XlsxCell.Num(round1(bd.shareOfPlatePercent))
                        )
                    )
                }
            }
        }

        return buildMinimalXlsx(
            sheets = listOf(
                "Recipes Summary" to summaryRows,
                "Recipe Ingredients" to ingredientRows
            )
        )
    }

    private sealed class XlsxCell {
        data class Str(val value: String, val bold: Boolean = false) : XlsxCell()
        data class Num(val value: Double, val bold: Boolean = false) : XlsxCell()
    }

    private fun buildMinimalXlsx(sheets: List<Pair<String, List<List<XlsxCell>>>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            fun addEntry(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            val sheetOverrides = sheets.indices.joinToString("\n") { i ->
                """  <Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
            }
            addEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
$sheetOverrides
</Types>"""
            )

            addEntry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
            )

            val sheetDecls = sheets.mapIndexed { i, (name, _) ->
                """    <sheet name="${escapeXml(name.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>"""
            }.joinToString("\n")

            addEntry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
$sheetDecls
  </sheets>
</workbook>"""
            )

            val relDecls = sheets.indices.joinToString("\n") { i ->
                """  <Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>"""
            }
            addEntry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$relDecls
  <Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
            )

            // Style 0 = regular, Style 1 = bold header
            addEntry(
                "xl/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FF142A42"/><name val="Calibri"/></font>
  </fonts>
  <fills count="3">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFECF3FA"/><bgColor indexed="64"/></patternFill></fill>
  </fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
  </cellXfs>
</styleSheet>"""
            )

            sheets.forEachIndexed { idx, (_, rows) ->
                val sb = StringBuilder()
                sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                rows.forEachIndexed { rIdx, row ->
                    val rowNum = rIdx + 1
                    sb.append("""<row r="$rowNum">""")
                    row.forEachIndexed { cIdx, cell ->
                        val ref = "${colIndexToExcelLetter(cIdx)}$rowNum"
                        when (cell) {
                            is XlsxCell.Str -> {
                                val styleAttr = if (cell.bold) """ s="1"""" else ""
                                sb.append("""<c r="$ref" t="inlineStr"$styleAttr><is><t>${escapeXml(cell.value)}</t></is></c>""")
                            }
                            is XlsxCell.Num -> {
                                val styleAttr = if (cell.bold) """ s="1"""" else ""
                                val safeNum = if (cell.value.isNaN() || cell.value.isInfinite()) 0.0 else cell.value
                                sb.append("""<c r="$ref"$styleAttr><v>$safeNum</v></c>""")
                            }
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                addEntry("xl/worksheets/sheet${idx + 1}.xml", sb.toString())
            }
        }
        return baos.toByteArray()
    }

    private fun colIndexToExcelLetter(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun escapeXml(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\t') sb.append(ch)
            }
        }
        return sb.toString()
    }

    // --- 4. DIRECT DOWNLOAD (MediaStore.Downloads), SAF SAVE, OPEN & SHARE HELPERS ---

    /**
     * Saves the generated PDF or Excel (.xlsx) file directly to the device Downloads/ChefsMate
     * folder using zero-permission MediaStore.Downloads on Android 10+ (API 29+) or app Downloads
     * on API 24-28, and prepares a FileProvider URI for instant Open / Share actions.
     */
    fun saveToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<SavedExportFile> = runCatching {
        require(bytes.isNotEmpty()) { "Generated export file was empty." }

        // Always write a shareable copy into cache/exports for guaranteed FileProvider Open & Share
        val cacheExportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val cacheFile = File(cacheExportDir, fileName)
        cacheFile.writeBytes(bytes)
        val shareableUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cacheFile
        )

        val displayLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ChefsMate")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val insertedUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create file in Downloads folder.")
            try {
                resolver.openOutputStream(insertedUri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: error("Unable to write bytes to Downloads.")
                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(insertedUri, doneValues, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(insertedUri, null, null) }
                throw e
            }
            "Downloads/ChefsMate/$fileName"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheExportDir
            dir.mkdirs()
            val outFile = File(dir, fileName)
            outFile.writeBytes(bytes)
            "Downloads/$fileName"
        }

        SavedExportFile(
            fileName = fileName,
            displayLocation = displayLocation,
            mimeType = mimeType,
            shareableUri = shareableUri
        )
    }

    fun writeBytesToUri(context: Context, uri: Uri, bytes: ByteArray): Result<Unit> = runCatching {
        require(bytes.isNotEmpty()) { "Generated export file was empty." }
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(bytes)
            stream.flush()
        } ?: context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(bytes)
            stream.flush()
        } ?: error("Unable to open destination file for saving.")
    }

    fun createOpenFileIntent(
        shareableUri: Uri,
        mimeType: String
    ): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(shareableUri, mimeType)
        clipData = ClipData.newRawUri("ChefsMate Recipe Export", shareableUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun createShareChooserIntent(
        shareableUri: Uri,
        mimeType: String,
        subject: String
    ): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, shareableUri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri(subject, shareableUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share Recipes").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createShareIntent(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        subject: String
    ): Result<Intent> = runCatching {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, fileName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        createShareChooserIntent(uri, mimeType, subject)
    }

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmtQty(v: Double): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
    private fun round1(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0
}
