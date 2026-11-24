package com.example.app.excel

import android.content.ContentResolver
import android.net.Uri
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.io.File
import java.io.InputStream
import java.util.Date
import java.util.Locale

/** Reads both the original flat export and the real pivot-style movement report. */
class ExcelReader(private val contentResolver: ContentResolver) {
    fun read(uri: Uri): List<Invoice> {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("تعذر فتح ملف Excel")
        return input.use { parseStream(it) }
    }

    fun readFile(file: File): List<Invoice> = file.inputStream().use { parseStream(it) }

    private fun parseStream(stream: InputStream): List<Invoice> {
        WorkbookFactory.create(stream).use { workbook ->
                for (sheet in workbook) {
                    val pivotRows = parsePivotSheet(sheet)
                    if (pivotRows.isNotEmpty()) return pivotRows
                }

                // Keep compatibility with the earlier 12-column export.
                var lastMissingColumns: List<String>? = null
                for (sheet in workbook) {
                    val flatRows = parseFlatSheet(sheet) { missing -> lastMissingColumns = missing }
                    if (flatRows.isNotEmpty()) return flatRows
                }
                lastMissingColumns?.let { throw MissingColumnsException(it) }
        }
        return emptyList()
    }

    private fun parsePivotSheet(sheet: Sheet): List<Invoice> {
        val formatter = DataFormatter(Locale.US)
        var materialColumn: Int? = null
        var unitColumn: Int? = null
        var currentItemCode: String? = null
        var currentItemName: String? = null
        var currentUnit = ""
        var dateColumn: Int? = null
        var invoiceColumn: Int? = null
        var inputColumn: Int? = null
        var outputColumn: Int? = null
        var salespersonColumn: Int? = null
        val parentHeaders = mutableMapOf<Int, String>()
        var insidePivot = false
        val result = mutableListOf<Invoice>()

        for (row in sheet) {
            val cells = rowStrings(row, formatter)
            val lower = cells.map { it.trim().lowercase() }

            // The material/unit header is above each item block.
            lower.forEachIndexed { index, value ->
                when (value) {
                    "المادة", "item", "المواد" -> materialColumn = index
                    "الوحدة", "unit" -> unitColumn = index
                }
            }

            // A material block header is located by the detected مادة column.
            val itemMatch = materialColumn?.let { ITEM_HEADER.matchEntire(cells.getOrNull(it).orEmpty()) }
            if (itemMatch != null) {
                currentItemCode = itemMatch.groupValues[1]
                currentItemName = itemMatch.groupValues[2].trim()
                currentUnit = unitColumn?.let { cells.getOrNull(it).orEmpty().trim() }.orEmpty()
                dateColumn = null
                invoiceColumn = null
                inputColumn = null
                outputColumn = null
                salespersonColumn = null
                parentHeaders.clear()
                insidePivot = true
                continue
            }

            if (!insidePivot || currentItemCode == null) continue

            // First header level: إدخالات/إخراجات. Keep it by column, then
            // resolve the repeated child label (الكمية) below it.
            lower.forEachIndexed { index, value ->
                if (value == "الإدخالات" || value == "الادخالات" || value == "الإخراجات" || value == "الاخراجات") {
                    parentHeaders[index] = value
                }
            }
            lower.forEachIndexed { index, value ->
                when {
                    value == "التاريخ" || value == "date" -> dateColumn = index
                    value == "فاتورة" || value == "الفاتورة" || value == "invoice" -> invoiceColumn = index
                    value == "المستودع" || value == "warehouse" -> salespersonColumn = index
                    value == "الكمية" || value == "quantity" -> {
                        val parent = parentHeaders[index]
                        when {
                            parent == "الإدخالات" || parent == "الادخالات" -> inputColumn = index
                            parent == "الإخراجات" || parent == "الاخراجات" -> outputColumn = index
                        }
                    }
                }
            }

            val dateIndex = dateColumn ?: continue
            val invoiceIndex = invoiceColumn ?: continue
            val dateCell = row.getCell(dateIndex)
            val dateText = cells.getOrNull(dateIndex).orEmpty()
            val date = parseDate(dateCell, dateText, formatter) ?: continue
            val movement = cells.getOrNull(invoiceIndex).orEmpty().trim()
            val normalizedMovement = movement.replace(" ", "")
            val kind = when {
                normalizedMovement.startsWith("إد.م:") || normalizedMovement.startsWith("اد.م:") -> MovementKind.TRANSFER_INPUT
                normalizedMovement.startsWith("إخ.م:") || normalizedMovement.startsWith("اخ.م:") -> MovementKind.TRANSFER_OUTPUT
                normalizedMovement.startsWith("مبيع:") || normalizedMovement.startsWith("مبيع") -> MovementKind.SALE
                else -> continue
            }
            val quantityColumn = (if (kind == MovementKind.TRANSFER_INPUT) inputColumn else outputColumn)
                ?: throw IllegalArgumentException("ترويسة كمية الإدخالات أو الإخراجات غير موجودة")
            val quantity = parseDouble(cells.getOrNull(quantityColumn).orEmpty())
            if (quantity == 0.0) continue
            val salesperson = cells.getOrNull(salespersonColumn ?: throw IllegalArgumentException("ترويسة المستودع غير موجودة")).orEmpty()
                .trim()
                .removePrefix("مستودع ")
                .removePrefix("مستودع")
                .trim()
            result += Invoice(
                date = date,
                invoiceNumber = movement,
                customerName = "",
                invoiceType = movement,
                customerType = "",
                itemCode = currentItemCode,
                itemName = currentItemName.orEmpty(),
                price = 0L,
                quantity = quantity,
                unit = currentUnit,
                region = "",
                salesperson = salesperson,
            )
        }
        return result
    }

    private fun parseFlatSheet(sheet: Sheet, onMissing: (List<String>) -> Unit): List<Invoice> {
        val formatter = DataFormatter(Locale.US)
        val rows = sheet.iterator()
        var columns: Map<String, Int>? = null
        var flatColumns: Map<String, Int>? = null
        while (rows.hasNext()) {
            val row = rows.next()
            val headers = rowStrings(row, formatter)
            val normalizedHeaders = headers.mapIndexed { index, header ->
                SchemaValidator.normalizeHeader(header) to index
            }.toMap()
            val detectedFlatColumns = FLAT_HEADER_ALIASES.mapValues { (_, aliases) ->
                aliases.asSequence()
                    .map { SchemaValidator.normalizeHeader(it) }
                    .mapNotNull { normalizedHeaders[it] }
                    .firstOrNull()
            }
            if (detectedFlatColumns.values.all { it != null }) {
                flatColumns = detectedFlatColumns
                    .mapKeys { SchemaValidator.normalizeHeader(it.key) }
                    .mapValues { it.value!! }
                break
            }
            when (val result = SchemaValidator.validate(headers)) {
                ValidationResult.Valid -> {
                    columns = headers.mapIndexed { index, header ->
                        SchemaValidator.normalizeHeader(header) to index
                    }.toMap()
                    break
                }
                is ValidationResult.Missing -> onMissing(result.columns)
            }
        }
        if (flatColumns != null) {
            val indexes = flatColumns
            val result = mutableListOf<Invoice>()
            while (rows.hasNext()) {
                val row = rows.next()
                fun value(header: String): String = cellText(row.getCell(indexes.getValue(SchemaValidator.normalizeHeader(header))), formatter)
                val date = parseDate(row.getCell(indexes.getValue(SchemaValidator.normalizeHeader("التاريخ"))), value("التاريخ"), formatter) ?: continue
                val itemCode = value("رمز المادة")
                val itemName = value("اسم المادة")
                val invoiceType = value("نوع الفاتورة")
                if (itemCode.isBlank() || invoiceType.isBlank()) continue
                val salesperson = value("المستودع")
                    .removePrefix("مستودع ")
                    .removePrefix("مستودع")
                    .trim()
                result += Invoice(
                    date = date,
                    invoiceNumber = "",
                    customerName = "",
                    invoiceType = invoiceType,
                    customerType = "",
                    itemCode = itemCode,
                    itemName = itemName,
                    price = 0L,
                    quantity = kotlin.math.abs(parseDouble(value("الكمية"))),
                    unit = value("الوحدة"),
                    region = "",
                    salesperson = salesperson,
                )
            }
            return result
        }
        val indexes = columns ?: return emptyList()
        val result = mutableListOf<Invoice>()
        while (rows.hasNext()) {
            val row = rows.next()
            fun value(header: String) = cellText(row.getCell(indexes.getValue(SchemaValidator.normalizeHeader(header))), formatter)
            val date = parseDate(row.getCell(indexes.getValue(SchemaValidator.normalizeHeader("التاريخ"))), value("التاريخ"), formatter) ?: continue
            val itemCode = value("رمز المادة")
            val itemName = value("اسم المادة")
            if (itemCode.isBlank() && itemName.isBlank()) continue
            result += Invoice(date, value("رقم الفاتورة"), value("اسم الزبون"), value("نوع الفاتورة"), value("نوع الزبون"), itemCode, itemName, parseLong(value("سعر المادة")), parseDouble(value("الكمية")), value("وحدة المادة"), value("المنطقة"), value("البائع"))
        }
        return result
    }

    private fun rowStrings(row: Row, formatter: DataFormatter): List<String> {
        val last = maxOf(row.lastCellNum.toInt(), 18)
        return (0 until last).map { cellText(row.getCell(it), formatter) }
    }

    private fun cellText(cell: Cell?, formatter: DataFormatter): String {
        if (cell == null || cell.cellType == Cell.CELL_TYPE_BLANK) return ""
        return formatter.formatCellValue(cell).trim()
    }

    private fun parseDate(cell: Cell?, text: String, formatter: DataFormatter): Long? {
        if (cell?.cellType == Cell.CELL_TYPE_NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return DateUtil.getJavaDate(cell.numericCellValue).time
        }
        val value = if (cell?.cellType == Cell.CELL_TYPE_NUMERIC) formatter.formatCellValue(cell) else text.trim()
        val formats = listOf("yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "yyyy/MM/dd", "dd.MM.yyyy")
        formats.forEach { pattern ->
            val position = ParsePosition(0)
            val date = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value, position)
            if (date != null && position.index == value.length) return date.time
        }
        return runCatching { SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US).parse(value)?.time }.getOrNull()
    }

    private fun parseLong(value: String): Long = value.replace(",", "").trim().toDoubleOrNull()?.toLong() ?: 0L
    private fun parseDouble(value: String): Double = value.replace(",", "").trim().toDoubleOrNull() ?: 0.0

    companion object {
        private val ITEM_HEADER = Regex("^\\s*(\\d+)\\s*[-–—]\\s*(.+?)\\s*$")
        private val FLAT_HEADER_ALIASES = linkedMapOf(
            "التاريخ" to listOf("التاريخ", "date"),
            "رمز المادة" to listOf("رمز المادة", "كود المادة", "item code"),
            "اسم المادة" to listOf("اسم المادة", "المادة", "item name"),
            "الكمية" to listOf("الكمية", "quantity"),
            "الوحدة" to listOf("الوحدة", "وحدة المادة", "unit"),
            "نوع الفاتورة" to listOf("نوع الفاتورة", "نوع الحركة", "invoice type"),
            "المستودع" to listOf("المستودع", "مستودع القلم", "البائع", "warehouse"),
        )
    }
}
