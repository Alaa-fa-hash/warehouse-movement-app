package com.example.app.excel

object SchemaValidator {
    val requiredColumns = listOf(
        "التاريخ",
        "رقم الفاتورة",
        "اسم الزبون",
        "نوع الفاتورة",
        "نوع الزبون",
        "رمز المادة",
        "اسم المادة",
        "سعر المادة",
        "الكمية",
        "وحدة المادة",
        "المنطقة",
        "البائع",
    )

    fun validate(headers: List<String>): ValidationResult {
        val normalized = headers.map(::normalizeHeader).toSet()
        val missing = requiredColumns.filter { normalizeHeader(it) !in normalized }
        return if (missing.isEmpty()) ValidationResult.Valid else ValidationResult.Missing(missing)
    }

    fun normalizeHeader(value: String): String {
        return value
            .replace('\uFEFF'.toString(), "")
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }
}

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Missing(val columns: List<String>) : ValidationResult
}

class MissingColumnsException(val columns: List<String>) : IllegalArgumentException(
    "الأعمدة الناقصة: ${columns.joinToString("، ")}",
)
