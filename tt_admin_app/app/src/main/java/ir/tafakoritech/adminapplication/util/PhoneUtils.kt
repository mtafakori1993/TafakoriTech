package ir.tafakoritech.adminapplication.util

fun normalizePhone(phone: String): String =
    phone.trim().filter { it.isDigit() }.let {
        if (it.startsWith("98") && it.length > 10) it.drop(2) else it.removePrefix("0")
    }
