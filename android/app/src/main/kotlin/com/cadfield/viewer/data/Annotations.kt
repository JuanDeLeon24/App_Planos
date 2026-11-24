package com.cadfield.viewer.data

import com.cadfield.core.cad.geom.Vec2
import com.cadfield.core.versioning.AnnotationRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

enum class AnnotationKind { TEXT, ARROW, CLOUD, SYMBOL, MEASURE_DISTANCE, MEASURE_POLYLINE, MEASURE_AREA, PHOTO, AUDIO, VIDEO }

@Serializable data class Pt(val x: Double, val y: Double) { fun v() = Vec2(x, y) }

/** Contenido de una anotación de campo (serializado en AnnotationRecord.payload). */
@Serializable
data class AnnotationPayload(
    val points: List<Pt>,
    val text: String = "",
    val value: Double? = null,      // resultado de medición
    val unit: String? = null,
    val symbol: String? = null,     // p.ej. "FILTRACION", "SOSTENIMIENTO", "PUNTO_INSPECCION"
    val lat: Double? = null,        // GPS asociado
    val lon: Double? = null,
    val gpsAccuracyM: Float? = null,
    val mediaHash: String? = null,  // blob en el ObjectStore (foto/audio/video, cifrado AES-256)
    val status: String = "ABIERTA", // ABIERTA | EN_REVISION | CERRADA
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

fun AnnotationRecord.payloadObj(): AnnotationPayload = json.decodeFromString(payload)
val AnnotationRecord.kind get() = runCatching { AnnotationKind.valueOf(type) }.getOrDefault(AnnotationKind.TEXT)

fun newAnnotation(kind: AnnotationKind, payload: AnnotationPayload, author: String, now: Long = System.currentTimeMillis()) =
    AnnotationRecord(UUID.randomUUID().toString(), kind.name, json.encodeToString(payload), author, now)
