package com.cadfield.core.versioning

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32

/**
 * Write-Ahead Log (WAL) de operaciones de edición.
 *
 * Cada operación del usuario (añadir anotación, mover, borrar...) se agrega aquí
 * ANTES de aplicarse en memoria, con fsync. Tras un cierre inesperado, reinicio
 * o caída de batería, se reproduce el journal sobre el último snapshot y no se
 * pierde ninguna acción. Registros con CRC32: un registro final truncado por un
 * corte se detecta y se descarta sin corromper los anteriores.
 *
 * Formato de registro: [len:int][crc:long][seq:long][ts:long][payload:bytes]
 */
class Journal(private val file: File, private val syncEveryWrite: Boolean = true) : AutoCloseable {

    data class Entry(val seq: Long, val timestamp: Long, val payload: String)

    private var out: FileOutputStream? = null
    private var data: DataOutputStream? = null
    var lastSeq: Long = 0
        private set

    init {
        file.parentFile?.mkdirs()
        val (entries, validBytes) = scan()
        lastSeq = entries.lastOrNull()?.seq ?: 0
        if (file.exists() && validBytes < file.length()) {
            // cola corrupta (escritura interrumpida): truncar al último registro válido
            java.io.RandomAccessFile(file, "rw").use { it.setLength(validBytes) }
        }
    }

    @Synchronized
    fun append(payload: String, timestamp: Long = System.currentTimeMillis()): Long {
        val o = out ?: FileOutputStream(file, true).also { out = it; data = DataOutputStream(it.buffered()) }
        val d = data!!
        val seq = ++lastSeq
        val bytes = payload.encodeToByteArray()
        val crc = CRC32().apply { update(longBytes(seq)); update(longBytes(timestamp)); update(bytes) }.value
        d.writeInt(bytes.size); d.writeLong(crc); d.writeLong(seq); d.writeLong(timestamp); d.write(bytes)
        d.flush()
        if (syncEveryWrite) o.fd.sync()
        return seq
    }

    /** Entradas válidas posteriores a [afterSeq] (para reproducir tras un fallo). */
    fun replay(afterSeq: Long = 0): List<Entry> = scan().first.filter { it.seq > afterSeq }

    /** Tras persistir un snapshot que incluye hasta [seq], el journal se compacta. */
    @Synchronized
    fun checkpoint(seq: Long) {
        val keep = replay(seq)
        close()
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(FileOutputStream(tmp).buffered()).use { d ->
            for (e in keep) {
                val b = e.payload.encodeToByteArray()
                val crc = CRC32().apply { update(longBytes(e.seq)); update(longBytes(e.timestamp)); update(b) }.value
                d.writeInt(b.size); d.writeLong(crc); d.writeLong(e.seq); d.writeLong(e.timestamp); d.write(b)
            }
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun scan(): Pair<List<Entry>, Long> {
        if (!file.exists()) return emptyList<Entry>() to 0L
        val out = ArrayList<Entry>()
        var valid = 0L
        DataInputStream(file.inputStream().buffered()).use { inp ->
            while (true) {
                try {
                    val len = inp.readInt()
                    if (len < 0 || len > MAX_RECORD) break
                    val crc = inp.readLong(); val seq = inp.readLong(); val ts = inp.readLong()
                    val b = ByteArray(len); inp.readFully(b)
                    val calc = CRC32().apply { update(longBytes(seq)); update(longBytes(ts)); update(b) }.value
                    if (calc != crc) break
                    out += Entry(seq, ts, b.decodeToString())
                    valid += 4 + 8 + 8 + 8 + len
                } catch (_: EOFException) { break }
            }
        }
        return out to valid
    }

    override fun close() { data?.close(); out = null; data = null }

    private fun longBytes(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

    companion object { const val MAX_RECORD = 16 * 1024 * 1024 }
}

/**
 * Política de retención de snapshots automáticos (historial "ilimitado configurable").
 * Estilo Time Machine: todo en la última hora, horarios 48 h, diarios 30 días, semanales después.
 */
data class RetentionPolicy(
    val keepAllMinutes: Long = 60,
    val hourlyHours: Long = 48,
    val dailyDays: Long = 30,
    val unlimited: Boolean = false,
) {
    /** Devuelve los timestamps a conservar. */
    fun select(timestamps: List<Long>, now: Long): Set<Long> {
        if (unlimited) return timestamps.toSet()
        val keep = HashSet<Long>()
        val buckets = HashMap<String, Long>()
        for (t in timestamps.sortedDescending()) {
            val age = now - t
            val key = when {
                age <= keepAllMinutes * MIN -> { keep += t; continue }
                age <= hourlyHours * HOUR -> "h${t / HOUR}"
                age <= dailyDays * DAY -> "d${t / DAY}"
                else -> "w${t / (7 * DAY)}"
            }
            if (key !in buckets) { buckets[key] = t; keep += t }
        }
        return keep
    }

    companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
    }
}
