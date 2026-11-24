package com.cadfield.core.versioning

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditSessionTest {

    private fun rec(id: String, t: Long) = AnnotationRecord(id, "TEXT", "{}", "ana", t)

    @Test fun `recupera operaciones tras cierre inesperado`() {
        val dir = Files.createTempDirectory("sess").toFile()
        val base = Snapshot("h", emptyMap())

        // Sesión 1: dos operaciones, autosave, una tercera operación y "crash" (sin close)
        val s1 = EditSession(dir, base)
        s1.execute(EditOp.Upsert(rec("a", 1)))
        s1.execute(EditOp.Upsert(rec("b", 2)))
        assertTrue(s1.autosave())
        s1.execute(EditOp.Delete("a", "luis", 3))
        // no se llama close(): simulamos que el proceso muere aquí

        val s2 = EditSession(dir, base)
        assertEquals(1, s2.recoveredOps)
        assertTrue(s2.dirty)
        assertEquals(setOf("a", "b"), s2.snapshot.annotations.keys)
        assertTrue(s2.snapshot.annotations.getValue("a").deleted) // borrado lógico, no físico
        s2.close()

        val s3 = EditSession(dir, base)
        assertEquals(0, s3.recoveredOps)
        assertFalse(s3.dirty)
        assertEquals(2, s3.snapshot.annotations.size)
    }
}
