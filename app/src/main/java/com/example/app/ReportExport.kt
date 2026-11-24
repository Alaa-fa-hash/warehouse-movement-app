package com.example.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File

internal data class ExportReport(
    val salesperson: String,
    val rows: List<ReportRow>,
    val retailRows: List<RetailReportRow>,
)

internal fun sharePngReport(context: Context, salesperson: String, rows: List<ReportRow>) {
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(dir, "report-${salesperson.hashCode()}.png")
    writePng(context, android.net.Uri.fromFile(file), rows)
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
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.TEAL.index
            setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND)
            setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER)
        }
        val headings = if (mode == "مفرق") listOf("التاريخ", "المادة", "طرد", "علبة") else listOf("التاريخ", "المادة", "الوحدة", "تحميل", "مرتجع", "مبيع")
        reports.forEachIndexed { reportIndex, report ->
            val baseName = report.salesperson.ifBlank { "مندوب" }.replace(Regex("[\\\\/?*\\[\\]:]"), "").take(28).ifBlank { "مندوب" }
            val sheet = workbook.createSheet("${baseName}_${reportIndex + 1}".take(31))
            val header = sheet.createRow(0)
            headings.forEachIndexed { i, value ->
                header.createCell(i).apply { setCellValue(value); cellStyle = headerStyle }
            }
            if (mode == "مفرق") {
                report.retailRows.forEachIndexed { rowIndex, row ->
                    val r = sheet.createRow(rowIndex + 1)
                    listOf(row.date, row.material).forEachIndexed { i, value -> r.createCell(i).setCellValue(value) }
                    r.createCell(2).setCellValue(row.carton.toDouble())
                    r.createCell(3).setCellValue(row.box.toDouble())
                }
            } else {
                report.rows.forEachIndexed { rowIndex, row ->
                    val r = sheet.createRow(rowIndex + 1)
                    listOf(row.date, row.material, row.unit).forEachIndexed { i, value -> r.createCell(i).setCellValue(value) }
                    r.createCell(3).setCellValue(row.loading.toDouble())
                    r.createCell(4).setCellValue(row.returned.toDouble())
                    r.createCell(5).setCellValue(row.sale.toDouble())
                }
            }
            for (i in headings.indices) sheet.autoSizeColumn(i)
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
