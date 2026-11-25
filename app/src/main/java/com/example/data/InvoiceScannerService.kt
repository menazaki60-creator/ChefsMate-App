package com.example.data

import android.graphics.Bitmap
import android.util.Log
import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import com.example.model.ScannedInvoiceLine
import com.example.model.UnitType
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val SCAN_TAG = "InvoiceScannerService"

class InvoiceScannerService {

    private val invoiceSchema = Schema.obj(
        properties = mapOf(
            "items" to Schema.array(
                description = "Extracted line items from the supplier invoice or ingredient label",
                items = Schema.obj(
                    properties = mapOf(
                        "rawLabel" to Schema.string(description = "Exact text printed on the invoice or label in Arabic or English"),
                        "canonicalKey" to Schema.string(description = "Lowercase snake_case unified English key (e.g. chicken, tomato, olive_oil) so Arabic دجاج and English Chicken share the same key"),
                        "nameEn" to Schema.string(description = "Clean English ingredient name (empty string if completely unidentifiable)"),
                        "nameAr" to Schema.string(description = "Arabic equivalent ingredient name (e.g. دجاج)"),
                        "category" to Schema.enumeration(
                            values = listOf("PROTEIN", "PRODUCE", "DAIRY", "DRY_GOODS", "SPICES", "BEVERAGES", "OTHER"),
                            description = "Culinary inventory category"
                        ),
                        "quantity" to Schema.double(description = "Numeric quantity purchased"),
                        "unit" to Schema.enumeration(
                            values = listOf("kg", "g", "L", "mL", "pcs"),
                            description = "Standardized unit of measure"
                        ),
                        "totalCost" to Schema.double(description = "Total line item price/cost as a positive number"),
                        "confidence" to Schema.double(description = "Identification confidence score from 0.0 to 1.0"),
                        "needsManualReview" to Schema.boolean(description = "True if ingredient is unknown, coded SKU, blurry, ambiguous, or confidence < 0.75"),
                        "reviewReason" to Schema.string(description = "Short explanation when needsManualReview is true, otherwise empty string")
                    )
                )
            )
        )
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = invoiceSchema
            }
        )
    }

    private fun buildSystemInstruction(existingInventory: List<InventoryItem>): String {
        val catalogSummary = if (existingInventory.isEmpty()) {
            "No existing inventory yet."
        } else {
            existingInventory.take(40).joinToString("\n") { item ->
                "- key=${item.canonicalKey}, en=${item.nameEn}, ar=${item.nameAr}, unit=${item.unit}, aliases=${item.aliases.joinToString(",")}"
            }
        }
        return """
            You are an expert bilingual (Arabic & English) restaurant kitchen invoice and food label parser.
            Task:
            1. Read every food ingredient line item from the provided supplier invoice photo, label photo, or raw text in Arabic, English, or mixed scripts.
            2. Unify Arabic and English names into the SAME canonicalKey and English/Arabic pair (for example: "دجاج", "صدور دجاج", "Chicken", "Fresh Chicken" MUST all map to canonicalKey="chicken", nameEn="Chicken", nameAr="دجاج").
            3. Reuse an existing canonicalKey from the kitchen's live catalog below whenever an item matches in ANY language:
            $catalogSummary
            4. Extract numeric quantity, standardized unit (kg, g, L, mL, pcs), and total line cost.
            5. CRITICAL SAFETY RULE: If a line item is an unknown supplier code (like "SKU-992", "Item X", blurry/unreadable, or ambiguous mix) or you are less than 75% sure what ingredient it is, set needsManualReview=true, confidence < 0.75, and explain clearly in reviewReason so a human operator can review it.
            Return strict JSON matching the schema.
        """.trimIndent()
    }

    suspend fun scanInvoiceBitmap(
        bitmap: Bitmap,
        existingInventory: List<InventoryItem>
    ): Result<List<ScannedInvoiceLine>> = try {
        val scaledBitmap = scaleDownIfNeeded(bitmap)
        val prompt = buildSystemInstruction(existingInventory)
        val response = withTimeout(60_000L) {
            model.generateContent(
                content {
                    image(scaledBitmap)
                    text(prompt)
                }
            )
        }
        val jsonText = response.text ?: throw IllegalStateException("AI scanner returned an empty response.")
        Result.success(parseResponse(jsonText, existingInventory))
    } catch (e: TimeoutCancellationException) {
        Log.e(SCAN_TAG, "Invoice scan timed out", e)
        Result.failure(IllegalStateException("Invoice scan timed out after 60 seconds. Please try again."))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(SCAN_TAG, "Invoice bitmap scan failed", e)
        Result.failure(e)
    }

    suspend fun scanInvoiceText(
        rawInvoiceText: String,
        existingInventory: List<InventoryItem>
    ): Result<List<ScannedInvoiceLine>> = try {
        require(rawInvoiceText.isNotBlank()) { "Please enter or paste invoice lines to scan." }
        val prompt = """
            ${buildSystemInstruction(existingInventory)}

            Invoice / Label Text to Parse:
            $rawInvoiceText
        """.trimIndent()
        val response = withTimeout(60_000L) {
            model.generateContent(prompt)
        }
        val jsonText = response.text ?: throw IllegalStateException("AI scanner returned an empty response.")
        Result.success(parseResponse(jsonText, existingInventory))
    } catch (e: TimeoutCancellationException) {
        Log.e(SCAN_TAG, "Invoice text scan timed out", e)
        Result.failure(IllegalStateException("Invoice scan timed out after 60 seconds. Please try again."))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(SCAN_TAG, "Invoice text scan failed", e)
        Result.failure(e)
    }

    private fun parseResponse(
        jsonString: String,
        existingInventory: List<InventoryItem>
    ): List<ScannedInvoiceLine> {
        val root = JSONObject(jsonString)
        val arr = root.optJSONArray("items") ?: return emptyList()
        val results = mutableListOf<ScannedInvoiceLine>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawLabel = obj.optString("rawLabel", "").trim()
            if (rawLabel.isEmpty()) continue
            val nameEn = obj.optString("nameEn", "").trim()
            val nameAr = obj.optString("nameAr", "").trim()
            val quantity = obj.optDouble("quantity", 1.0).coerceAtLeast(0.0)
            val unit = UnitType.fromSymbol(obj.optString("unit", "kg"))
            val totalCost = obj.optDouble("totalCost", 0.0).coerceAtLeast(0.0)
            val confidence = obj.optDouble("confidence", 0.5).coerceIn(0.0, 1.0)
            val aiCategory = IngredientCategory.fromRaw(obj.optString("category", "OTHER"))
            val flaggedByAi = obj.optBoolean("needsManualReview", false)
            val reviewReason = obj.optString("reviewReason", "").trim()

            // Cross-check against live inventory & bilingual matcher for guaranteed unification
            val unified = BilingualIngredientMatcher.resolve(
                rawName = nameEn.ifBlank { rawLabel },
                existingInventory = existingInventory,
                fallbackArabic = nameAr,
                fallbackCategory = aiCategory
            )

            val shouldFlag = flaggedByAi || confidence < 0.75 || (nameEn.isBlank() && unified.matchedExistingId == null)
            val finalReason = when {
                !shouldFlag -> ""
                reviewReason.isNotBlank() -> reviewReason
                confidence < 0.75 -> "Low identification confidence (${(confidence * 100).toInt()}%) — verify ingredient"
                else -> "Unknown or ambiguous ingredient label"
            }

            results.add(
                ScannedInvoiceLine(
                    rawLabel = rawLabel,
                    canonicalKey = obj.optString("canonicalKey", "").trim().ifBlank { unified.canonicalKey },
                    nameEn = unified.nameEn.ifBlank { nameEn },
                    nameAr = unified.nameAr.ifBlank { nameAr },
                    category = if (unified.matchedExistingId != null) unified.category else aiCategory,
                    quantity = quantity,
                    unit = unit,
                    totalCost = totalCost,
                    confidence = confidence,
                    needsManualReview = shouldFlag,
                    reviewReason = finalReason,
                    matchedInventoryId = unified.matchedExistingId
                )
            )
        }
        return results
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int = 1600): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDim && height <= maxDim) return bitmap
        val ratio = minOf(maxDim.toFloat() / width, maxDim.toFloat() / height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (width * ratio).toInt().coerceAtLeast(1),
            (height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
