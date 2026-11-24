package com.cadfield.core.versioning

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Almacén de objetos direccionado por contenido (SHA-256), estilo Git.
 *
 * Garantías anti-pérdida:
 *  - Write-once: un objeto existente NUNCA se reescribe (inmutabilidad).
 *  - Escritura atómica: temp + fsync + rename (un corte de energía no deja objetos a medias).
 *  - Verificación de integridad al leer (hash recalculado).
 *  - Deduplicación: el mismo plano importado dos veces ocupa una sola vez.
 */
interface ObjectStore {
    fun put(bytes: ByteArray): String
    fun put(stream: InputStream): String
    fun get(hash: String): ByteArray
    fun open(hash: String): InputStream
    fun exists(hash: String): Boolean
    fun verify(hash: String): Boolean
    fun size(hash: String): Long
}

class FileObjectStore(private val root: File) : ObjectStore {
    init { root.mkdirs() }

    private fun pathOf(hash: String): File {
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Hash inválido: $hash" }
        return File(File(root, hash.substring(0, 2)), hash.substring(2))
    }

    override fun put(bytes: ByteArray): String = put(bytes.inputStream())

    override fun put(stream: InputStream): String {
        val tmp = File.createTempFile("obj", ".tmp", root)
        val md = MessageDigest.getInstance("SHA-256")
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n); out.write(buf, 0, n)
                }
                out.flush(); out.fd.sync()
            }
            val hash = md.digest().toHex()
            val dest = pathOf(hash)
            if (dest.exists()) { tmp.delete(); return hash } // dedupe, nunca sobrescribir
            dest.parentFile.mkdirs()
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath())
            }
            dest.setReadOnly()
            return hash
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    override fun get(hash: String): ByteArray {
        val bytes = pathOf(hash).readBytes()
        if (sha256(bytes) != hash) throw CorruptObjectException(hash)
        return bytes
    }

    override fun open(hash: String): InputStream = pathOf(hash).inputStream()
    override fun exists(hash: String) = pathOf(hash).exists()
    override fun size(hash: String) = pathOf(hash).length()

    /** Ruta del objeto en disco (sólo lectura) para motores nativos que requieren un archivo. */
    fun fileOf(hash: String): File = pathOf(hash)
    override fun verify(hash: String) = exists(hash) && runCatching { get(hash) }.isSuccess

    /** Lista todos los objetos (para auditoría/backup/fsck). */
    fun all(): Sequence<String> = root.walkTopDown().filter { it.isFile && it.parentFile != root }
        .map { it.parentFile.name + it.name }
}

class CorruptObjectException(hash: String) : Exception("Objeto corrupto o alterado: $hash")

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
