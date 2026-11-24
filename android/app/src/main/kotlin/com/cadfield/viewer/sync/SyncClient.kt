package com.cadfield.viewer.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable data class NegotiateRequest(val hashes: List<String>)
@Serializable data class NegotiateResponse(val missing: List<String>)
@Serializable data class RefUpdate(val branch: String, val expectedOld: String?, val new: String)
@Serializable data class RefState(val branch: String, val commitId: String)
@Serializable data class ProjectUpsert(val name: String)
@Serializable data class DocumentUpsert(
    val projectId: String, val displayName: String, val originalHash: String,
    val discipline: String?, val front: String?, val tunnel: String?,
)

class ConflictException(val remoteHead: String) : IOException("La rama remota avanzó: $remoteHead")
class AuthException : IOException("Sesión expirada: se requiere iniciar sesión")

/**
 * Cliente REST del backend .NET 9 (protocolo de sincronización diferencial por contenido).
 * Ver docs/09-estrategia-sincronizacion.md.
 */
class SyncClient(private val baseUrl: String, private val token: () -> String?) {
    private val json = Json { ignoreUnknownKeys = true }

    fun upsertProject(id: String, body: ProjectUpsert) { request("PUT", "api/v1/projects/$id", json.encodeToString(body)) }

    fun upsertDocument(id: String, body: DocumentUpsert) { request("PUT", "api/v1/documents/$id", json.encodeToString(body)) }

    fun negotiate(docId: String, hashes: List<String>): List<String> =
        json.decodeFromString<NegotiateResponse>(
            request("POST", "api/v1/documents/$docId/sync/negotiate", json.encodeToString(NegotiateRequest(hashes)))
        ).missing

    fun uploadObject(hash: String, body: InputStream, length: Long) {
        val c = open("PUT", "api/v1/objects/$hash")
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(length)
        c.doOutput = true
        c.outputStream.use { body.copyTo(it, 1 shl 16) }
        check(c)
    }

    fun downloadObject(hash: String): ByteArray {
        val c = open("GET", "api/v1/objects/$hash")
        check(c)
        return c.inputStream.use { it.readBytes() }
    }

    fun refs(docId: String): List<RefState> =
        json.decodeFromString(request("GET", "api/v1/documents/$docId/refs", null))

    /** Compare-and-swap de la rama remota. 409 → [ConflictException] con el head remoto. */
    fun updateRef(docId: String, update: RefUpdate) {
        try {
            request("POST", "api/v1/documents/$docId/refs", json.encodeToString(update))
        } catch (e: HttpStatusException) {
            if (e.code == 409) throw ConflictException(json.decodeFromString<RefState>(e.body).commitId)
            throw e
        }
    }

    class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code: $body")

    private fun request(method: String, path: String, body: String?): String {
        val c = open(method, path)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        check(c)
        return c.inputStream.use { it.readBytes().decodeToString() }
    }

    private fun open(method: String, path: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            token()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code == 401) throw AuthException()
        if (code !in 200..299) throw HttpStatusException(code, c.errorStream?.use { it.readBytes().decodeToString() }.orEmpty())
    }
}
