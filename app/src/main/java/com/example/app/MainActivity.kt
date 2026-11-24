package com.example.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale
import com.example.app.excel.ExcelReader
import com.example.app.excel.Invoice
import com.example.app.excel.MovementKind
import com.example.app.excel.SalesMode
import com.example.app.excel.filterForMode
import com.example.app.excel.movementKind
import com.example.app.excel.defaultAlias

private val Navy = Color(0xFF10283D)
private val Teal = Color(0xFF1196A7)
private val Background = Color(0xFFF7F3EA)
private val PaleBlue = Color(0xFFDFF1F5)
private val Border = Color(0xFFD7E2E6)
private val Green = Color(0xFF2E7D50)

internal data class ReportRow(
    val date: String,
    val material: String,
    val unit: String,
    val loading: Int,
    val returned: Int,
    val sale: Int,
)

internal data class RetailReportRow(
    val date: String,
    val material: String,
    val carton: Int,
    val box: Int,
)

private val demoRows = listOf(
    ReportRow("2026-11-23", "كيكتي", "طرد", 20, 5, 15),
    ReportRow("2026-11-23", "دونيستا", "طرد", 12, 3, 9),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WarehouseApp() }
    }
}

@Composable
private fun WarehouseApp() {
    var selectedMode by remember { mutableStateOf("جملة") }
    var selectedFile by remember { mutableStateOf("مثال تجريبي — استورد ملف Excel للبيانات الفعلية") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var invoices by remember { mutableStateOf<List<Invoice>>(emptyList()) }
    var selectedSalesperson by remember { mutableStateOf<String?>(null) }
    var isReading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val excelReader = remember(context) { ExcelReader(context.contentResolver) }
    val preferences = remember(context) { context.getSharedPreferences("warehouse_preferences", android.content.Context.MODE_PRIVATE) }
    val savedImportFile = remember(context) { File(context.filesDir, "imports/last-report.xlsx") }
    var priorityCodes by remember {
        mutableStateOf(preferences.getString("priority_codes", "")!!.split('|').filter { it.isNotBlank() }.take(4))
    }
    var aliases by remember {
        mutableStateOf(preferences.getString("aliases", "")!!.split('|').mapNotNull { entry ->
            entry.split("=", limit = 2).takeIf { it.size == 2 }
        }.associate { it[0] to it[1] })
    }
    var guideOpen by remember { mutableStateOf(false) }
    var aliasCode by remember { mutableStateOf<String?>(null) }
    var aliasText by remember { mutableStateOf("") }
    var exportRows by remember { mutableStateOf<List<ReportRow>>(emptyList()) }
    LaunchedEffect(context) {
        selectedMode = preferences.getString("sales_mode", "جملة") ?: "جملة"
        selectedFile = preferences.getString("last_file_name", "")?.takeIf { it.isNotBlank() }
            ?: "مثال تجريبي — استورد ملف Excel للبيانات الفعلية"
        if (savedImportFile.exists()) {
            isReading = true
            runCatching { withContext(Dispatchers.IO) { excelReader.readFile(savedImportFile) } }
                .onSuccess { restored -> invoices = restored }
                .onFailure { savedImportFile.delete() }
            isReading = false
        }
    }
    val csvExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { runCatching { writeCsv(context, uri, exportRows) }.onSuccess { snackbarHostState.showSnackbar("تم تصدير ملف Excel بصيغة CSV") }.onFailure { snackbarHostState.showSnackbar("فشل تصدير Excel: ${it.message}") } }
    }
    val pngExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch { runCatching { writePng(context, uri, exportRows) }.onSuccess { snackbarHostState.showSnackbar("تم تصدير التقرير PNG") }.onFailure { snackbarHostState.showSnackbar("فشل تصدير PNG: ${it.message}") } }
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            pickedUri = uri
            if (uri != null) {
                // بعض مزوّدي الملفات يعيدون Uri بلا اسم ملف أو بلا صلاحية دائمة.
                // لا نسمح لفشل هذه الخطوة بإسقاط التطبيق.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                selectedFile = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: "ملف Excel محدد"
                preferences.edit().putString("last_file_name", selectedFile).apply()
                scope.launch {
                    isReading = true
                    try {
                        val parsed = withContext(Dispatchers.IO) {
                            savedImportFile.parentFile?.mkdirs()
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                savedImportFile.outputStream().use { output -> input.copyTo(output) }
                            } ?: throw IllegalArgumentException("تعذر فتح ملف Excel")
                            excelReader.readFile(savedImportFile)
                        }
                        invoices = parsed
                        selectedSalesperson = null
                        snackbarHostState.showSnackbar("تمت قراءة ${parsed.size} حركة من ملف Excel")
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        val detail = error.message?.takeIf { it.isNotBlank() }
                        val message = if (detail != null) {
                            "فشل استيراد Excel: $detail"
                        } else {
                            "فشل استيراد Excel (${error::class.java.simpleName})"
                        }
                        snackbarHostState.showSnackbar(message)
                    } finally {
                        isReading = false
                    }
                }
            }
        },
    )
    val mode = if (selectedMode == "مفرق") SalesMode.RETAIL else SalesMode.WHOLESALE
    val salespeople = invoices.map { it.salesperson }.filter(String::isNotBlank).distinct().sorted()
    val filteredInvoices = invoices.filterForMode(mode, selectedSalesperson)
    val priorityOrder = priorityCodes.withIndex().associate { it.value to it.index }
    val orderedInvoices = filteredInvoices.sortedWith(compareBy({ priorityOrder[it.itemCode] ?: 1000 }, { it.itemName }, { it.unit }))
    val reportGroups = orderedInvoices.groupBy { it.salesperson.ifBlank { "غير محدد" } }
    val reportRows = invoiceRows(orderedInvoices, aliases, priorityOrder).ifEmpty { demoRows }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Teal,
            background = Background,
            surface = Color.White,
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor = Background,
            ) { innerPadding ->
                Surface(modifier = Modifier.fillMaxSize().padding(innerPadding), color = Background) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 36.dp),
                    ) {
                    item {
                        Header()
                        ActionRow(
                            onPickFile = { if (!isReading) filePicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/*")) },
                            onExport = { guideOpen = true },
                            isReading = isReading,
                        )
                        FileCard(
                            fileName = selectedFile,
                            hasFile = pickedUri != null || invoices.isNotEmpty(),
                            movementCount = invoices.size,
                            materialCount = invoices.map { it.itemCode }.filter(String::isNotBlank).distinct().size,
                            salespersonCount = salespeople.size,
                        )
                        Filters(
                            selectedMode = selectedMode,
                            onModeChange = { selectedMode = it; preferences.edit().putString("sales_mode", it).apply() },
                            salespeople = salespeople,
                            selectedSalesperson = selectedSalesperson,
                            onSalespersonChange = { selectedSalesperson = it },
                        )
                        PriorityMaterials(
                            materials = invoices.distinctBy { it.itemCode }.filter { it.itemCode.isNotBlank() },
                            selectedCodes = priorityCodes,
                            aliases = aliases,
                            onChange = { codes ->
                                priorityCodes = codes
                                preferences.edit().putString("priority_codes", codes.joinToString("|")).apply()
                            },
                        )
                        ReportsHeader(
                            mode = selectedMode,
                            filteredCount = filteredInvoices.size,
                            onExportExcel = {
                                val exports = reportGroups.map { (person, personInvoices) ->
                                    ExportReport(person, invoiceRows(personInvoices, aliases, priorityOrder), retailRows(personInvoices, aliases, priorityOrder))
                                }.ifEmpty { listOf(ExportReport("كل المندوبين", reportRows, retailRows(orderedInvoices, aliases, priorityOrder))) }
                                shareXlsx(context, exports, "تقرير حركة المندوبين", selectedMode)
                            },
                        )
                    }
                    if (reportGroups.isEmpty()) {
                        item {
                            ReportCard(
                                mode = selectedMode,
                                salespersonName = "كل المندوبين",
                                rows = reportRows,
                                retailRows = retailRows(orderedInvoices, aliases, priorityOrder),
                                onExportPng = { exportRows = reportRows; pngExporter.launch("warehouse-report.png") },
                            )
                        }
                    } else {
                        reportGroups.forEach { (person, personInvoices) ->
                            item {
                                val personRows = invoiceRows(personInvoices, aliases, priorityOrder).ifEmpty { demoRows }
                                ReportCard(
                                    mode = selectedMode,
                                    salespersonName = person,
                                    rows = personRows,
                                    retailRows = retailRows(personInvoices, aliases, priorityOrder),
                                    onExportPng = { sharePngReport(context, person, personRows, selectedMode, retailRows(personInvoices, aliases, priorityOrder)) },
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            text = "الإصدار الأول — محرك قراءة Excel الكامل قيد الإكمال داخل النسخة Native.",
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            textAlign = TextAlign.Center,
                            color = Color(0xFF687782),
                            fontSize = 12.sp,
                        )
                    }
                    }
                }
            }
        }
    }
    if (guideOpen) {
        AlertDialog(
            onDismissRequest = { guideOpen = false },
            title = { Text("دليل المواد", color = Navy, fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn {
                    items(invoices.distinctBy { it.itemCode }.filter { it.itemCode.isNotBlank() }) { material ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Text(material.itemName, color = Navy, textAlign = TextAlign.End)
                                Text(material.itemCode, color = Color(0xFF687782), fontSize = 11.sp)
                            }
                            TextButton(onClick = {
                                aliasCode = material.itemCode
                                aliasText = aliases[material.itemCode] ?: material.defaultAlias()
                            }) { Text(aliases[material.itemCode] ?: material.defaultAlias(), color = Teal, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { guideOpen = false }) { Text("إغلاق") } },
        )
    }
    if (aliasCode != null) {
        AlertDialog(
            onDismissRequest = { aliasCode = null },
            title = { Text("تعديل الاختصار") },
            text = { OutlinedTextField(value = aliasText, onValueChange = { aliasText = it }, label = { Text("الاسم المختصر") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val code = aliasCode ?: return@TextButton
                    val next = aliases.toMutableMap().apply { put(code, aliasText.trim()) }
                    aliases = next
                    preferences.edit().putString("aliases", next.entries.joinToString("|") { "${it.key}=${it.value}" }).apply()
                    aliasCode = null
                }) { Text("حفظ", color = Teal) }
            },
            dismissButton = { TextButton(onClick = { aliasCode = null }) { Text("إلغاء") } },
        )
    }
}

private fun invoiceRows(
    invoices: List<Invoice>,
    aliases: Map<String, String> = emptyMap(),
    priorityOrder: Map<String, Int> = emptyMap(),
): List<ReportRow> {
    data class Key(val date: String, val code: String, val material: String, val unit: String)
    return invoices
        .groupBy { invoice ->
            Key(reportDate(invoice.date), invoice.itemCode, aliases[invoice.itemCode]?.takeIf(String::isNotBlank) ?: invoice.defaultAlias(), invoice.unit.trim())
        }
        .map { (key, movements) ->
            val loading = movements.filter { it.movementKind() == MovementKind.TRANSFER_INPUT }.sumOf { it.quantity.toInt() }
            val returned = movements.filter { it.movementKind() == MovementKind.TRANSFER_OUTPUT }.sumOf { it.quantity.toInt() }
            key to ReportRow(
                date = key.date,
                material = key.material,
                unit = key.unit,
                loading = loading,
                returned = returned,
                sale = loading - returned,
            )
        }
        .filter { (_, row) -> row.loading != 0 || row.returned != 0 || row.sale != 0 }
        .sortedWith(compareBy({ priorityOrder[it.first.code] ?: 1000 }, { -it.second.sale }, { it.second.date }, { it.second.material }, { it.second.unit }))
        .map { it.second }
        .take(60)
}

private fun retailRows(
    invoices: List<Invoice>,
    aliases: Map<String, String> = emptyMap(),
    priorityOrder: Map<String, Int> = emptyMap(),
): List<RetailReportRow> {
    data class Key(val date: String, val code: String, val material: String)
    return invoices
        .filter { it.movementKind() == MovementKind.SALE }
        .groupBy { Key(reportDate(it.date), it.itemCode, aliases[it.itemCode]?.takeIf(String::isNotBlank) ?: it.defaultAlias()) }
        .map { (key, movements) ->
            key to RetailReportRow(
                date = key.date,
                material = key.material,
                carton = movements.filter { normalizedUnit(it.unit).contains("طرد") }.sumOf { it.quantity.toInt() },
                box = movements.filter { normalizedUnit(it.unit).contains("علب") || normalizedUnit(it.unit).contains("علبه") }.sumOf { it.quantity.toInt() },
            )
        }
        .filter { (_, row) -> row.carton != 0 || row.box != 0 }
        .sortedWith(compareBy({ priorityOrder[it.first.code] ?: 1000 }, { -(it.second.carton + it.second.box) }, { it.second.date }, { it.second.material }))
        .map { it.second }
        .take(60)
}

private fun normalizedUnit(value: String): String = value.replace(Regex("\\s+"), "").trim()

private fun reportDate(epochMillis: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(epochMillis))

@Composable
private fun Header() {
    Column(
        modifier = Modifier.fillMaxWidth().background(Navy).padding(horizontal = 24.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text("حركة مستودع المندوبين", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("تحليل الإدخالات والإخراجات والمبيع الفعلي", color = Color(0xFFD4EDF1), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.width(84.dp).height(4.dp).background(Teal))
        Spacer(Modifier.height(12.dp))
        Text("ملف محلي — لا حاجة للإنترنت لمعالجة Excel", color = Color(0xFFB9CED7), fontSize = 12.sp)
    }
}

@Composable
private fun ActionRow(onPickFile: () -> Unit, onExport: () -> Unit, isReading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onPickFile,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal),
        ) {
            androidx.compose.material3.Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (isReading) "جارٍ قراءة Excel..." else "اختيار ملف Excel", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onExport,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("دليل المواد", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FileCard(
    fileName: String,
    hasFile: Boolean,
    movementCount: Int,
    materialCount: Int,
    salespersonCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = PaleBlue),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(if (hasFile) "تم اختيار الملف" else "الملف الحالي", color = Color(0xFF687782), fontSize = 12.sp)
                Text("$movementCount حركة", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("$materialCount مادة", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("$salespersonCount مندوب", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                Text(fileName, color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun Filters(
    selectedMode: String,
    onModeChange: (String) -> Unit,
    salespeople: List<String>,
    selectedSalesperson: String?,
    onSalespersonChange: (String?) -> Unit,
) {
    var salespersonMenuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("الفلاتر الأساسية", color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("غيّر طريقة الحساب من هنا", color = Color(0xFF687782), fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().background(Color(0xFFF1E8DB), RoundedCornerShape(12.dp)).padding(4.dp)) {
                listOf("جملة", "مفرق").forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text("مبيعات $mode", fontWeight = FontWeight.Bold) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Box {
                OutlinedButton(onClick = { salespersonMenuExpanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text("فلتر المندوبين — ${selectedSalesperson ?: "كل المندوبين"}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                }
                DropdownMenu(expanded = salespersonMenuExpanded, onDismissRequest = { salespersonMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("كل المندوبين") }, onClick = { onSalespersonChange(null); salespersonMenuExpanded = false })
                    salespeople.forEach { person ->
                        DropdownMenuItem(text = { Text(person) }, onClick = { onSalespersonChange(person); salespersonMenuExpanded = false })
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun PriorityMaterials(
    materials: List<Invoice>,
    selectedCodes: List<String>,
    aliases: Map<String, String>,
    onChange: (List<String>) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("المواد الأساسية", color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("تظهر أولًا في كل تقرير وتُحفظ تلقائيًا", color = Color(0xFF687782), fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until 4).forEach { slot ->
                    var expanded by remember { mutableStateOf(false) }
                    val selected = materials.firstOrNull { it.itemCode == selectedCodes.getOrNull(slot) }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${slot + 1}", color = Teal, fontWeight = FontWeight.Bold)
                                Text(selected?.let { aliases[it.itemCode]?.takeIf(String::isNotBlank) ?: it.defaultAlias() } ?: "اختر المادة", color = Navy, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            materials.forEach { material ->
                                DropdownMenuItem(
                                    text = { Text("${aliases[material.itemCode]?.takeIf(String::isNotBlank) ?: material.defaultAlias()} (${material.itemCode})") },
                                    onClick = {
                                        val next = selectedCodes.toMutableList()
                                        while (next.size < 4) next.add("")
                                        next[slot] = material.itemCode
                                        onChange(next.take(4))
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun ReportsHeader(mode: String, filteredCount: Int, onExportExcel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("$mode مبيعات — $filteredCount حركة", color = Navy, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        TextButton(onClick = onExportExcel) {
            androidx.compose.material3.Icon(Icons.Default.FileDownload, contentDescription = null)
            Text("تصدير Excel")
        }
    }
}

@Composable
private fun ReportCard(
    mode: String,
    salespersonName: String,
    rows: List<ReportRow>,
    retailRows: List<RetailReportRow>,
    onExportPng: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            val columnWeights = if (mode == "مفرق") listOf(1.35f, 1.25f, 1f, 1f) else listOf(1.35f, 1.25f, 1f, 1f, 1f, 1f)
            Column(Modifier.fillMaxWidth().background(PaleBlue).padding(22.dp), horizontalAlignment = Alignment.End) {
                Text(salespersonName, color = Navy, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("مبيعات $mode", color = Color(0xFF687782), fontSize = 15.sp)
                Text("2026-11-23 إلى 2026-11-23", color = Color(0xFF687782), fontSize = 14.sp)
            }
            if (mode == "مفرق") {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SummaryCell("المبيع", retailRows.sumOf { it.carton + it.box }.toString(), Green)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SummaryCell("الإدخالات", rows.sumOf { it.loading }.toString())
                    SummaryCell("الإخراجات", rows.sumOf { it.returned }.toString())
                    SummaryCell("المبيع", rows.sumOf { it.sale }.toString(), Green)
                }
            }
            HorizontalDivider(color = Border)
            Row(Modifier.fillMaxWidth().background(Teal).padding(vertical = 14.dp)) {
                val headings = if (mode == "مفرق") listOf("التاريخ", "المادة", "طرد", "علبة") else listOf("التاريخ", "المادة", "الوحدة", "تحميل", "مرتجع", "مبيع")
                headings.forEachIndexed { index, heading ->
                    Text(heading, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(columnWeights[index]), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                }
            }
            if (mode == "مفرق") retailRows.forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth().background(if (rowIndex % 2 == 0) Color.White else Color(0xFFF4F7F8)).padding(vertical = 14.dp)) {
                    val values = listOf(row.date, row.material, row.carton.toString(), row.box.toString())
                    values.forEachIndexed { index, value ->
                        Text(value, color = Navy, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(columnWeights[index]), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                    }
                }
                HorizontalDivider(color = Color(0xFFDFEAED))
            }
            if (mode != "مفرق") rows.forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth().background(if (rowIndex % 2 == 0) Color.White else Color(0xFFF4F7F8)).padding(vertical = 14.dp)) {
                    val values = listOf(row.date, row.material, row.unit, row.loading.toString(), row.returned.toString(), row.sale.toString())
                    values.forEachIndexed { index, value ->
                        Text(value, color = Navy, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(columnWeights[index]), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                    }
                }
                HorizontalDivider(color = Color(0xFFDFEAED))
            }
            Button(onClick = onExportPng, modifier = Modifier.fillMaxWidth().padding(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal)) {
                Text("تصدير التقرير PNG", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, color: Color = Navy) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Navy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

private fun writeCsv(context: android.content.Context, uri: Uri, rows: List<ReportRow>) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
        writer.write("\uFEFF")
        writer.write("التاريخ,المادة,الوحدة,تحميل,مرتجع,مبيع\n")
        rows.forEach { row ->
            writer.write(listOf(row.date, row.material, row.unit, row.loading, row.returned, row.sale).joinToString(",") { csvEscape(it.toString()) })
            writer.write("\n")
        }
    } ?: error("تعذر إنشاء ملف التصدير")
}

private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""

internal fun writePng(
    context: android.content.Context,
    uri: Uri,
    rows: List<ReportRow>,
    mode: String = "جملة",
    retailRows: List<RetailReportRow> = emptyList(),
    salesperson: String = "",
) {
    val logicalWidth = 412f
    val pixelRatio = 4f
    val width = (logicalWidth * pixelRatio).toInt()
    val scale = pixelRatio
    val headerHeight = 164f
    val summaryHeight = 70f
    val tableHeaderHeight = 46f
    val rowHeight = 50f
    val rowCount = if (mode == "مفرق") retailRows.size else rows.size
    val height = ((headerHeight + summaryHeight + tableHeaderHeight + (rowCount + 1) * rowHeight) * scale).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val navy = android.graphics.Color.rgb(16, 40, 61)
    val teal = android.graphics.Color.rgb(15, 153, 168)
    val pale = android.graphics.Color.rgb(237, 248, 249)
    val rowAlt = android.graphics.Color.rgb(244, 250, 250)
    val totalColor = android.graphics.Color.rgb(222, 242, 245)
    val muted = android.graphics.Color.rgb(150, 170, 176)
    val green = android.graphics.Color.rgb(46, 125, 80)
    val reportBold = Typeface.create("sans-serif", Typeface.BOLD)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        typeface = reportBold
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
        isSubpixelText = true
    }
    fun text(value: String, x: Float, y: Float, size: Float, color: Int = navy, align: Paint.Align = Paint.Align.CENTER) {
        paint.textSize = size * scale
        paint.color = color
        paint.textAlign = align
        paint.typeface = reportBold
        canvas.drawText(value, x * scale, y * scale, paint)
    }
    fun cellColor(value: String, index: Int): Int = when {
        value == "0" -> muted
        mode == "جملة" && index == 0 -> green
        else -> navy
    }
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.drawRect(0f, 0f, width.toFloat(), headerHeight * scale, paint.apply { color = pale })
    text(salesperson.ifBlank { if (mode == "مفرق") "مبيعات مفرق" else "حركة جملة" }, logicalWidth - 24f, 62f, 28f, navy, Paint.Align.RIGHT)
    text("التاريخ: ${rows.firstOrNull()?.date ?: retailRows.firstOrNull()?.date ?: ""}", logicalWidth - 24f, 106f, 18f, android.graphics.Color.rgb(104, 119, 130), Paint.Align.RIGHT)

    var y = headerHeight
    canvas.drawRect(0f, y * scale, width.toFloat(), (y + summaryHeight) * scale, paint.apply { color = android.graphics.Color.WHITE })
    if (mode == "مفرق") {
        text("المبيع: ${retailRows.sumOf { it.carton + it.box }}", logicalWidth / 2f, y + 44f, 21f, green)
    } else {
        val summary = listOf("الإدخالات: ${rows.sumOf { it.loading }}", "الإخراجات: ${rows.sumOf { it.returned }}", "المبيع: ${rows.sumOf { it.sale }}")
        val positions = listOf(logicalWidth - 68f, logicalWidth / 2f, 68f)
        summary.forEachIndexed { index, value -> text(value, positions[index], y + 44f, 16f, if (index == 2) green else navy) }
    }
    y += summaryHeight

    val headers = if (mode == "مفرق") listOf("علبة", "طرد", "المادة", "التاريخ") else listOf("مبيع", "مرتجع", "تحميل", "الوحدة", "المادة", "التاريخ")
    val values = if (mode == "مفرق") retailRows.map { listOf(it.box.toString(), it.carton.toString(), it.material, it.date) }
    else rows.map { listOf(it.sale.toString(), it.returned.toString(), it.loading.toString(), it.unit, it.material, it.date) }
    val weights = if (mode == "مفرق") listOf(.20f, .20f, .38f, .22f) else listOf(.12f, .14f, .14f, .12f, .30f, .18f)
    val xStarts = weights.runningFold(0f) { acc, weight -> acc + weight }.dropLast(1).map { it * logicalWidth }
    val widths = weights.map { it * logicalWidth }
    fun drawRowBackground(top: Float, color: Int) { canvas.drawRect(0f, top * scale, width.toFloat(), (top + rowHeight) * scale, paint.apply { this.color = color }) }
    canvas.drawRect(0f, y * scale, width.toFloat(), (y + tableHeaderHeight) * scale, paint.apply { color = teal })
    headers.forEachIndexed { index, header -> text(header, xStarts[index] + widths[index] / 2f, y + 30f, 14f, android.graphics.Color.WHITE) }
    y += tableHeaderHeight
    values.forEachIndexed { rowIndex, row ->
        drawRowBackground(y, if (rowIndex % 2 == 0) android.graphics.Color.WHITE else rowAlt)
        row.forEachIndexed { index, value -> text(value, xStarts[index] + widths[index] / 2f, y + 34f, if (index == 4 && mode == "جملة") 14f else 13f, cellColor(value, index)) }
        paint.color = android.graphics.Color.rgb(218, 232, 235)
        canvas.drawRect(0f, (y + rowHeight - 0.5f) * scale, width.toFloat(), (y + rowHeight) * scale, paint)
        y += rowHeight
    }
    val totals = if (mode == "مفرق") listOf("${retailRows.sumOf { it.box }}", "${retailRows.sumOf { it.carton }}", "الإجمالي", "") else listOf("${rows.sumOf { it.sale }}", "${rows.sumOf { it.returned }}", "${rows.sumOf { it.loading }}", "", "الإجمالي", "")
    canvas.drawRect(0f, y * scale, width.toFloat(), (y + rowHeight) * scale, paint.apply { color = totalColor })
    totals.forEachIndexed { index, value -> text(value, xStarts[index] + widths[index] / 2f, y + 34f, 14f, if (value == "0") muted else if (index < 2) green else navy) }
    val output = if (uri.scheme == "file") java.io.FileOutputStream(uri.path ?: error("مسار PNG غير صالح")) else context.contentResolver.openOutputStream(uri)
    output?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ?: error("تعذر إنشاء صورة PNG")
    bitmap.recycle()
}
