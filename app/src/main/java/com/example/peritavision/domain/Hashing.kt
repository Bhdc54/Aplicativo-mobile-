package com.example.peritavision.domain

import java.io.File
import java.security.MessageDigest

/**
 * Geracao de hash da evidencia. O SHA-256 e calculado NO CELULAR, logo apos
 * receber o arquivo — nunca nos oculos (RF-08 do FIELD spec). Por isso vive na
 */
object Hashing {

    /** SHA-256 de um arquivo, lido em blocos para nao carregar tudo na memoria. */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().toHex()
    }

    /** SHA-256 de uma String (usado no encadeamento do log de custodia). */
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
