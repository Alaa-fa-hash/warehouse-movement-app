package com.example.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File

internal fun sharePngReport(context: Context, salesperson: String, rows: List<ReportRow>) {
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(dir, "report-${salesperson.hashCode()}.png")
    writePng(context, android.net.Uri.fromFile(file), rows)
    shareFile(context, file, "image/png", "تقرير $salesperson")
}

internal fun shareXlsx(context: Context, rows: List<ReportRow>, title: String) {
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(dir, "warehouse-report.xlsx")
    XSSFWorkbook().use { workbook ->
        val sheet = workbook.createSheet("التقرير")
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.TEAL.index
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
        }
        val header = sheet.createRow(0)
        listOf("التاريخ", "المادة", "الوحدة", "تحميل", "مرتجع", "مبيع").forEachIndexed { i, value ->
            header.createCell(i).apply { setCellValue(value); cellStyle = headerStyle }
        }
        rows.forEachIndexed { rowIndex, row ->
            val r = sheet.createRow(rowIndex + 1)
            listOf(row.date, row.material, row.unit).forEachIndexed { i, value -> r.createCell(i).setCellValue(value) }
            r.createCell(3).setCellValue(row.loading.toDouble())
            r.createCell(4).setCellValue(row.returned.toDouble())
            r.createCell(5).setCellValue(row.sale.toDouble())
        }
        for (i in 0..5) sheet.autoSizeColumn(i)
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
