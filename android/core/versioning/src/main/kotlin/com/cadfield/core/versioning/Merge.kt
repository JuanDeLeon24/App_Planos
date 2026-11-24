package com.cadfield.core.versioning

/**
 * Fusión de tres vías a nivel de anotación (granularidad fina → muy pocos conflictos
 * reales en campo, donde cada usuario suele agregar observaciones nuevas).
 *
 * Reglas:
 *  - Cambio sólo en un lado → se toma ese lado.
 *  - Ambos lados iguales → sin conflicto.
 *  - Ambos modificados distinto → CONFLICTO: se conservan AMBAS versiones
 *    (la de "theirs" se duplica con id derivado) para no perder información,
 *    y se reporta para revisión humana.
 *  - Borrado vs. modificación → gana la modificación (cero pérdida) + conflicto.
 */
object ThreeWayMerge {

    fun diff(from: Snapshot, to: Snapshot): SnapshotDiff {
        val added = to.annotations.filterKeys { it !in from.annotations }.values.toList()
        val removed = from.annotations.filterKeys { it !in to.annotations }.values.toList()
        val modified = to.annotations.mapNotNull { (id, r) -> from.annotations[id]?.takeIf { it != r }?.let { it to r } }
        return SnapshotDiff(added, removed, modified, from.baseFileHash != to.baseFileHash)
    }

    fun merge(base: Snapshot, ours: Snapshot, theirs: Snapshot): MergeResult {
        val result = LinkedHashMap<String, AnnotationRecord>()
        val conflicts = ArrayList<MergeConflict>()
        val ids = LinkedHashSet<String>().apply {
            addAll(base.annotations.keys); addAll(ours.annotations.keys); addAll(theirs.annotations.keys)
        }
        for (id in ids) {
            val b = base.annotations[id]
            val o = ours.annotations[id]
            val t = theirs.annotations[id]
            when {
                o == t -> o?.let { result[id] = it }
                o == b -> t?.let { result[id] = it }          // sólo cambió theirs
                t == b -> o?.let { result[id] = it }          // sólo cambió ours
                o == null -> { result[id] = t!!; conflicts += MergeConflict(id, null, t) }   // borrado vs. modificado
                t == null -> { result[id] = o; conflicts += MergeConflict(id, o, null) }
                else -> {
                    result[id] = o
                    val copyId = "$id~${t.author}~${t.modifiedAt}"
                    result[copyId] = t.copy(id = copyId)
                    conflicts += MergeConflict(id, o, t)
                }
            }
        }
        val baseHash = when {
            ours.baseFileHash == theirs.baseFileHash -> ours.baseFileHash
            ours.baseFileHash == base.baseFileHash -> theirs.baseFileHash
            else -> ours.baseFileHash
        }
        return MergeResult(Snapshot(baseHash, result, base.metadata + theirs.metadata + ours.metadata), conflicts)
    }
}
