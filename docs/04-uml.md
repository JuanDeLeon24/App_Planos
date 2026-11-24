# 04 · Diagramas UML

## 1. Componentes

```mermaid
flowchart TB
  subgraph app[":app"]
    ExplorerScreen --> ExplorerViewModel
    ViewerScreen --> ViewerViewModel
    HistoryScreen --> HistoryViewModel
    ViewerScreen --> CadCanvas
    ExplorerViewModel & ViewerViewModel & HistoryViewModel --> DocumentRepository
    DocumentRepository --> AppDatabase
    SyncWorker --> SyncClient
    SyncWorker --> DocumentRepository
    ViewerViewModel --> Exporters
    DocumentRepository --> CryptoManager
  end
  subgraph cad[":core:cad"]
    DxfParser --> CadDocument
    RenderModelDeps[QuadTree] 
    Measurements
    Utm
    DrawingAnalyzer --> CadDocument
  end
  subgraph ver[":core:versioning"]
    VersionRepository --> FileObjectStore
    VersionRepository --> ThreeWayMerge
    EditSession --> Journal
  end
  DocumentRepository --> DxfParser
  DocumentRepository --> VersionRepository
  DocumentRepository --> EditSession
  CadCanvas --> RenderModelDeps
```

## 2. Clases — núcleo CAD

```mermaid
classDiagram
  class CadDocument {
    +Map~String,Layer~ layers
    +Map~String,Block~ blocks
    +List~CadEntity~ entities
    +DrawingUnits units
    +BBox extents
  }
  class CadEntity {
    <<sealed>>
    +String handle
    +String layer
    +CadColor color
    +bounds() BBox
    +toPolylines(tol) List
  }
  CadEntity <|-- Line
  CadEntity <|-- Polyline
  CadEntity <|-- Circle
  CadEntity <|-- Arc
  CadEntity <|-- Text
  CadEntity <|-- Point
  CadEntity <|-- Face3D
  CadEntity <|-- Insert
  CadEntity <|-- Unknown
  CadDocument "1" *-- "*" CadEntity
  CadDocument "1" *-- "*" Layer
  CadDocument "1" *-- "*" Block
  class DxfParser {
      +parse(Reader) CadDocument
  }
  class CadImporter {
      <<interface>>
      +supported
      +import(stream,size,progress)
  }
  class QuadTree~T~ {
      +insert(box,v)
      +query(area)
  }
  class Measurements {
      <<object>>
      +polygonArea()
      +slope()
      +meshVolume()
      +averageEndAreaVolume()
  }
  DxfParser ..> CadDocument
  CadImporter ..> CadDocument
```

## 3. Clases — versionado

```mermaid
classDiagram
  class ObjectStore {
      <<interface>>
      +put(bytes) hash
      +get(hash)
      +verify(hash)
  }
  class FileObjectStore
  ObjectStore <|.. FileObjectStore
  class VersionRepository {
    +init(file, author) Commit
    +commit(snapshot, author, msg, branch) Commit
    +createBranch(name, from)
    +merge(source, target, author)
    +restore(commitId, author) Commit
    +diff(a, b) SnapshotDiff
    +fsck() List
  }
  class Commit {
      id
      parents
      snapshotHash
      author
      timestamp
      message
      label
      branch
      signature
  }
  class Snapshot {
      baseFileHash
      annotations
      metadata
  }
  class AnnotationRecord {
      id
      type
      payload
      author
      modifiedAt
      deleted
  }
  class EditSession {
      +execute(op)
      +autosave()
      +recoveredOps
  }
  class Journal {
      +append()
      +replay()
      +checkpoint()
  }
  VersionRepository --> ObjectStore
  VersionRepository ..> Commit
  Commit --> Snapshot
  Snapshot "1" *-- "*" AnnotationRecord
  EditSession --> Journal
  EditSession --> Snapshot
```

## 4. Secuencia — abrir plano y recuperar tras fallo

```mermaid
sequenceDiagram
  participant V as ViewerViewModel
  participant R as DocumentRepository
  participant S as EditSession
  participant J as Journal
  participant P as DxfParser
  V->>R: openSession(docId)
  R->>S: new(dir, headSnapshot)
  S->>S: lee working.json (último autosave)
  S->>J: replay(afterSeq)
  J-->>S: ops pendientes (CRC válidos)
  S-->>V: snapshot + recoveredOps
  V->>R: loadModel(doc, progress)
  R->>P: parse(stream del original inmutable)
  P-->>V: CadDocument (progreso 0→100 %)
  V->>V: RenderModel (teselado + quadtree) en Default
  loop cada 30 s
    V->>S: autosave()
  end
```

## 5. Secuencia — anotar, versionar y sincronizar

```mermaid
sequenceDiagram
  actor U as Ingeniero
  participant V as ViewerViewModel
  participant S as EditSession
  participant R as VersionRepository
  participant Q as Outbox (Room)
  participant W as SyncWorker
  participant A as API .NET
  U->>V: Nota + foto en km 1+240
  V->>S: execute(Upsert) → journal fsync
  U->>V: Guardar versión "Observación Campo"
  V->>R: commit(snapshot, firma ECDSA)
  R-->>V: V1.1
  V->>Q: enqueue(COMMIT V1.1)
  W->>A: negotiate(hashes)
  A-->>W: missing[]
  W->>A: PUT objects/{hash} (sólo faltantes)
  W->>A: POST refs {expectedOld, new}
  alt rama remota avanzó
    A-->>W: 409 {remoteHead}
    W->>A: GET objects (cadena remota)
    W->>R: merge(remoto.principal → principal)
    W->>A: POST refs (reintento)
  end
  A-->>W: 200
  W->>Q: done()
```

## 6. Estados de una observación

```mermaid
stateDiagram-v2
  [*] --> Abierta: creada en campo
  Abierta --> EnRevision: versión sincronizada
  EnRevision --> Abierta: devuelta con comentario
  EnRevision --> Cerrada: aprobada + firma
  Abierta --> Eliminada: borrado lógico
  Eliminada --> Abierta: restaurar versión
  Cerrada --> [*]
```

## 7. Despliegue

```mermaid
flowchart LR
  Phone[Android 8+<br/>App + Room + ObjectStore] -- HTTPS --> GW[Azure Front Door / WAF]
  GW --> AKS["Contenedores API .NET 9<br/>(≥2 réplicas, 2 zonas)"]
  AKS --> PG[(PostgreSQL Flexible<br/>HA zona-redundante + PITR)]
  AKS --> RC[(Azure Cache for Redis)]
  AKS --> BL[(Blob Storage<br/>WORM + GRS)]
  AKS --> ENTRA[Microsoft Entra ID]
  PG -. réplica geo .-> PG2[(Región secundaria)]
  BL -. GRS .-> BL2[(Región secundaria)]
```
