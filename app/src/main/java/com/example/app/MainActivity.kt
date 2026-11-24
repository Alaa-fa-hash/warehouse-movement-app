package com.example.app

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private data class ReportRow(
    val date: String,
    val material: String,
    val unit: String,
    val loading: Int,
    val returned: Int,
    val sale: Int,
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
                scope.launch {
                    isReading = true
                    try {
                        val parsed = withContext(Dispatchers.IO) { excelReader.read(uri) }
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
                            onExport = { },
                            isReading = isReading,
                        )
                        FileCard(fileName = selectedFile, hasFile = pickedUri != null)
                        Filters(selectedMode = selectedMode, onModeChange = { selectedMode = it }, salespeople = salespeople, selectedSalesperson = selectedSalesperson, onSalespersonChange = { selectedSalesperson = it })
                        PriorityMaterials()
                        ReportsHeader(mode = selectedMode, filteredCount = filteredInvoices.size)
                    }
                    item { ReportCard(mode = selectedMode, rows = invoiceRows(filteredInvoices).ifEmpty { demoRows }) }
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
}

private fun invoiceRows(invoices: List<Invoice>): List<ReportRow> = invoices.take(60).map { invoice ->
    when (invoice.movementKind()) {
        MovementKind.TRANSFER_INPUT -> ReportRow(invoice.date.toString(), invoice.defaultAlias(), invoice.unit, invoice.quantity.toInt(), 0, 0)
        MovementKind.TRANSFER_OUTPUT -> ReportRow(invoice.date.toString(), invoice.defaultAlias(), invoice.unit, 0, invoice.quantity.toInt(), 0)
        MovementKind.SALE -> ReportRow(invoice.date.toString(), invoice.defaultAlias(), invoice.unit, 0, 0, invoice.quantity.toInt())
        MovementKind.UNKNOWN -> ReportRow(invoice.date.toString(), invoice.defaultAlias(), invoice.unit, 0, 0, 0)
    }
}

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
private fun FileCard(fileName: String, hasFile: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = PaleBlue),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(if (hasFile) "تم اختيار الملف" else "الملف الحالي", color = Color(0xFF687782), fontSize = 12.sp)
                Text("8 حركة", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("6 مادة", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("2 مندوب", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
private fun PriorityMaterials() {
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
                listOf("1", "2", "3", "4").forEach { index ->
                    Box(Modifier.weight(1f).border(1.dp, Border, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(index, color = Teal, fontWeight = FontWeight.Bold)
                            Text("اختر المادة", color = Navy, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun ReportsHeader(mode: String, filteredCount: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("$mode مبيعات — $filteredCount حركة", color = Navy, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        TextButton(onClick = { }) {
            androidx.compose.material3.Icon(Icons.Default.FileDownload, contentDescription = null)
            Text("تصدير Excel")
        }
    }
}

@Composable
private fun ReportCard(mode: String, rows: List<ReportRow>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            Column(Modifier.fillMaxWidth().background(PaleBlue).padding(22.dp), horizontalAlignment = Alignment.End) {
                Text("بهجت نقاوة", color = Navy, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("مبيعات $mode", color = Color(0xFF687782), fontSize = 15.sp)
                Text("2026-11-23 إلى 2026-11-23", color = Color(0xFF687782), fontSize = 14.sp)
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                SummaryCell("الإدخالات", "32")
                SummaryCell("الإخراجات", "8")
                SummaryCell("المبيع", "24", Green)
            }
            HorizontalDivider(color = Border)
            Row(Modifier.fillMaxWidth().background(Teal).padding(vertical = 14.dp)) {
                listOf("التاريخ", "المادة", "الوحدة", "تحميل", "مرتجع", "مبيع").forEach { heading ->
                    Text(heading, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    listOf(row.date, row.material, row.unit, row.loading.toString(), row.returned.toString(), row.sale.toString()).forEach { value ->
                        Text(value, color = Navy, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    }
                }
                HorizontalDivider(color = Color(0xFFDFEAED))
            }
            Text("تصدير التقرير PNG", modifier = Modifier.fillMaxWidth().padding(16.dp), color = Navy, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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
