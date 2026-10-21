package com.nizar.geostamp.data

/** The four editable lines of a watermark. */
data class StampFields(
    val text: String = "",
    val date: String = "",
    val gps: String = "",
    val address: String = ""
) {
    /** Non-empty lines in drawing order. */
    fun lines(): List<String> =
        listOf(text, date, gps, address).map { it.trim() }.filter { it.isNotEmpty() }

    fun serialize(): List<String> =
        listOf(text, date, gps, address).map { it.replace("\n", " ").trim() }

    companion object {
        fun from(list: List<String>) = StampFields(
            list.getOrElse(0) { "" },
            list.getOrElse(1) { "" },
            list.getOrElse(2) { "" },
            list.getOrElse(3) { "" }
        )
    }
}
