package com.cadfield.core.versioning

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VersioningTest {

    private fun tmp(): File = Files.createTempDirectory("cadfield").toFile()

    private fun ann(id: String, text: String, author: String = "ana", t: Long = 1) =
        AnnotationRecord(id, "TEXT", """{"text":"$text"}""", author, t)

    private fun newRepo(): Pair<VersionRepository, Commit> {
        val dir = tmp()
        val plan = File(dir, "plano.dxf").apply { writeText("0\nSECTION\n0\nEOF\n") }
        var t = 1000L
        val repo = VersionRepository(File(dir, "repo")) { t++ }
        return repo to repo.init(plan, "ana")
    }

    @Test fun `object store es inmutable deduplica y detecta corrupcion`() {
        val dir = tmp()
        val s = FileObjectStore(dir)
        val h1 = s.put("hola".toByteArray())
        val h2 = s.put("hola".toByteArray())
        assertEquals(h1, h2)
        assertEquals("hola", s.get(h1).decodeToString())
        val f = File(File(dir, h1.take(2)), h1.drop(2))
        f.setWritable(true); f.writeText("alterado")
        assertFailsWith<CorruptObjectException> { s.get(h1) }
    }

    @Test fun `versionado lineal con etiquetas restaurar y diff`() {
        val (repo, v10) = newRepo()
        assertEquals("V1.0", v10.label)
        val base = repo.snapshot(v10)
        val v11 = repo.commit(base.copy(annotations = mapOf("a" to ann("a", "Filtración km 1+200"))), "ana", "Observación Campo")
        val v12 = repo.commit(repo.snapshot(v11).copy(annotations = mapOf("a" to ann("a", "Filtración corregida", t = 2))), "luis", "Corrección Trazado")
        assertEquals(listOf("V1.2", "V1.1", "V1.0"), repo.log().map { it.label })
        assertEquals("luis", repo.head()!!.author)

        val d = repo.diff(v10.id, v12.id)
        assertEquals(1, d.added.size)
        assertTrue(!d.baseChanged)

        val r = repo.restore(v10.id, "ana")
        assertEquals("V1.3", r.label)
        assertTrue(repo.snapshot(r).annotations.isEmpty())
        assertEquals(4, repo.log().size) // historial nunca se reescribe
        assertTrue(repo.fsck().isEmpty())
    }

    @Test fun `commit sin cambios no genera version`() {
        val (repo, v10) = newRepo()
        assertEquals(v10.id, repo.commit(repo.snapshot(v10), "ana", "nada").id)
    }

    @Test fun `ramas y fusion de observaciones con conflicto conservado`() {
        val (repo, v10) = newRepo()
        val s0 = repo.snapshot(v10).copy(annotations = mapOf("x" to ann("x", "original")))
        repo.commit(s0, "ana", "base")
        repo.createBranch("campo-norte")

        repo.commit(s0.copy(annotations = s0.annotations + ("n1" to ann("n1", "grieta", "pedro"))
            + ("x" to ann("x", "versión campo", "pedro", 5))), "pedro", "campo", "campo-norte")
        repo.commit(s0.copy(annotations = s0.annotations + ("o1" to ann("o1", "revisión"))
            + ("x" to ann("x", "versión oficina", "ana", 6))), "ana", "oficina")

        val (merged, conflicts) = repo.merge("campo-norte", VersionRepository.MAIN, "ana")
        assertEquals(2, merged.parents.size)
        assertEquals("V2.0", merged.label)
        assertEquals(1, conflicts.size)
        val anns = repo.snapshot(merged).annotations
        assertTrue("n1" in anns && "o1" in anns)
        // ambas versiones del conflicto se conservan → cero pérdida
        assertEquals(2, anns.values.count { it.id.startsWith("x") })
    }

    @Test fun `merge con borrado vs modificacion conserva la modificacion`() {
        val base = Snapshot("h", mapOf("a" to ann("a", "1")))
        val ours = Snapshot("h", emptyMap())
        val theirs = Snapshot("h", mapOf("a" to ann("a", "2", t = 9)))
        val r = ThreeWayMerge.merge(base, ours, theirs)
        assertEquals("""{"text":"2"}""", r.snapshot.annotations.getValue("a").payload)
        assertEquals(1, r.conflicts.size)
    }

    @Test fun `journal sobrevive a escritura truncada`() {
        val f = File(tmp(), "edit.wal")
        Journal(f).use { j -> j.append("op1"); j.append("op2"); j.append("op3") }
        // simular corte de energía a mitad del último registro
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 2) }
        val j = Journal(f)
        assertEquals(listOf("op1", "op2"), j.replay().map { it.payload })
        assertEquals(3, j.append("op3-bis"))
        j.checkpoint(2)
        assertEquals(listOf("op3-bis"), j.replay().map { it.payload })
        j.close()
        assertEquals(listOf("op3-bis"), Journal(f).replay().map { it.payload })
    }

    @Test fun `politica de retencion`() {
        val now = 100 * RetentionPolicy.DAY
        val ts = (0 until 60 * 24 * 3).map { now - it * RetentionPolicy.MIN } // cada minuto, 3 días
        val kept = RetentionPolicy().select(ts, now)
        assertTrue(kept.size < ts.size)
        assertTrue(ts.take(61).all { it in kept })
        assertNotEquals(0, kept.count { now - it > 2 * RetentionPolicy.DAY })
        assertEquals(ts.size, RetentionPolicy(unlimited = true).select(ts, now).size)
    }
}
