package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import com.example.model.ScannedInvoiceLine
import com.example.model.UnitType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

enum class DocumentKind {
    IMAGE,
    PDF,
    EXCEL_XLSX,
    WORD_DOCX,
    CSV_OR_TEXT
}

object LocalOcrAndDocumentParser {

    private const val MAX_ZIP_ENTRY_BYTES = 12 * 1024 * 1024 // 12 MB safety cap per zip entry
    private const val MAX_ZIP_TOTAL_BYTES = 24 * 1024 * 1024 // 24 MB total unzipped cap
    private const val MAX_DIGITAL_PDF_BYTES = 12 * 1024 * 1024 // 12 MB cap for direct PDF text parse
    private const val OCR_PAGE_TIMEOUT_MS = 8_000L

    private val warmUpStarted = AtomicBoolean(false)
    private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val knownCatalogKeys: Set<String> by lazy {
        BilingualIngredientMatcher.basicIngredients
            .map { BilingualIngredientMatcher.normalize(it.key) }
            .toSet()
    }

    /**
     * Pre-warms ML Kit's on-device OCR native engine in the background at startup
     * so the user's very first invoice or recipe photo scan completes in under a second.
     */
    fun warmUp() {
        if (!warmUpStarted.compareAndSet(false, true)) return
        warmUpScope.launch {
            runCatching {
                val tiny = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                try {
                    recognizeSingleBitmap(tiny)
                } finally {
                    tiny.recycle()
                }
            }
        }
    }

    /**
     * Ultra-fast pure-Kotlin digital PDF text extractor (< 25ms, zero bitmap rendering!).
     * Reads uncompressed & FlateDecode text streams for digital invoices & exported recipe PDFs.
     * Returns empty list for scanned/image-only PDFs so callers seamlessly fall back to local OCR.
     */
    suspend fun extractDigitalPdfText(context: Context, uri: Uri): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_DIGITAL_PDF_BYTES) return@runCatching emptyList()
                    baos.write(buf, 0, n)
                }
                baos.toByteArray()
            } ?: return@runCatching emptyList()

            if (rawBytes.size < 5 || String(rawBytes, 0, 5, Charsets.US_ASCII) != "%PDF-") {
                return@runCatching emptyList()
            }

            val latin = String(rawBytes, Charsets.ISO_8859_1)
            val extractedLines = mutableListOf<String>()
            var pos = 0
            while (pos < latin.length) {
                val streamStart = latin.indexOf("stream", pos)
                if (streamStart < 0) break
                val dictStart = max(0, streamStart - 300)
                val headerSnippet = latin.substring(dictStart, streamStart)
                var dataStart = streamStart + 6
                if (dataStart < latin.length && latin[dataStart] == '\r') dataStart++
                if (dataStart < latin.length && latin[dataStart] == '\n') dataStart++
                val endStream = latin.indexOf("endstream", dataStart)
                if (endStream < 0) break
                pos = endStream + 9

                // Skip embedded image streams (JPEG/DCTDecode/Image XObjects)
                if (headerSnippet.contains("/Subtype /Image") || headerSnippet.contains("/DCTDecode")) {
                    continue
                }

                val slice = rawBytes.copyOfRange(dataStart, endStream)
                val contentText = if (headerSnippet.contains("/FlateDecode")) {
                    inflatePdfStream(slice)?.let { String(it, Charsets.ISO_8859_1) } ?: continue
                } else {
                    String(slice, Charsets.ISO_8859_1)
                }

                if (contentText.contains("BT") && (contentText.contains("Tj") || contentText.contains("TJ"))) {
                    extractedLines.addAll(parsePdfTextOperators(contentText))
                }
            }

            // Keep meaningful lines only (filter binary/font-glyph garbage)
            extractedLines
                .map { it.trim() }
                .filter { line ->
                    line.length >= 2 &&
                        line.any { it.isLetterOrDigit() } &&
                        line.count { it.code in 32..126 || it.code in 0x0600..0x06FF } >= (line.length * 0.7)
                }
        }.getOrDefault(emptyList())
    }

    private fun inflatePdfStream(compressed: ByteArray): ByteArray? {
        return runCatching {
            val inflater = Inflater()
            try {
                inflater.setInput(compressed)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (!inflater.finished()) {
                    val count = inflater.inflate(buf)
                    if (count <= 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                        break
                    }
                    total += count
                    if (total > 2 * 1024 * 1024) break
                    out.write(buf, 0, count)
                }
                out.toByteArray()
            } finally {
                inflater.end()
            }
        }.getOrNull()
    }

    private fun parsePdfTextOperators(streamText: String): List<String> {
        val lines = mutableListOf<String>()
        val currentLine = StringBuilder()
        var i = 0
        val len = streamText.length

        fun flushLine() {
            val s = currentLine.toString().replace(Regex("\\s{2,}"), "  ").trim()
            if (s.isNotEmpty()) lines.add(s)
            currentLine.setLength(0)
        }

        while (i < len) {
            val ch = streamText[i]
            if (ch == '(') {
                val sb = StringBuilder()
                var depth = 1
                i++
                while (i < len && depth > 0) {
                    val c = streamText[i]
                    if (c == '\\' && i + 1 < len) {
                        val next = streamText[i + 1]
                        when (next) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append(' ')
                            '(', ')', '\\' -> sb.append(next)
                            else -> sb.append(next)
                        }
                        i += 2
                    } else if (c == '(') {
                        depth++
                        sb.append(c)
                        i++
                    } else if (c == ')') {
                        depth--
                        if (depth > 0) sb.append(c)
                        i++
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                val token = sb.toString()
                if (token.isNotBlank()) {
                    if (currentLine.isNotEmpty() && !currentLine.endsWith(" ")) currentLine.append("  ")
                    currentLine.append(token)
                }
            } else if (ch == 'E' && i + 1 < len && streamText[i + 1] == 'T' &&
                (i == 0 || !streamText[i - 1].isLetter()) &&
                (i + 2 >= len || !streamText[i + 2].isLetter())
            ) {
                flushLine()
                i += 2
            } else if (ch == 'T' && i + 1 < len && (streamText[i + 1] == '*' || streamText[i + 1] == 'd' || streamText[i + 1] == 'D') &&
                (i == 0 || !streamText[i - 1].isLetter())
            ) {
                flushLine()
                i += 2
            } else {
                i++
            }
        }
        flushLine()
        return lines
    }

    fun detectDocumentKind(context: Context, uri: Uri): DocumentKind {
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.US).orEmpty()
        val displayName = queryFileName(context, uri).lowercase(Locale.US)
        val uriLower = uri.toString().lowercase(Locale.US)

        if (mime == "application/pdf" || displayName.endsWith(".pdf") || uriLower.endsWith(".pdf")) {
            return DocumentKind.PDF
        }
        if (mime.contains("spreadsheetml") || mime.contains("ms-excel") ||
            displayName.endsWith(".xlsx") || uriLower.endsWith(".xlsx")
        ) {
            return DocumentKind.EXCEL_XLSX
        }
        if (mime.contains("wordprocessingml") || mime.contains("msword") ||
            displayName.endsWith(".docx") || uriLower.endsWith(".docx")
        ) {
            return DocumentKind.WORD_DOCX
        }
        if (mime.startsWith("text/") || mime.contains("csv") ||
            displayName.endsWith(".csv") || displayName.endsWith(".tsv") || displayName.endsWith(".txt")
        ) {
            return DocumentKind.CSV_OR_TEXT
        }
        if (mime.startsWith("image/") ||
            displayName.endsWith(".jpg") || displayName.endsWith(".jpeg") ||
            displayName.endsWith(".png") || displayName.endsWith(".webp") || displayName.endsWith(".heic")
        ) {
            return DocumentKind.IMAGE
        }

        // Magic bytes inspection fallback
        val header = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(8)
                val n = stream.read(buf)
                if (n > 0) buf.copyOf(n) else ByteArray(0)
            }
        }.getOrNull() ?: ByteArray(0)

        if (header.size >= 5 && String(header, 0, 5, Charsets.US_ASCII) == "%PDF-") {
            return DocumentKind.PDF
        }
        if (header.size >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
            // ZIP container: inspect whether it's an XLSX or DOCX
            val zipKind = detectOfficeZipKind(context, uri)
            if (zipKind != null) return zipKind
        }
        return DocumentKind.IMAGE
    }

    fun queryFileName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    private fun detectOfficeZipKind(context: Context, uri: Uri): DocumentKind? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase(Locale.US)
                        if (name.startsWith("xl/")) return DocumentKind.EXCEL_XLSX
                        if (name.startsWith("word/")) return DocumentKind.WORD_DOCX
                        entry = zip.nextEntry
                    }
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Runs Google ML Kit on-device OCR across one or more pages completely locally (< 0.5s/page)
     * and reconstructs aligned horizontal table rows using line bounding boxes.
     */
    suspend fun recognizeLinesLocally(bitmaps: List<Bitmap>): List<String> = withContext(Dispatchers.Default) {
        val allLines = mutableListOf<String>()
        for (bitmap in bitmaps) {
            val recognized = recognizeSingleBitmap(bitmap)
            allLines.addAll(reconstructVisualRows(recognized))
        }
        allLines
    }

    private suspend fun recognizeSingleBitmap(bitmap: Bitmap): Text =
        withTimeout(OCR_PAGE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val input = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(input)
                    .addOnSuccessListener { visionText ->
                        if (cont.isActive) cont.resume(visionText)
                    }
                    .addOnFailureListener { err ->
                        if (cont.isActive) cont.resumeWithException(err)
                    }
            }
        }

    /**
     * Groups ML Kit Text.Line items that appear on the same horizontal row (within ~55% of line height)
     * and orders them left-to-right so tabular invoices and recipe sheets remain intact.
     */
    private fun reconstructVisualRows(visionText: Text): List<String> {
        data class PositionedLine(val text: String, val left: Int, val centerY: Int, val height: Int)

        val positioned = mutableListOf<PositionedLine>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val clean = line.text.trim()
                if (clean.isEmpty()) continue
                val box = line.boundingBox
                if (box != null) {
                    positioned.add(
                        PositionedLine(
                            text = clean,
                            left = box.left,
                            centerY = (box.top + box.bottom) / 2,
                            height = max(12, box.height())
                        )
                    )
                } else {
                    positioned.add(PositionedLine(clean, 0, positioned.size * 30, 24))
                }
            }
        }
        if (positioned.isEmpty()) {
            return visionText.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }

        positioned.sortWith(compareBy<PositionedLine> { it.centerY }.thenBy { it.left })
        val rows = mutableListOf<MutableList<PositionedLine>>()
        for (item in positioned) {
            val targetRow = rows.lastOrNull()
            if (targetRow == null) {
                rows.add(mutableListOf(item))
            } else {
                val avgY = targetRow.map { it.centerY }.average()
                val avgH = targetRow.map { it.height }.average()
                val tolerance = max(14.0, avgH * 0.58)
                if (abs(item.centerY - avgY) <= tolerance) {
                    targetRow.add(item)
                } else {
                    rows.add(mutableListOf(item))
                }
            }
        }

        return rows.map { row ->
            row.sortedBy { it.left }
                .joinToString("  ") { it.text }
                .trim()
        }.filter { it.isNotEmpty() }
    }

    // --- Pure-Kotlin Safe Excel (.xlsx) Reader ---

    /**
     * Reads all sheets from an .xlsx workbook into a map of (sheetIndex -> rows of cells).
     * Uses XmlPullParser with strict size caps and zero external libraries.
     */
    suspend fun readXlsxSheets(context: Context, uri: Uri): List<Pair<String, List<List<String>>>> =
        withContext(Dispatchers.IO) {
            val zipEntries = readSafeZipEntries(context, uri)
            val sharedStrings = zipEntries["xl/sharedStrings.xml"]
                ?.let { parseXlsxSharedStrings(it) }
                ?: emptyList()

            val sheetNamesByTarget = zipEntries["xl/workbook.xml"]
                ?.let { parseWorkbookSheetNames(it) }
                ?: emptyList()

            val worksheetPaths = zipEntries.keys
                .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sortedBy { path ->
                    Regex("sheet(\\d+)\\.xml").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 999
                }

            worksheetPaths.mapIndexed { idx, path ->
                val bytes = zipEntries[path] ?: ByteArray(0)
                val name = sheetNamesByTarget.getOrNull(idx) ?: "Sheet${idx + 1}"
                name to parseWorksheetRows(bytes, sharedStrings)
            }
        }

    private fun readSafeZipEntries(context: Context, uri: Uri): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var totalRead = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        if (name.endsWith(".xml", ignoreCase = true) || name.endsWith(".rels", ignoreCase = true)) {
                            val out = ByteArrayOutputStream()
                            val buf = ByteArray(8192)
                            var entryRead = 0
                            while (true) {
                                val count = zip.read(buf)
                                if (count <= 0) break
                                entryRead += count
                                totalRead += count
                                if (entryRead > MAX_ZIP_ENTRY_BYTES || totalRead > MAX_ZIP_TOTAL_BYTES) {
                                    throw IllegalStateException("Spreadsheet or document file is too large to read safely.")
                                }
                                out.write(buf, 0, count)
                            }
                            result[name] = out.toByteArray()
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalStateException("Could not open the selected file.")
        return result
    }

    private fun newSafeXmlParser(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        runCatching { parser.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        return parser
    }

    private fun parseWorkbookSheetNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        val parser = newSafeXmlParser(bytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && (parser.name == "sheet" || parser.name.endsWith(":sheet"))) {
                val sheetName = parser.getAttributeValue(null, "name")
                if (!sheetName.isNullOrBlank()) names.add(sheetName)
            }
            event = parser.next()
        }
        return names
    }

    private fun parseXlsxSharedStrings(bytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = newSafeXmlParser(bytes)
        var event = parser.eventType
        var inSi = false
        var inT = false
        val sb = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    if (tag == "si") {
                        inSi = true
                        sb.setLength(0)
                    } else if (inSi && tag == "t") {
                        inT = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inSi && inT) sb.append(parser.text.orEmpty())
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    if (tag == "t") inT = false
                    if (tag == "si") {
                        list.add(sb.toString())
                        inSi = false
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    private fun parseWorksheetRows(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val parser = newSafeXmlParser(bytes)
        var event = parser.eventType
        var currentRow = mutableListOf<String>()
        var cellType = ""
        var cellColIdx = -1
        var inValue = false
        var inInlineText = false
        val valueSb = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "row" -> currentRow = mutableListOf()
                        "c" -> {
                            cellType = parser.getAttributeValue(null, "t").orEmpty()
                            val cellRef = parser.getAttributeValue(null, "r").orEmpty()
                            cellColIdx = excelColRefToIndex(cellRef)
                            valueSb.setLength(0)
                        }
                        "v" -> inValue = true
                        "t" -> if (cellType == "inlineStr") inInlineText = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inValue || inInlineText) valueSb.append(parser.text.orEmpty())
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "v" -> inValue = false
                        "t" -> inInlineText = false
                        "c" -> {
                            val raw = valueSb.toString().trim()
                            val resolved = if (cellType == "s") {
                                raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
                            } else {
                                raw
                            }
                            if (cellColIdx >= 0) {
                                while (currentRow.size <= cellColIdx) currentRow.add("")
                                currentRow[cellColIdx] = resolved
                            } else {
                                currentRow.add(resolved)
                            }
                            cellType = ""
                            cellColIdx = -1
                        }
                        "row" -> {
                            if (currentRow.any { it.isNotBlank() }) {
                                rows.add(currentRow.toList())
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun excelColRefToIndex(ref: String): Int {
        if (ref.isBlank()) return -1
        var col = 0
        var seenLetter = false
        for (ch in ref) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch - 'A' + 1)
                seenLetter = true
            } else if (ch in 'a'..'z') {
                col = col * 26 + (ch - 'a' + 1)
                seenLetter = true
            } else break
        }
        return if (seenLetter) col - 1 else -1
    }

    // --- Pure-Kotlin Safe Word (.docx) Reader ---

    /**
     * Reads paragraphs and table rows from a Word .docx file cleanly in reading order.
     */
    suspend fun readDocxLines(context: Context, uri: Uri): List<String> = withContext(Dispatchers.IO) {
        val zipEntries = readSafeZipEntries(context, uri)
        val docXml = zipEntries["word/document.xml"]
            ?: throw IllegalStateException("This Word file is missing document content. Make sure it is a .docx file.")

        val lines = mutableListOf<String>()
        val parser = newSafeXmlParser(docXml)
        var event = parser.eventType

        var inTableRow = false
        var inTableCell = false
        val rowCells = mutableListOf<String>()
        val cellSb = StringBuilder()
        val paraSb = StringBuilder()
        var inText = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "tr" -> {
                            inTableRow = true
                            rowCells.clear()
                        }
                        "tc" -> {
                            inTableCell = true
                            cellSb.setLength(0)
                        }
                        "p" -> if (!inTableCell) paraSb.setLength(0)
                        "t" -> inText = true
                        "tab" -> {
                            if (inTableCell) cellSb.append(' ') else paraSb.append("  ")
                        }
                        "br" -> {
                            if (inTableCell) cellSb.append(' ') else paraSb.append('\n')
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inText) {
                        val txt = parser.text.orEmpty()
                        if (inTableCell) cellSb.append(txt) else paraSb.append(txt)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "t" -> inText = false
                        "p" -> {
                            if (inTableCell) {
                                if (cellSb.isNotEmpty() && !cellSb.endsWith(" ")) cellSb.append(' ')
                            } else {
                                paraSb.toString().lines().forEach { l ->
                                    val clean = l.trim()
                                    if (clean.isNotEmpty()) lines.add(clean)
                                }
                            }
                        }
                        "tc" -> {
                            inTableCell = false
                            rowCells.add(cellSb.toString().trim())
                        }
                        "tr" -> {
                            inTableRow = false
                            val joined = rowCells.filter { it.isNotEmpty() }.joinToString(" | ").trim()
                            if (joined.isNotEmpty()) lines.add(joined)
                        }
                    }
                }
            }
            event = parser.next()
        }
        lines
    }

    // --- CSV / TSV / Plain Text Reader ---

    suspend fun readCsvOrTextRows(context: Context, uri: Uri): List<List<String>> = withContext(Dispatchers.IO) {
        val rawLines = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                var count = 0
                while (line != null && count < 4000) {
                    val cleaned = line.removePrefix("\uFEFF").trim()
                    if (cleaned.isNotEmpty()) rawLines.add(cleaned)
                    line = reader.readLine()
                    count++
                }
            }
        } ?: throw IllegalStateException("Could not read the selected text or CSV file.")

        if (rawLines.isEmpty()) return@withContext emptyList()
        val sample = rawLines.take(5).joinToString("\n")
        val delimiter = when {
            sample.count { it == '\t' } > sample.count { it == ',' } -> '\t'
            sample.count { it == ';' } > sample.count { it == ',' } -> ';'
            else -> ','
        }
        rawLines.map { splitDelimitedLine(it, delimiter) }
    }

    fun splitDelimitedLine(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == delimiter && !inQuotes -> {
                    cells.add(sb.toString().trim())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        cells.add(sb.toString().trim())
        return cells
    }

    // --- Fast Local Deterministic Invoice Line Parser ---

    private val noiseKeywords = listOf(
        "subtotal", "sub total", "grand total", "total due", "net total", "amount due",
        "vat", "tva", "tax", "discount", "change", "cash", "visa", "mastercard", "card",
        "balance", "invoice #", "invoice no", "inv#", "receipt #", "receipt no", "order #",
        "date:", "time:", "tel:", "phone", "fax", "www.", "http", "thank you", "welcome",
        "address", "street", "beirut", "lebanon", "مجموع", "المجموع", "الإجمالي", "الاجمالي",
        "ضريبة", "خصم", "فاتورة", "التاريخ", "شكراً", "شكرا", "المدفوع", "الباقي"
    )

    private val qtyUnitRegex = Regex(
        """(?i)(?:^|[\s,(x×])(\d+(?:[.,]\d+)?)\s*(kg|kilo|kilos|kgs|g|gr|gm|grams|gram|l|lt|ltr|liter|litre|liters|ml|pcs|pc|piece|pieces|unit|units|box|boxes|pack|packs|bag|bags|bottle|bottles|btl|can|cans|tray|trays|dozen|dz|كغ|كجم|كيلو|جم|غم|غ|لتر|مل|حبة|علبة|صندوق|ربطة)(?=$|[\s,.)/x×-])"""
    )

    private val priceWithCurrencyRegex = Regex(
        """(?i)(?:\$|usd|eur|€|£|lbp|ll|sar|aed|qar|kwd|ل\.ل)\s*(\d+(?:[.,]\d+)?)|(\d+(?:[.,]\d+)?)\s*(?:\$|usd|eur|€|£|lbp|ll|sar|aed|qar|kwd|ل\.ل)"""
    )

    private val trailingPriceRegex = Regex("""(?<!\d)(\d+(?:[.,]\d{1,2}))\s*$""")
    private val leadingQtyRegex = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*[x×*]?\s+(.+)$""")
    private val dateOrPhoneRegex = Regex("""^\s*(\d{1,4}[/-]\d{1,2}[/-]\d{1,4}|\+?\d[\d\s()-]{6,})\s*$""")

    fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '٠'..'٩' -> sb.append(('0'.code + (ch - '٠')).toChar())
                in '۰'..'۹' -> sb.append(('0'.code + (ch - '۰')).toChar())
                '٫' -> sb.append('.')
                '٬' -> sb.append(',')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun stitchWrappedOcrLines(rawLines: List<String>): List<String> {
        val stitched = mutableListOf<String>()
        for (raw in rawLines) {
            val trimmed = normalizeDigits(raw).trim()
            if (trimmed.isEmpty()) continue
            val isQtyPriceOnly = trimmed.none { it.isLetter() } ||
                (qtyUnitRegex.containsMatchIn(trimmed) &&
                    trimmed.replace(qtyUnitRegex, "")
                        .replace(priceWithCurrencyRegex, "")
                        .replace(Regex("[0-9.,$€£x×*\\s/-]+"), "")
                        .length <= 2)
            if (isQtyPriceOnly && stitched.isNotEmpty() && !qtyUnitRegex.containsMatchIn(stitched.last())) {
                stitched[stitched.lastIndex] = "${stitched.last()}  $trimmed"
            } else {
                stitched.add(raw)
            }
        }
        return stitched
    }

    fun parseInvoiceLinesLocally(
        rawLines: List<String>,
        existingInventory: List<InventoryItem>
    ): List<ScannedInvoiceLine> {
        val results = mutableListOf<ScannedInvoiceLine>()
        val preparedLines = stitchWrappedOcrLines(rawLines)

        for (rawOriginal in preparedLines) {
            val normalizedLine = normalizeDigits(rawOriginal)
                .replace("|", "  ")
                .replace("\t", "  ")
                .trim()
            if (normalizedLine.length < 2) continue

            val lower = normalizedLine.lowercase(Locale.US)
            if (isNoiseLine(normalizedLine, lower)) continue

            var working = normalizedLine

            // 1. Extract price & detected currency (explicit currency first, then trailing decimal/number)
            var totalCost = 0.0
            var detectedCurrency = "$"
            val currencyMatches = priceWithCurrencyRegex.findAll(working).toList()
            if (currencyMatches.isNotEmpty()) {
                val lastMoney = currencyMatches.last()
                val numStr = lastMoney.groupValues[1].ifBlank { lastMoney.groupValues[2] }
                totalCost = parseNumber(numStr)
                val tokenLower = lastMoney.value.lowercase(Locale.US)
                detectedCurrency = when {
                    tokenLower.contains("lbp") || tokenLower.contains("l.l") || tokenLower.contains("ll") || tokenLower.contains("ل.ل") -> "LBP"
                    tokenLower.contains("eur") || tokenLower.contains("€") -> "EUR"
                    tokenLower.contains("gbp") || tokenLower.contains("£") -> "GBP"
                    tokenLower.contains("aed") -> "AED"
                    tokenLower.contains("sar") -> "SAR"
                    else -> "$"
                }
                working = working.removeRange(lastMoney.range).trim()
            }

            // 2. Extract quantity + explicit unit (e.g. "2.5 kg", "500g", "3 pcs")
            var quantity = 1.0
            var unit = UnitType.PCS
            var hasExplicitQtyUnit = false
            val qtyUnitMatch = qtyUnitRegex.find(working)
            if (qtyUnitMatch != null) {
                quantity = parseNumber(qtyUnitMatch.groupValues[1]).takeIf { it > 0.0 } ?: 1.0
                unit = parseFlexibleUnit(qtyUnitMatch.groupValues[2])
                hasExplicitQtyUnit = true
                working = working.removeRange(qtyUnitMatch.range).trim()
            }

            // 3. If no currency price yet, check for trailing numeric cost (e.g. "Chicken 2kg 18.50")
            if (totalCost <= 0.0) {
                val trailingMatch = trailingPriceRegex.find(working)
                if (trailingMatch != null) {
                    val candidate = parseNumber(trailingMatch.groupValues[1])
                    if (candidate >= 0.0) {
                        totalCost = candidate
                        working = working.removeRange(trailingMatch.range).trim()
                    }
                }
            }
            if (detectedCurrency == "$" && totalCost >= 10_000.0 && !normalizedLine.contains("$") && !lower.contains("usd")) {
                detectedCurrency = "LBP"
            }

            // 4. If still no explicit qty, check leading count (e.g. "2x Garlic" or "3 Tomatoes")
            if (!hasExplicitQtyUnit) {
                val leadMatch = leadingQtyRegex.find(working)
                if (leadMatch != null) {
                    val leadQty = parseNumber(leadMatch.groupValues[1])
                    val rest = leadMatch.groupValues[2].trim()
                    if (leadQty > 0.0 && leadQty <= 10_000.0 && rest.any { it.isLetter() }) {
                        quantity = leadQty
                        working = rest
                    }
                }
            }

            // 5. Clean remaining text to isolate the exact invoice item name
            val cleanedName = working
                .replace(Regex("""\b\d+(?:[.,]\d+)?\s*[x×*]\s*\d+(?:[.,]\d+)?\b"""), " ")
                .replace(Regex("""^[\s\-•*:#\d.)]+"""), "")
                .replace(Regex("""[\s\-:|@]+$"""), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()

            if (cleanedName.length < 2 || !cleanedName.any { it.isLetter() }) continue

            // Ignore pure table headers like "Item  Qty  Price"
            val cleanLower = cleanedName.lowercase(Locale.US)
            if (cleanLower in setOf("item", "items", "description", "product", "qty", "quantity", "price", "amount", "unit", "total", "name")) {
                continue
            }

            // 6. Strictly respect existing POS / Stock names — NEVER substitute unrelated generic names!
            val normalizedInput = BilingualIngredientMatcher.normalize(cleanedName)
            val matchedExisting = existingInventory.firstOrNull { item ->
                BilingualIngredientMatcher.normalize(item.nameEn) == normalizedInput ||
                    (item.nameAr.isNotBlank() && BilingualIngredientMatcher.normalize(item.nameAr) == normalizedInput) ||
                    item.canonicalKey.equals(normalizedInput, ignoreCase = true) ||
                    item.aliases.any { alias -> BilingualIngredientMatcher.normalize(alias) == normalizedInput }
            }

            val finalUnit = if (hasExplicitQtyUnit) {
                unit
            } else {
                matchedExisting?.unitEnum ?: UnitType.PCS
            }

            val isLikelySku = Regex("""^(?i)(sku|code|item|ref|plu)[\s\-_#]*[a-z0-9]+$""").matches(cleanedName)
            val confidence = when {
                isLikelySku -> 0.35
                matchedExisting != null -> 0.96
                hasExplicitQtyUnit && totalCost > 0.0 -> 0.85
                totalCost > 0.0 -> 0.78
                else -> 0.65
            }

            val needsReview = isLikelySku
            val reviewReason = if (isLikelySku) {
                "Supplier SKU/code — edit or match to an existing POS/Stock name"
            } else {
                ""
            }

            results.add(
                ScannedInvoiceLine(
                    rawLabel = rawOriginal.trim().take(120),
                    canonicalKey = matchedExisting?.canonicalKey ?: normalizedInput.ifBlank { "item" },
                    nameEn = matchedExisting?.nameEn ?: cleanedName,
                    nameAr = matchedExisting?.nameAr ?: "",
                    category = matchedExisting?.categoryEnum ?: IngredientCategory.OTHER,
                    quantity = quantity,
                    unit = finalUnit,
                    totalCost = totalCost,
                    confidence = confidence,
                    needsManualReview = needsReview,
                    reviewReason = reviewReason,
                    matchedInventoryId = matchedExisting?.id,
                    detectedCurrency = detectedCurrency
                )
            )
        }
        return results
    }

    private fun isNoiseLine(line: String, lower: String): Boolean {
        if (dateOrPhoneRegex.matches(line)) return true
        if (lower.startsWith("total") && !lower.contains("kg") && !lower.contains(" g ")) return true
        if (noiseKeywords.any { kw -> lower.contains(kw) }) return true
        // Skip lines that are purely numbers/symbols
        if (line.none { it.isLetter() }) return true
        return false
    }

    private fun isKnownCatalogKey(canonicalKey: String): Boolean {
        return BilingualIngredientMatcher.normalize(canonicalKey) in knownCatalogKeys
    }

    private fun findCatalogMatchInsidePhrase(
        phrase: String,
        existingInventory: List<InventoryItem>
    ): CanonicalIngredientMatch? {
        val normPhrase = BilingualIngredientMatcher.normalize(phrase)
        if (normPhrase.length < 3) return null

        // Check existing inventory names first (longest match wins)
        val bestInv = existingInventory
            .filter { item ->
                val nEn = BilingualIngredientMatcher.normalize(item.nameEn)
                val nAr = BilingualIngredientMatcher.normalize(item.nameAr)
                (nEn.length >= 3 && normPhrase.contains(nEn)) ||
                    (nAr.length >= 2 && normPhrase.contains(nAr))
            }
            .maxByOrNull { it.nameEn.length }
        if (bestInv != null) {
            return CanonicalIngredientMatch(
                canonicalKey = bestInv.canonicalKey,
                nameEn = bestInv.nameEn,
                nameAr = bestInv.nameAr,
                category = bestInv.categoryEnum,
                matchedExistingId = bestInv.id
            )
        }

        // Check built-in 115+ basic ingredients catalog (longest match wins, e.g. "olive oil" over "oil")
        val bestSpec = BilingualIngredientMatcher.basicIngredients
            .filter { spec ->
                val en = BilingualIngredientMatcher.normalize(spec.nameEn)
                val ar = BilingualIngredientMatcher.normalize(spec.nameAr)
                (en.length >= 3 && normPhrase.contains(en)) ||
                    (ar.length >= 2 && normPhrase.contains(ar)) ||
                    spec.synonyms.any { syn ->
                        val ns = BilingualIngredientMatcher.normalize(syn)
                        ns.length >= 3 && normPhrase.contains(ns)
                    }
            }
            .maxByOrNull { it.nameEn.length }

        return bestSpec?.let { spec ->
            CanonicalIngredientMatch(
                canonicalKey = spec.key,
                nameEn = spec.nameEn,
                nameAr = spec.nameAr,
                category = spec.category,
                matchedExistingId = existingInventory.firstOrNull {
                    BilingualIngredientMatcher.normalize(it.canonicalKey) == BilingualIngredientMatcher.normalize(spec.key)
                }?.id
            )
        }
    }

    fun parseFlexibleUnit(raw: String): UnitType {
        val u = raw.trim().lowercase(Locale.US)
        return when (u) {
            "kg", "kilo", "kilos", "kgs", "كغ", "كجم", "كيلو" -> UnitType.KG
            "g", "gr", "gm", "gram", "grams", "جم", "غم", "غ" -> UnitType.G
            "l", "lt", "ltr", "liter", "litre", "liters", "لتر" -> UnitType.L
            "ml", "milliliter", "milliliters", "مل" -> UnitType.ML
            else -> UnitType.PCS
        }
    }

    fun parseNumber(raw: String): Double {
        val cleaned = normalizeDigits(raw).trim()
        if (cleaned.isEmpty()) return 0.0
        // Handle European decimal comma (e.g. "3,50") vs thousands comma ("1,200.50")
        val normalized = if (cleaned.contains(',') && !cleaned.contains('.')) {
            val parts = cleaned.split(',')
            if (parts.size == 2 && parts[1].length <= 2) {
                cleaned.replace(',', '.')
            } else {
                cleaned.replace(",", "")
            }
        } else {
            cleaned.replace(",", "")
        }
        return normalized.toDoubleOrNull() ?: 0.0
    }
}
