# 09 · Estrategia de sincronización

## 1. Principios
1. **Offline-first**: el dispositivo es la fuente de verdad para su trabajo; la red es opcional.
2. **Content-addressed**: todo es un objeto SHA-256 inmutable → la sincronización consiste en "enviar los hashes que faltan" (diferencial y deduplicada por diseño; un DXF de 500 MB ya subido nunca se re-sube aunque otro usuario lo importe).
3. **Ramas con compare-and-swap**: única operación mutable en el servidor; concurrencia optimista.
4. **Conflictos sin pérdida**: fusión de 3 vías a nivel de observación; ante conflicto real se conservan ambas versiones.
5. **Outbox persistente**: nada se considera sincronizado hasta confirmación del servidor.

## 2. Protocolo (implementado en `SyncWorker` / `SyncEndpoints`)

| Paso | Llamada | Descripción |
|---|---|---|
| 0 | `PUT /api/v1/projects/{id}` · `PUT /api/v1/documents/{id}` | Registro idempotente de metadatos (original inmutable) |
| 1 | `POST /documents/{id}/sync/negotiate {hashes}` | Servidor responde `missing[]` y vincula los existentes |
| 2 | `PUT /objects/{hash}` | Subida en streaming; el servidor recalcula SHA-256 y rechaza si no coincide; write-once |
| 3 | `GET /documents/{id}/refs` | Cabezas remotas (caché Redis) |
| 4 | `POST /documents/{id}/refs {branch, expectedOld, new}` | CAS; `409 {commitId}` si la rama avanzó |
| 5 (409) | `GET /objects/{hash}` | Descarga de la cadena remota faltante; verificación de hash |
| 6 | Fusión local `remoto.<rama>` → `<rama>` | Commit de fusión `V(n+1).0`; volver a 1 |

## 3. Resolución de conflictos

| Caso | Regla |
|---|---|
| Observación nueva en A y otra en B | Ambas se incluyen (caso más común en campo) |
| Misma observación modificada sólo en un lado | Se toma ese lado |
| Modificada distinto en ambos | Se conserva la local y la remota como copia `id~autor~ts`; documento marcado `CONFLICT` para revisión |
| Borrada en un lado, modificada en otro | Gana la modificación; conflicto reportado |
| Plano base distinto (nueva revisión de oficina) | Gana la revisión nueva; las observaciones se re-proyectan (F2: re-anclaje por handle) |

## 4. Integración con nubes de terceros

| Servicio | API | Uso |
|---|---|---|
| OneDrive / SharePoint | Microsoft Graph (`/drives/{id}/items`, *delta query*) | Importar planos de bibliotecas; publicar PDFs exportados; delta para cambios |
| Google Drive | Drive API v3 (`changes.list`) | Importar/exportar |
| Dropbox | API v2 (`list_folder/continue`) | Importar/exportar |

Estas nubes son **fuentes/destinos de documentos**, no el almacén de versiones (no garantizan inmutabilidad ni CAS). El versionado autoritativo vive en el backend propio; los conectores corren en el servidor (tokens OAuth del usuario, nunca en el dispositivo en Enterprise) y usan *delta queries* para sincronización diferencial.

## 5. Políticas
- Red: por defecto sólo Wi-Fi para objetos > 20 MB; metadatos y commits por datos móviles (configurable).
- Batería: no sincronizar con batería baja.
- Reintentos: backoff exponencial (1 min → 5 h); errores visibles por documento.
- Prioridad: commits y observaciones antes que originales pesados; fotos comprimidas a 2560 px con original opcional.
- Subidas grandes: F2 añade subida reanudable por bloques (`Content-Range`) para originales > 100 MB.
