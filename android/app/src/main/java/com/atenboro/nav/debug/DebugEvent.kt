package com.atenboro.nav.debug

data class DebugEvent(
    val t: Long = System.currentTimeMillis(),
    val src: String = "phone",
    val lvl: String = "i",
    val msg: String,
    val origin: String = "local" // local | board | synced
) {
    fun toJsonObject(): String {
        val safe = msg.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"t":$t,"src":"$src","lvl":"$lvl","msg":"$safe","origin":"$origin"}"""
    }

    fun line(): String = "[$src/$lvl] $msg"
}
