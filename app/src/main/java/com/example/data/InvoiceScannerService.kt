package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

private const val SCAN_TAG = "InvoiceScannerService"
private const val MAX_IMAGE_DIM = 960
private const val MAX_PDF_PAGES = 2
private const val MAX_PDF_BYTES = 18 * 1024 * 1024L // 18MB guard

data class LoadedInvoiceMedia(
    val pages: List<Bitmap>,
    val previewThumbnail: Bitmap,
    val isPdf: Boolean
)

data class LocalInvoiceScanOutput(
    val lines: List<ScannedInvoiceLine>,
    val previewThumbnail: Bitmap?,
    val rawExtractedText: String,
    val elapsedMs: Long,
    val canRetryWithCloudAi: Boolean
)

class InvoiceScannerService {

    private val invoiceSchema = Schema.obj(
        properties = mapOf(
            "items" to Schema.array(
                description = "Extracted line items from the supplier invoice, receipt, or ingredient label",
                items = Schema.obj(
                    properties = mapOf(
                        "rawLabel" to Schema.string(description = "Exact text printed or written on the invoice or label in Arabic, English, or French"),
                        "canonicalKey" to Schema.string(description = "Lowercase snake_case unified English key (e.g. chicken, tomato, olive_oil) so Arabic دجاج and English Chicken share the same key"),
                        "nameEn" to Schema.string(description = "Clean English ingredient name (empty string if completely unidentifiable)"),
                        "nameAr" to Schema.string(description = "Arabic equivalent ingredient name (e.g. دجاج)"),
                        "category" to Schema.enumeration(
                            values = listOf("PROTEIN", "PRODUCE", "DAIRY", "DRY_GOODS", "SPICES", "BEVERAGES", "OTHER"),
                            description = "Culinary inventory category"
                        ),
                        "quantity" to Schema.double(description = "Numeric quantity purchased (default 1.0 if omitted)"),
                        "unit" to Schema.enumeration(
                            values = listOf("kg", "g", "L", "mL", "pcs"),
                            description = "Standardized unit of measure"
                        ),
                        "totalCost" to Schema.double(description = "Total line item price/cost as a non-negative number (compute qty * unit price if only unit price is shown; 0.0 if missing)"),
                        "confidence" to Schema.double(description = "Identification confidence score from 0.0 to 1.0"),
                        "needsManualReview" to Schema.boolean(description = "True if ingredient is unknown, coded SKU, blurry, ambiguous, missing price, or confidence < 0.75"),
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

    /**
     * FAST ON-DEVICE LOCAL INVOICE SCANNER (< 1 second, works offline!).
     * Supports Camera/Gallery Photos, PDFs (via PdfRenderer + ML Kit OCR),
     * Excel (.xlsx / .csv), and Word (.docx) invoices with zero network round-trip.
     */
    suspend fun scanInvoiceLocallyFromUri(
        context: Context,
        uri: Uri,
        existingInventory: List<InventoryItem>
    ): Result<LocalInvoiceScanOutput> = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()
        try {
            val kind = LocalOcrAndDocumentParser.detectDocumentKind(context, uri)
            when (kind) {
                DocumentKind.EXCEL_XLSX -> {
                    val sheets = LocalOcrAndDocumentParser.readXlsxSheets(context, uri)
                    val rowLines = sheets.flatMap { it.second }
                        .map { row -> row.filter { it.isNotBlank() }.joinToString("  ") }
                        .filter { it.isNotBlank() }
                    val parsed = LocalOcrAndDocumentParser.parseInvoiceLinesLocally(rowLines, existingInventory)
                    Result.success(
                        LocalInvoiceScanOutput(
                            lines = parsed,
                            previewThumbnail = null,
                            rawExtractedText = rowLines.joinToString("\n"),
                            elapsedMs = System.currentTimeMillis() - startMs,
                            canRetryWithCloudAi = false
                        )
                    )
                }

                DocumentKind.WORD_DOCX -> {
                    val docLines = LocalOcrAndDocumentParser.readDocxLines(context, uri)
                    val parsed = LocalOcrAndDocumentParser.parseInvoiceLinesLocally(docLines, existingInventory)
                    Result.success(
                        LocalInvoiceScanOutput(
                            lines = parsed,
                            previewThumbnail = null,
                            rawExtractedText = docLines.joinToString("\n"),
                            elapsedMs = System.currentTimeMillis() - startMs,
                            canRetryWithCloudAi = false
                        )
                    )
                }

                DocumentKind.CSV_OR_TEXT -> {
                    val rows = LocalOcrAndDocumentParser.readCsvOrTextRows(context, uri)
                    val rowLines = rows.map { row -> row.filter { it.isNotBlank() }.joinToString("  ") }
                        .filter { it.isNotBlank() }
                    val parsed = LocalOcrAndDocumentParser.parseInvoiceLinesLocally(rowLines, existingInventory)
                    Result.success(
                        LocalInvoiceScanOutput(
                            lines = parsed,
                            previewThumbnail = null,
                            rawExtractedText = rowLines.joinToString("\n"),
                            elapsedMs = System.currentTimeMillis() - startMs,
                            canRetryWithCloudAi = false
                        )
                    )
                }

                DocumentKind.PDF -> {
                    // Instant digital PDF text extraction (<25ms, zero bitmap rendering)
                    val digitalLines = LocalOcrAndDocumentParser.extractDigitalPdfText(context, uri)
                    if (digitalLines.isNotEmpty()) {
                        val parsedDigital = LocalOcrAndDocumentParser.parseInvoiceLinesLocally(digitalLines, existingInventory)
                        if (parsedDigital.isNotEmpty()) {
                            return@withContext Result.success(
                                LocalInvoiceScanOutput(
                                    lines = parsedDigital,
                                    previewThumbnail = null,
                                    rawExtractedText = digitalLines.joinToString("\n"),
                                    elapsedMs = System.currentTimeMillis() - startMs,
                                    canRetryWithCloudAi = false
                                )
                            )
                        }
                    }
                    // Fallback to fast local PdfRenderer + ML Kit OCR for scanned image PDFs
                    val media = loadMediaFromUri(context, uri, forcePdf = true).getOrThrow()
                    val ocrLines = LocalOcrAndDocumentParser.recognizeLinesLocally(media.pages)
                    val parsed = LocalOcrAndDocumentParser.parseInvoiceLinesLocally(ocrLines, existingInventory)
                    Result.success(
                        LocalInvoiceScanOutput(
                            lines = parsed,
                            previewThumbnail = media.previewThumbnail,
                            rawExtractedText = ocrLines.joinToString("\n"),
                            elapsedMs = System.currentTimeMillis() - startMs,
                            canRetryWithCloudAi = false
                        )
                    )
                }

                DocumentKind.IMAGE -> {
                    val media = loadMediaFromUri(context, uri, forcePdf = false).getOrThrow()
                    val ocrLines = LocalOcrAndDocumentParser.recognizeLinesLocally(media.pages)
                    val parsed = LocalOcrAndDocumentParser.parseInvoiceLinesLocally(ocrLines, existingInventory)
                    Result.success(
                        LocalInvoiceScanOutput(
                            lines = parsed,
                            previewThumbnail = media.previewThumbnail,
                            rawExtractedText = ocrLines.joinToString("\n"),
                            elapsedMs = System.currentTimeMillis() - startMs,
                            canRetryWithCloudAi = false
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(SCAN_TAG, "Local invoice scan failed for $uri", e)
            Result.failure(
                IllegalStateException(
                    e.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "Could not scan this invoice locally. Please try a sharper photo or another file."
                )
            )
        }
    }

    /**
     * Instant local parser for pasted or typed invoice text (< 5ms, offline).
     */
    fun scanInvoiceTextLocally(
        rawInvoiceText: String,
        existingInventory: List<InventoryItem>
    ): Result<LocalInvoiceScanOutput> {
        val startMs = System.currentTimeMillis()
        if (rawInvoiceText.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter or paste invoice lines to scan."))
        }
        val lines = rawInvoiceText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val parsed = LocalOcrAndDocumentParser.parseInvoiceLinesLocally(lines, existingInventory)
        return Result.success(
            LocalInvoiceScanOutput(
                lines = parsed,
                previewThumbnail = null,
                rawExtractedText = rawInvoiceText,
                elapsedMs = System.currentTimeMillis() - startMs,
                canRetryWithCloudAi = true
            )
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
            You are an accurate multilingual (Arabic, English, and French — including Lebanon & MENA suppliers) restaurant invoice reader.
            CRITICAL RULES:
            1. ONLY read the line items printed or written on the invoice. NEVER invent, rename, or suggest new generic ingredient names.
            2. Ignore non-item rows such as shop header, address, phone numbers, invoice date/number, subtotals, VAT/TVA/tax, discounts, and grand totals.
            3. STRICT POS / STOCK NAME RESPECT: If an invoice item matches one of the kitchen's existing POS/stock items listed below, use its EXACT canonicalKey, nameEn, and nameAr verbatim:
            $catalogSummary
            4. If an invoice item does NOT match any item in the kitchen's existing list above, keep the EXACT item name printed on the invoice in rawLabel and nameEn (do NOT invent a different name).
            5. Extract numeric quantity (default 1.0 if omitted), unit (kg, g, L, mL, pcs), and exact printed line total price (if only unit price is printed, multiply quantity × unit price; 0.0 if missing). Do NOT guess currency conversion — report the exact printed number so the chef can review or convert to USD.
            6. If a line is an unreadable code or blurry (< 75% confidence), set needsManualReview=true and explain briefly in reviewReason.
            Return strict JSON matching the schema.
        """.trimIndent()
    }

    /**
     * Decodes an image or PDF URI completely off the main thread, downsampling during decode so
     * high-megapixel phone photos (12MP-200MP) never cause OutOfMemoryError or hardware bitmap crashes.
     */
    suspend fun loadMediaFromUri(
        context: Context,
        uri: Uri,
        forcePdf: Boolean? = null
    ): Result<LoadedInvoiceMedia> = withContext(Dispatchers.IO) {
        try {
            val isPdf = forcePdf ?: detectIsPdf(context, uri)
            val pages = if (isPdf) {
                renderPdfPages(context, uri)
            } else {
                listOf(decodeSampledSoftwareBitmap(context, uri))
            }

            if (pages.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Could not read any pages from the selected file. Please try another photo or PDF.")
                )
            }

            val preview = scaleDownIfNeeded(pages.first(), maxDim = 240)
            Result.success(
                LoadedInvoiceMedia(
                    pages = pages,
                    previewThumbnail = preview,
                    isPdf = isPdf
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.e(SCAN_TAG, "Out of memory decoding invoice media: $uri", e)
            Result.failure(
                IllegalStateException("This invoice image is too large for device memory. Try taking a closer photo or choosing a smaller file.")
            )
        } catch (e: SecurityException) {
            Log.e(SCAN_TAG, "Permission error opening invoice URI: $uri", e)
            Result.failure(
                IllegalStateException("Could not access the selected file. Please pick the invoice again.")
            )
        } catch (e: Exception) {
            Log.e(SCAN_TAG, "Failed to load invoice media from URI: $uri", e)
            Result.failure(
                IllegalStateException(
                    e.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "Could not read the invoice image or PDF. Please try another photo."
                )
            )
        }
    }

    /**
     * Optional Cloud AI vision fallback for handwritten or cursive Arabic invoices when requested by the chef.
     */
    suspend fun scanInvoiceBitmapsWithCloudAi(
        bitmaps: List<Bitmap>,
        existingInventory: List<InventoryItem>
    ): Result<List<ScannedInvoiceLine>> = try {
        require(bitmaps.isNotEmpty()) { "No invoice image provided to scan." }
        val prepared = withContext(Dispatchers.IO) {
            bitmaps.take(MAX_PDF_PAGES).map { bmp ->
                scaleDownIfNeeded(ensureSoftwareArgb8888(bmp), maxDim = MAX_IMAGE_DIM)
            }
        }
        val prompt = buildSystemInstruction(existingInventory)
        val response = withTimeout(45_000L) {
            model.generateContent(
                content {
                    prepared.forEach { pageBitmap ->
                        image(pageBitmap)
                    }
                    text(prompt)
                }
            )
        }
        val jsonText = response.text ?: throw IllegalStateException("AI scanner returned an empty response.")
        Result.success(parseResponse(jsonText, existingInventory))
    } catch (e: TimeoutCancellationException) {
        Log.e(SCAN_TAG, "Cloud AI invoice scan timed out", e)
        Result.failure(IllegalStateException("Cloud AI scan timed out. Local scan is instant and works offline."))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(SCAN_TAG, "Cloud AI invoice bitmap scan failed", e)
        Result.failure(IllegalStateException(toReadableScanError(e), e))
    }

    suspend fun scanInvoiceTextWithCloudAi(
        rawInvoiceText: String,
        existingInventory: List<InventoryItem>
    ): Result<List<ScannedInvoiceLine>> = try {
        require(rawInvoiceText.isNotBlank()) { "Please enter or paste invoice lines to scan." }
        val prompt = """
            ${buildSystemInstruction(existingInventory)}

            Invoice / Label Text to Parse:
            $rawInvoiceText
        """.trimIndent()
        val response = withTimeout(45_000L) {
            model.generateContent(prompt)
        }
        val jsonText = response.text ?: throw IllegalStateException("AI scanner returned an empty response.")
        Result.success(parseResponse(jsonText, existingInventory))
    } catch (e: TimeoutCancellationException) {
        Log.e(SCAN_TAG, "Cloud AI text scan timed out", e)
        Result.failure(IllegalStateException("Cloud AI scan timed out. Try local scan instead."))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(SCAN_TAG, "Cloud AI invoice text scan failed", e)
        Result.failure(IllegalStateException(toReadableScanError(e), e))
    }

    private fun detectIsPdf(context: Context, uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        if (mime == "application/pdf" || mime.endsWith("/pdf")) return true
        val uriText = uri.toString().lowercase()
        if (uriText.endsWith(".pdf")) return true
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(5)
                val read = stream.read(header)
                read >= 5 && String(header, Charsets.US_ASCII) == "%PDF-"
            } ?: false
        }.getOrDefault(false)
    }

    private fun renderPdfPages(context: Context, uri: Uri): List<Bitmap> {
        val tempPdf = File(context.cacheDir, "scan_pdf_${System.currentTimeMillis()}.pdf")
        try {
            var copiedBytes = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempPdf).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        copiedBytes += count
                        if (copiedBytes > MAX_PDF_BYTES) {
                            throw IllegalStateException("PDF file exceeds 18 MB limit. Please upload a smaller PDF or photo.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalStateException("Unable to open PDF file.")

            if (copiedBytes == 0L) {
                throw IllegalStateException("The selected PDF file is empty.")
            }

            val bitmaps = mutableListOf<Bitmap>()
            ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pageCount = minOf(renderer.pageCount, MAX_PDF_PAGES)
                    for (index in 0 until pageCount) {
                        renderer.openPage(index).use { page ->
                            val scale = MAX_IMAGE_DIM.toFloat() / max(page.width, page.height).coerceAtLeast(1)
                            val clampedScale = scale.coerceIn(1.0f, 2.5f)
                            val targetW = (page.width * clampedScale).roundToInt().coerceIn(1, MAX_IMAGE_DIM)
                            val targetH = (page.height * clampedScale).roundToInt().coerceIn(1, MAX_IMAGE_DIM)
                            val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmaps.add(bitmap)
                        }
                    }
                }
            }
            return bitmaps
        } finally {
            runCatching { tempPdf.delete() }
        }
    }

    private fun decodeSampledSoftwareBitmap(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                    val srcW = info.size.width
                    val srcH = info.size.height
                    val longest = max(srcW, srcH)
                    if (longest > MAX_IMAGE_DIM && srcW > 0 && srcH > 0) {
                        val ratio = MAX_IMAGE_DIM.toFloat() / longest.toFloat()
                        val targetW = (srcW * ratio).roundToInt().coerceAtLeast(1)
                        val targetH = (srcH * ratio).roundToInt().coerceAtLeast(1)
                        decoder.setTargetSize(targetW, targetH)
                    }
                }
            }.onFailure { err ->
                Log.w(SCAN_TAG, "ImageDecoder failed for $uri, falling back to BitmapFactory", err)
            }.getOrNull()

            if (decoded != null) {
                return ensureSoftwareArgb8888(decoded)
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw IllegalStateException("Could not open the selected image file.")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("The selected file is not a valid image or is empty.")
        }

        var sampleSize = 1
        var halfW = bounds.outWidth
        var halfH = bounds.outHeight
        while (max(halfW, halfH) > MAX_IMAGE_DIM) {
            sampleSize *= 2
            halfW /= 2
            halfH /= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: throw IllegalStateException("Could not decode the photo. Please try again.")

        return scaleDownIfNeeded(ensureSoftwareArgb8888(decoded), maxDim = MAX_IMAGE_DIM)
    }

    private fun ensureSoftwareArgb8888(bitmap: Bitmap): Bitmap {
        val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        return if (isHardware || bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Unable to prepare image for scanning.")
        } else {
            bitmap
        }
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
            val nameEn = obj.optString("nameEn", "").trim()
            val nameAr = obj.optString("nameAr", "").trim()
            val rawLabel = obj.optString("rawLabel", "").trim()
                .ifBlank { nameEn.ifBlank { nameAr } }
            if (rawLabel.isEmpty()) continue

            val rawQty = obj.optDouble("quantity", 1.0)
            val quantity = if (rawQty.isNaN() || rawQty.isInfinite() || rawQty <= 0.0) 1.0 else rawQty
            val unit = UnitType.fromSymbol(obj.optString("unit", "kg"))
            val rawCost = obj.optDouble("totalCost", 0.0)
            val totalCost = if (rawCost.isNaN() || rawCost.isInfinite() || rawCost < 0.0) 0.0 else rawCost
            val rawConf = obj.optDouble("confidence", 0.5)
            val confidence = if (rawConf.isNaN() || rawConf.isInfinite()) 0.5 else rawConf.coerceIn(0.0, 1.0)
            val aiCategory = IngredientCategory.fromRaw(obj.optString("category", "OTHER"))
            val flaggedByAi = obj.optBoolean("needsManualReview", false)
            val reviewReason = obj.optString("reviewReason", "").trim()

            val normalizedRaw = BilingualIngredientMatcher.normalize(nameEn.ifBlank { rawLabel })
            val matchedExisting = existingInventory.firstOrNull { item ->
                BilingualIngredientMatcher.normalize(item.nameEn) == normalizedRaw ||
                    (item.nameAr.isNotBlank() && BilingualIngredientMatcher.normalize(item.nameAr) == normalizedRaw) ||
                    item.canonicalKey.equals(obj.optString("canonicalKey", "").trim(), ignoreCase = true) ||
                    item.aliases.any { BilingualIngredientMatcher.normalize(it) == normalizedRaw }
            }

            val shouldFlag = flaggedByAi || confidence < 0.75
            val finalReason = when {
                !shouldFlag -> ""
                reviewReason.isNotBlank() -> reviewReason
                confidence < 0.75 -> "Low OCR confidence (${(confidence * 100).toInt()}%) — verify name, qty & price"
                else -> "Verify item name and match to POS/stock"
            }

            results.add(
                ScannedInvoiceLine(
                    rawLabel = rawLabel,
                    canonicalKey = matchedExisting?.canonicalKey ?: normalizedRaw.ifBlank { "item" },
                    nameEn = matchedExisting?.nameEn ?: nameEn.ifBlank { rawLabel },
                    nameAr = matchedExisting?.nameAr ?: nameAr,
                    category = matchedExisting?.categoryEnum ?: aiCategory,
                    quantity = quantity,
                    unit = unit,
                    totalCost = totalCost,
                    confidence = confidence,
                    needsManualReview = shouldFlag,
                    reviewReason = finalReason,
                    matchedInventoryId = matchedExisting?.id,
                    detectedCurrency = if (totalCost >= 10_000.0) "LBP" else "$"
                )
            )
        }
        return results
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int = MAX_IMAGE_DIM): Bitmap {
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

    private fun toReadableScanError(e: Exception): String {
        val msg = e.localizedMessage.orEmpty()
        val lower = msg.lowercase()
        return when {
            lower.contains("app check") || lower.contains("403") || lower.contains("attestation") ->
                "Cloud AI verification failed (App Check 403). Local scan works offline without App Check."
            lower.contains("unable to resolve host") || lower.contains("network") || lower.contains("failed to connect") ->
                "No internet connection for Cloud AI. Use fast local scan offline."
            lower.contains("quota") || lower.contains("429") || lower.contains("resource_exhausted") ->
                "Cloud AI is busy right now. Please wait a few seconds or use local scan."
            msg.isNotBlank() -> msg
            else -> "Cloud AI scan failed. Please try a clearer photo."
        }
    }
}
