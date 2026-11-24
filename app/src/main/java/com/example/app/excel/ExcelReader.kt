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
import java.util.Date
import java.util.Locale

/** Reads both the original flat export and the real pivot-style movement report. */
class ExcelReader(private val contentResolver: ContentResolver) {
    fun read(uri: Uri): List<Invoice> {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("تعذر فتح ملف Excel")

        input.use { stream ->
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
        }
        return emptyList()
    }

    private fun parsePivotSheet(sheet: Sheet): List<Invoice> {
        val formatter = DataFormatter(Locale.US)
        var dateColumn = 1
        var invoiceColumn = 6
        var inputColumn = 7
        var outputColumn = 9
        var unitColumn = 15
        var salespersonColumn = 16
        var sawPivotLayout = false
        var currentItemCode: String? = null
        var currentItemName: String? = null
        var currentUnit = ""
        val result = mutableListOf<Invoice>()

        for (row in sheet) {
            val cells = rowStrings(row, formatter)
            val lower = cells.map { it.trim().lowercase() }

            // Discover the real report columns from its repeated transaction header.
            val dateHeader = lower.indexOfFirst { it == "التاريخ" || it == "date" }
            val invoiceHeader = lower.indexOfFirst { it == "فاتورة" || it == "الفاتورة" || it == "invoice" }
            val unitHeader = lower.indexOfFirst { it == "الوحدة" || it == "unit" }
            val warehouseHeader = lower.indexOfFirst { it == "المستودع" || it == "warehouse" }
            if (dateHeader >= 0 && invoiceHeader >= 0) {
                dateColumn = dateHeader
                invoiceColumn = invoiceHeader
                sawPivotLayout = true
            }
            if (unitHeader >= 0) unitColumn = unitHeader
            if (warehouseHeader >= 0) salespersonColumn = warehouseHeader
            val inputHeader = lower.indexOfFirst { it == "الإدخالات" || it == "الادخالات" }
            val outputHeader = lower.indexOfFirst { it == "الإخراجات" || it == "الاخراجات" }
            if (inputHeader >= 0) inputColumn = inputHeader
            if (outputHeader >= 0) outputColumn = outputHeader

            // A material block header is in column A: 0002-اسم المادة.
            val itemMatch = ITEM_HEADER.matchEntire(cells.getOrNull(0).orEmpty())
            if (itemMatch != null) {
                currentItemCode = itemMatch.groupValues[1]
                currentItemName = itemMatch.groupValues[2].trim()
                currentUnit = cells.getOrNull(unitColumn).orEmpty().trim()
                sawPivotLayout = true
                continue
            }

            if (!sawPivotLayout || currentItemCode == null) continue
            val dateCell = row.getCell(dateColumn)
            val dateText = cells.getOrNull(dateColumn).orEmpty()
            val date = parseDate(dateCell, dateText, formatter) ?: continue
            val movement = cells.getOrNull(invoiceColumn).orEmpty().trim()
            val normalizedMovement = movement.replace(" ", "")
            val kind = when {
                normalizedMovement.startsWith("إد.م:") || normalizedMovement.startsWith("اد.م:") -> MovementKind.TRANSFER_INPUT
                normalizedMovement.startsWith("إخ.م:") || normalizedMovement.startsWith("اخ.م:") -> MovementKind.TRANSFER_OUTPUT
                normalizedMovement.startsWith("مبيع:") || normalizedMovement.startsWith("مبيع") -> MovementKind.SALE
                else -> continue
            }
            val quantityColumn = if (kind == MovementKind.TRANSFER_INPUT) inputColumn else outputColumn
            val quantity = parseDouble(cells.getOrNull(quantityColumn).orEmpty())
            if (quantity == 0.0) continue
            val salesperson = cells.getOrNull(salespersonColumn).orEmpty()
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
        while (rows.hasNext()) {
            val row = rows.next()
            val headers = rowStrings(row, formatter)
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
    }
}
