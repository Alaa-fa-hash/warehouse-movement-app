package com.example.app.excel

import android.content.ContentResolver
import android.net.Uri
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
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
                    val formatter = DataFormatter(Locale("ar"))
                    val evaluator = workbook.creationHelper.createFormulaEvaluator()
                    val rows = sheet.iterator()
                    var headerRow: Row? = null
                    var columnIndexes: Map<String, Int> = emptyMap()

                    while (rows.hasNext()) {
                        val row = rows.next()
                        val headers = rowToStrings(row, formatter, evaluator)
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
                        if (rowIsBlank(row, formatter, evaluator)) continue
                        parseRow(row, columnIndexes, formatter, evaluator)?.let(result::add)
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
        evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator,
    ): Invoice? {
        fun value(header: String): String = cellText(row.getCell(columns.getValue(SchemaValidator.normalizeHeader(header))), formatter, evaluator)
        val date = parseDate(row.getCell(columns.getValue(SchemaValidator.normalizeHeader("التاريخ"))), value("التاريخ"), formatter, evaluator)
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
        evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator,
    ): List<String> {
        val last = maxOf(row.lastCellNum.toInt(), SchemaValidator.requiredColumns.size)
        return (0 until last).map { cellText(row.getCell(it), formatter, evaluator) }
    }

    private fun rowIsBlank(
        row: Row,
        formatter: DataFormatter,
        evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator,
    ): Boolean = rowToStrings(row, formatter, evaluator).all(String::isBlank)

    private fun cellText(
        cell: Cell?,
        formatter: DataFormatter,
        evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator,
    ): String {
        if (cell == null || cell.cellType == CellType.BLANK) return ""
        return formatter.formatCellValue(cell, evaluator).trim()
    }

    private fun parseDate(
        cell: Cell?,
        text: String,
        formatter: DataFormatter,
        evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator,
    ): Long? {
        if (cell?.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return DateUtil.getJavaDate(cell.numericCellValue).time
        }
        val value = if (cell?.cellType == CellType.NUMERIC) formatter.formatCellValue(cell, evaluator) else text
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
