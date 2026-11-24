# 06 · Modelo de datos

## 1. Principio

Separación estricta entre **índice mutable** (Room/PostgreSQL: búsquedas, permisos, colas) y **contenido inmutable** (ObjectStore SHA-256: originales, snapshots, commits, multimedia). Perder o corromper el índice nunca destruye contenido: se reconstruye recorriendo `refs/` y objetos (`fsck`).

## 2. Local (Room, `cadfield.db`, WAL)

```mermaid
erDiagram
  projects ||--o{ documents : contiene
  documents ||--o{ versions : "índice de"
  documents ||--o{ document_tags : etiqueta
  tags ||--o{ document_tags : ""
  documents ||--o{ sync_queue : outbox
  documents ||--o{ audit_log : traza
  projects {
      string id PK
      string name
      string client
      long createdAt
      long updatedAt
      string remoteId
  }
  documents {
      string id PK
      string projectId FK
      string displayName
      string originalFileName
      string format
      long sizeBytes
      string originalHash
      string front
      string tunnel
      string discipline
      long documentDate
      string currentVersionLabel
      string headCommitId
      string syncState
  }
  versions {
      string commitId PK
      string documentId
      string label
      string branch
      string author
      string message
      long timestamp
      string parentIds
      bool signed
      bool pushed
  }
  tags {
      string name PK
      int color
  }
  document_tags {
      string documentId PK
      string tag PK
  }
  sync_queue {
      long id PK
      string documentId
      string kind
      string ref
      long createdAt
      int attempts
      string lastError
  }
  audit_log {
      long id PK
      long timestamp
      string user
      string action
      string documentId
      string detail
      double lat
      double lon
  }
```

## 3. Repositorio de objetos por documento

```
files/docs/<docId>/
├── repo/
│   ├── objects/ab/cdef…   ← blobs inmutables (solo lectura en disco)
│   │     · plano original (DXF/DWG/PDF)
│   │     · snapshot JSON  {baseFileHash, annotations{}, metadata{}}
│   │     · commit JSON    {parents[], snapshotHash, author, timestamp, message, label, branch, signature}
│   │     · fotos/audio    (cifrados AES-256-GCM)
│   └── refs/
│         principal          ← id del commit cabeza (escritura atómica)
│         campo-frente-norte
│         remoto.principal   ← última cabeza remota conocida
└── session/
      working.json           ← último autosave (atómico)
      edits.wal              ← journal de operaciones (CRC32 por registro)
```

### Anotación (`AnnotationRecord.payload`)

```json
{
  "points": [{"x": 512340.12, "y": 8667420.55}],
  "text": "Filtración en hastial derecho",
  "symbol": "FILTRACION",
  "lat": -12.0461, "lon": -77.0430, "gpsAccuracyM": 4.0,
  "mediaHash": "9f2c…",
  "status": "ABIERTA"
}
```

## 4. Servidor (PostgreSQL 17)

```mermaid
erDiagram
  Projects ||--o{ Documents : ""
  Projects ||--o{ Memberships : ""
  Documents ||--o{ Refs : ""
  Documents ||--o{ ObjectLinks : ""
  Objects ||--o{ ObjectLinks : ""
  Documents ||--o{ Audit : ""
  Projects {
      uuid Id PK
      text Name
      text OrganizationId
      timestamptz CreatedAt
  }
  Documents {
      uuid Id PK
      uuid ProjectId FK
      text DisplayName
      text Discipline
      text Front
      text Tunnel
      char64 OriginalHash
      timestamptz CreatedAt
  }
  Refs {
      uuid DocumentId PK
      text Branch PK
      char64 CommitId
      text UpdatedBy
      timestamptz UpdatedAt
      xid xmin "concurrencia"
  }
  Objects {
      char64 Hash PK
      bigint Size
      timestamptz CreatedAt
  }
  ObjectLinks {
      uuid DocumentId PK
      char64 Hash PK
  }
  Memberships {
      uuid ProjectId PK
      text UserId PK
      text Role
  }
  Audit {
      bigint Id PK
      timestamptz Timestamp
      text UserId
      text Action
      uuid DocumentId
      text Detail
      inet Ip
  }
```

- `Refs.xmin` → concurrencia optimista (compare-and-swap de ramas).
- `Audit`: el rol de BD de la API sólo tiene `INSERT, SELECT` (sin `UPDATE/DELETE`); particionado mensual.
- Blobs en Azure Blob/S3; la tabla `Objects` es índice + tamaño.
- Redis: caché de `refs:{docId}` (TTL 5 min, invalidación en escritura), *rate limiting*, sesiones de subida.

## 5. Migraciones

- Room: `exportSchema = true` (esquemas en `app/schemas`), **prohibido** `fallbackToDestructiveMigration`; pruebas de migración con `MigrationTestHelper`.
- EF Core: migraciones versionadas en CI; despliegue *expand → migrate → contract* sin downtime.
