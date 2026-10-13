package com.awt.helper

/** Holds an unfinished line, so a secret split across process output chunks cannot leak. */
class Redactor(private val secret: String) {
    private val pending=StringBuilder()
    @Synchronized fun append(text: String): String {
        if(secret.isEmpty()) return text
        pending.append(text)
        val newline=pending.lastIndexOf("\n")
        if(newline < 0) return ""
        val result=pending.substring(0,newline+1).replace(secret,"[redacted]")
        pending.delete(0,newline+1)
        return result
    }
    @Synchronized fun finish(): String = pending.toString().replace(secret,"[redacted]").also { pending.setLength(0) }
}
