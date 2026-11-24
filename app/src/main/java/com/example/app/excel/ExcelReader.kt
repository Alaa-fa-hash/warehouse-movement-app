package com.example.app.excel

import android.content.ContentResolver
import android.net.Uri
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExcelReader(private val contentResolver: ContentResolver) {
    fun read(uri: Uri): List<Invoice> {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("تعذر فتح ملف Excel")

        input.use { stream ->
            WorkbookFactory.create(stream).use { workbook ->
                var lastMissingColumns: List<String>? = null
                for (sheet in workbook) {
                    // لا نقيّم الصيغ أثناء الاستيراد؛ بعض ملفات Excel تحتوي صيغاً أو
                    // مراجع خارجية لا يدعمها POI على Android وقد تسبب انهيار القراءة.
                    val formatter = DataFormatter(Locale.US)
                    val rows = sheet.iterator()
                    var headerRow: Row? = null
                    var columnIndexes: Map<String, Int> = emptyMap()

                    while (rows.hasNext()) {
                        val row = rows.next()
                        val headers = rowToStrings(row, formatter)
                        when (val result = SchemaValidator.validate(headers)) {
                            ValidationResult.Valid -> {
                                headerRow = row
                                columnIndexes = headers.mapIndexed { index, header ->
                                    SchemaValidator.normalizeHeader(header) to index
                                }.toMap()
                                break
                            }
                            is ValidationResult.Missing -> {
                                lastMissingColumns = result.columns
                                continue
                            }
                        }
                    }

                    if (headerRow == null) continue
                    val result = mutableListOf<Invoice>()
                    while (rows.hasNext()) {
                        val row = rows.next()
                        if (rowIsBlank(row, formatter)) continue
                        parseRow(row, columnIndexes, formatter)?.let(result::add)
                    }
                    return result
                }
                lastMissingColumns?.let { throw MissingColumnsException(it) }
            }
        }
        return emptyList()
    }

    private fun parseRow(
        row: Row,
        columns: Map<String, Int>,
        formatter: DataFormatter,
    ): Invoice? {
        fun value(header: String): String = cellText(row.getCell(columns.getValue(SchemaValidator.normalizeHeader(header))), formatter)
        val date = parseDate(row.getCell(columns.getValue(SchemaValidator.normalizeHeader("التاريخ"))), value("التاريخ"), formatter)
            ?: return null
        val itemCode = value("رمز المادة").trim()
        val itemName = value("اسم المادة").trim()
        if (itemCode.isBlank() && itemName.isBlank()) return null

        return Invoice(
            date = date,
            invoiceNumber = value("رقم الفاتورة"),
            customerName = value("اسم الزبون"),
            invoiceType = value("نوع الفاتورة"),
            customerType = value("نوع الزبون"),
            itemCode = itemCode,
            itemName = itemName,
            price = parseLong(value("سعر المادة")),
            quantity = parseDouble(value("الكمية")),
            unit = value("وحدة المادة"),
            region = value("المنطقة"),
            salesperson = value("البائع"),
        )
    }

    private fun rowToStrings(
        row: Row,
        formatter: DataFormatter,
    ): List<String> {
        val last = maxOf(row.lastCellNum.toInt(), SchemaValidator.requiredColumns.size)
        return (0 until last).map { cellText(row.getCell(it), formatter) }
    }

    private fun rowIsBlank(
        row: Row,
        formatter: DataFormatter,
    ): Boolean = rowToStrings(row, formatter).all(String::isBlank)

    private fun cellText(
        cell: Cell?,
        formatter: DataFormatter,
    ): String {
        if (cell == null || cell.cellType == Cell.CELL_TYPE_BLANK) return ""
        return formatter.formatCellValue(cell).trim()
    }

    private fun parseDate(
        cell: Cell?,
        text: String,
        formatter: DataFormatter,
    ): Long? {
        if (cell?.cellType == Cell.CELL_TYPE_NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return DateUtil.getJavaDate(cell.numericCellValue).time
        }
        val value = if (cell?.cellType == Cell.CELL_TYPE_NUMERIC) formatter.formatCellValue(cell) else text
        val formats = listOf("yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "yyyy/MM/dd", "dd.MM.yyyy")
        formats.forEach { pattern ->
            val position = ParsePosition(0)
            val date = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value, position)
            if (date != null && position.index == value.length) return date.time
        }
        return null
    }

    private fun parseLong(value: String): Long = value.replace(",", "").trim().toDoubleOrNull()?.toLong() ?: 0L

    private fun parseDouble(value: String): Double = value.replace(",", "").trim().toDoubleOrNull() ?: 0.0
}
