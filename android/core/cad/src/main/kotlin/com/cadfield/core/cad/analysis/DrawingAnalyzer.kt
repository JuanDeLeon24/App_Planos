package com.cadfield.core.cad.analysis

import com.cadfield.core.cad.geom.Vec2
import com.cadfield.core.cad.measure.Measurements
import com.cadfield.core.cad.model.CadDocument
import com.cadfield.core.cad.model.CadEntity
import kotlin.math.round

enum class Severity { INFO, WARNING, ERROR }

data class Finding(val severity: Severity, val code: String, val message: String, val handles: List<String>)

/**
 * Análisis geométrico determinista. Es la base "verificable" del asistente IA:
 * el LLM traduce la pregunta del usuario a llamadas de estas funciones
 * (tool-calling), y nunca inventa cifras.
 */
class DrawingAnalyzer(private val doc: CadDocument, private val tol: Double = 1e-6) {

    fun findIssues(): List<Finding> = buildList {
        addAll(zeroLength())
        addAll(duplicates())
        addAll(nearlyClosed())
        addAll(missingBlocks())
    }

    private fun zeroLength() = doc.entities.filterIsInstance<CadEntity.Line>()
        .filter { (it.end - it.start).length() <= tol }
        .takeIf { it.isNotEmpty() }
        ?.let { listOf(Finding(Severity.WARNING, "ZERO_LENGTH", "${it.size} líneas de longitud cero", it.map { l -> l.handle })) }
        .orEmpty()

    /** Líneas duplicadas/superpuestas (mismos extremos en cualquier sentido). */
    private fun duplicates(): List<Finding> {
        fun key(p: Vec2) = "${round(p.x / tol)}:${round(p.y / tol)}"
        val groups = doc.entities.filterIsInstance<CadEntity.Line>().groupBy {
            listOf(key(it.start.xy()), key(it.end.xy())).sorted().joinToString("|") + "@" + it.layer
        }.values.filter { it.size > 1 }
        if (groups.isEmpty()) return emptyList()
        return listOf(Finding(Severity.WARNING, "DUPLICATE", "${groups.sumOf { it.size - 1 }} entidades duplicadas",
            groups.flatMap { g -> g.drop(1).map { it.handle } }))
    }

    /** Polilíneas abiertas cuyo inicio y fin casi coinciden (impiden calcular áreas). */
    private fun nearlyClosed(): List<Finding> {
        val bad = doc.entities.filterIsInstance<CadEntity.Polyline>().filter {
            !it.closed && it.vertices.size > 2 &&
                it.vertices.first().xy().distanceTo(it.vertices.last().xy()).let { d -> d > tol && d < doc.extents.width * 1e-4 }
        }
        return if (bad.isEmpty()) emptyList() else
            listOf(Finding(Severity.INFO, "NEARLY_CLOSED", "${bad.size} polilíneas casi cerradas", bad.map { it.handle }))
    }

    private fun missingBlocks(): List<Finding> {
        val bad = doc.entities.filterIsInstance<CadEntity.Insert>().filter { it.blockName !in doc.blocks }
        return if (bad.isEmpty()) emptyList() else
            listOf(Finding(Severity.ERROR, "MISSING_BLOCK", "${bad.size} referencias a bloques inexistentes", bad.map { it.handle }))
    }

    // ---------- Consultas para el asistente técnico ----------

    /** "¿Cuál es la longitud del túnel?" → longitud total de ejes en la capa indicada. */
    fun totalLength(layerPattern: Regex): Double = doc.entities.filter { layerPattern.matches(it.layer) }.sumOf { e ->
        when (e) {
            is CadEntity.Line -> (e.end - e.start).xy().length()
            is CadEntity.Polyline -> Measurements.polylineLength(e.vertices.map { it.xy() }, e.bulges, e.closed)
            is CadEntity.Arc -> e.radius * Math.toRadians(((e.endDeg - e.startDeg) % 360 + 360) % 360)
            is CadEntity.Circle -> 2 * Math.PI * e.radius
            else -> 0.0
        }
    }

    /** "¿Cuál es el área de esta excavación?" → suma de áreas de contornos cerrados. */
    fun closedArea(layerPattern: Regex): Double = doc.entities.filter { layerPattern.matches(it.layer) }.sumOf { e ->
        when (e) {
            is CadEntity.Polyline -> if (e.closed) Measurements.polygonArea(e.toPolylines(1e-3).first()) else 0.0
            is CadEntity.Circle -> Math.PI * e.radius * e.radius
            else -> 0.0
        }
    }

    /** "¿Cuántos puntos de inspección existen?" → conteo de bloques/puntos. */
    fun countBlocks(namePattern: Regex) = doc.entities.count { it is CadEntity.Insert && namePattern.matches(it.blockName) }

    fun countByLayer(): Map<String, Int> = doc.entities.groupingBy { it.layer }.eachCount()
}
