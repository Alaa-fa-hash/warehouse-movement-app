package com.example.app.excel

data class Invoice(
    val date: Long,
    val invoiceNumber: String,
    val customerName: String,
    val invoiceType: String,
    val customerType: String,
    val itemCode: String,
    val itemName: String,
    val price: Long,
    val quantity: Double,
    val unit: String,
    val region: String,
    val salesperson: String,
)

fun Invoice.defaultAlias(): String {
    val ignored = setOf("الصوص", "صوص", "شوكولا", "بسكويت", "بسكوت", "ديانا", "رقم", "مادة")
    return itemName
        .replace(Regex("[0-9٠-٩]+"), " ")
        .replace("*", " ")
        .replace(Regex("[()\\[\\],.:/\\-]+"), " ")
        .split(Regex("\\s+"))
        .firstOrNull { it.isNotBlank() && it !in ignored }
        ?.trim()
        ?.ifBlank { itemName }
        ?: itemName
}

enum class MovementKind {
    TRANSFER_INPUT,
    TRANSFER_OUTPUT,
    SALE,
    UNKNOWN,
}

enum class SalesMode {
    WHOLESALE,
    RETAIL,
}

fun Invoice.movementKind(): MovementKind {
    val value = invoiceType.trim().lowercase()
    return when {
        Regex("مبيع|مبيعات|فاتورة بيع|sale|sales").containsMatchIn(value) -> MovementKind.SALE
        Regex("إدخال|ادخال|إد\\.?م|اد\\.?م|تحميل|مناقلة إدخال|input|transfer in").containsMatchIn(value) -> MovementKind.TRANSFER_INPUT
        Regex("إخراج|اخراج|إخ\\.?م|اخ\\.?م|مرتجع|مناقلة إخراج|output|transfer out|return").containsMatchIn(value) -> MovementKind.TRANSFER_OUTPUT
        else -> MovementKind.UNKNOWN
    }
}

fun List<Invoice>.filterForMode(mode: SalesMode, salesperson: String? = null): List<Invoice> {
    return asSequence()
        .filter { salesperson.isNullOrBlank() || it.salesperson == salesperson }
        .filter {
            when (mode) {
                SalesMode.WHOLESALE -> it.movementKind() == MovementKind.TRANSFER_INPUT || it.movementKind() == MovementKind.TRANSFER_OUTPUT
                SalesMode.RETAIL -> it.movementKind() == MovementKind.SALE
            }
        }
        .toList()
}
