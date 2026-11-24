package com.example.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTColor
import java.io.File

internal data class ExportReport(
    val salesperson: String,
    val rows: List<ReportRow>,
    val retailRows: List<RetailReportRow>,
)

internal fun sharePngReport(
    context: Context,
    salesperson: String,
    rows: List<ReportRow>,
    mode: String,
    retailRows: List<RetailReportRow>,
) {
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(dir, "report-${salesperson.hashCode()}.png")
    writePng(context, android.net.Uri.fromFile(file), rows, mode, retailRows, salesperson)
    shareFile(context, file, "image/png", "تقرير $salesperson")
}

internal fun shareXlsx(
    context: Context,
    reports: List<ExportReport>,
    title: String,
    mode: String = "جملة",
) {
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(dir, "warehouse-report.xlsx")
    XSSFWorkbook().use { workbook ->
        fun rgb(red: Int, green: Int, blue: Int): XSSFColor {
            val color = CTColor.Factory.newInstance()
            color.rgb = byteArrayOf(red.toByte(), green.toByte(), blue.toByte())
            return XSSFColor(color)
        }
        val headerStyle = workbook.createCellStyle().apply {
            setFillForegroundColor(rgb(15, 153, 168))
            setFillPattern(FillPatternType.SOLID_FOREGROUND)
            setAlignment(HorizontalAlignment.CENTER)
            setBorderTop(BorderStyle.THIN)
            setBorderBottom(BorderStyle.THIN)
            setBorderLeft(BorderStyle.THIN)
            setBorderRight(BorderStyle.THIN)
        }
        val headerFont = workbook.createFont().apply {
            bold = true
            color = IndexedColors.WHITE.index
        }
        headerStyle.setFont(headerFont)

        fun bodyStyle(alternate: Boolean): XSSFCellStyle = workbook.createCellStyle().apply {
            if (alternate) {
                setFillForegroundColor(rgb(244, 247, 248))
                setFillPattern(FillPatternType.SOLID_FOREGROUND)
            }
            setAlignment(HorizontalAlignment.CENTER)
            setBorderTop(BorderStyle.THIN)
            setBorderBottom(BorderStyle.THIN)
            setBorderLeft(BorderStyle.THIN)
            setBorderRight(BorderStyle.THIN)
        }
        val totalStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.LIGHT_TURQUOISE.index
            setFillPattern(FillPatternType.SOLID_FOREGROUND)
            setAlignment(HorizontalAlignment.CENTER)
            setBorderTop(BorderStyle.THIN)
            setBorderBottom(BorderStyle.THIN)
            setBorderLeft(BorderStyle.THIN)
            setBorderRight(BorderStyle.THIN)
        }
        val totalFont = workbook.createFont().apply { bold = true; color = IndexedColors.DARK_BLUE.index }
        totalStyle.setFont(totalFont)

        val headings = if (mode == "مفرق") listOf("التاريخ", "المادة", "طرد", "علبة")
        else listOf("التاريخ", "المادة", "الوحدة", "تحميل", "مرتجع", "مبيع")
        val widths = if (mode == "مفرق") listOf(14, 32, 14, 14) else listOf(14, 32, 12, 14, 14, 14)

        reports.forEachIndexed { reportIndex, report ->
            val baseName = report.salesperson.ifBlank { "مندوب" }
                .replace(Regex("[\\\\/?*\\[\\]:]"), "")
                .take(28)
                .ifBlank { "مندوب" }
            val sheet = workbook.createSheet("${baseName}_${reportIndex + 1}".take(31))
            sheet.setRightToLeft(true)
            sheet.createFreezePane(0, 1)
            val headerRow = sheet.createRow(0)
            headings.forEachIndexed { index, heading ->
                sheet.setColumnWidth(index, widths[index] * 256)
                headerRow.createCell(index).apply {
                    setCellValue(heading)
                    cellStyle = headerStyle
                }
            }
            if (mode == "مفرق") {
                report.retailRows.forEachIndexed { rowIndex, row ->
                    val excelRow = sheet.createRow(rowIndex + 1)
                    val style = bodyStyle(rowIndex % 2 == 1)
                    listOf(row.date, row.material).forEachIndexed { index, value ->
                        excelRow.createCell(index).apply { setCellValue(value); cellStyle = style }
                    }
                    excelRow.createCell(2).apply { setCellValue(row.carton.toDouble()); cellStyle = style }
                    excelRow.createCell(3).apply { setCellValue(row.box.toDouble()); cellStyle = style }
                }
                val totalRow = sheet.createRow(report.retailRows.size + 1)
                listOf("", "الإجمالي", report.retailRows.sumOf { it.carton }.toDouble(), report.retailRows.sumOf { it.box }.toDouble())
                    .forEachIndexed { index, value -> totalRow.createCell(index).apply { if (value is String) setCellValue(value) else setCellValue(value as Double); cellStyle = totalStyle } }
            } else {
                report.rows.forEachIndexed { rowIndex, row ->
                    val excelRow = sheet.createRow(rowIndex + 1)
                    val style = bodyStyle(rowIndex % 2 == 1)
                    listOf(row.date, row.material, row.unit).forEachIndexed { index, value ->
                        excelRow.createCell(index).apply { setCellValue(value); cellStyle = style }
                    }
                    listOf(row.loading, row.returned, row.sale).forEachIndexed { offset, value ->
                        excelRow.createCell(offset + 3).apply { setCellValue(value.toDouble()); cellStyle = style }
                    }
                }
                val totalRow = sheet.createRow(report.rows.size + 1)
                listOf("", "الإجمالي", "", report.rows.sumOf { it.loading }.toDouble(), report.rows.sumOf { it.returned }.toDouble(), report.rows.sumOf { it.sale }.toDouble())
                    .forEachIndexed { index, value -> totalRow.createCell(index).apply { if (value is String) setCellValue(value) else setCellValue(value as Double); cellStyle = totalStyle } }
            }
        }
        file.outputStream().use { workbook.write(it) }
    }
    shareFile(context, file, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", title)
}

internal fun shareFile(context: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "مشاركة التقرير"))
}
