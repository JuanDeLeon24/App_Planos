# 02 · Requisitos funcionales y no funcionales

## 1. Actores
- **Ingeniero de campo** (offline, guantes, sol/baja luz): navega, mide, anota, fotografía.
- **Revisor de oficina técnica**: compara versiones, fusiona ramas, cierra observaciones, firma.
- **Administrador de proyecto**: crea proyectos, permisos, clasificación, políticas de retención.
- **Auditor / cliente**: consulta historial y auditoría (sólo lectura).

## 2. Compatibilidad de archivos (estado por fase)

| Familia | Formato | Motor | Fase | Estado en este repositorio |
|---|---|---|---|---|
| CAD | DXF ASCII (R12–2018) | Parser propio streaming (`core/cad`) | MVP | **Implementado y probado** |
| CAD | DXF binario | Parser propio | F2 | Detectado; mensaje guía |
| CAD | DWG (R14–2018+) | ODA Drawings SDK vía `CadImporter` | F2 / Enterprise | Detección de versión; punto de extensión |
| CAD | DWF / DWFx | ODA (DWF) | F3 | Detectado |
| CAD | DGN V7/V8 | ODA Drawings SDK (DGN) | F3 | Detectado |
| BIM | IFC / IFCZIP / IFCXML | web-ifc (MPL) o IfcOpenShell (LGPL) | F3 | Detectado |
| Modelos | OBJ, STL, GLTF, GLB | Filament + loaders | F3 | Detectado |
| Modelos | FBX | Conversión servidor (FBX→glTF, Assimp) | F4 | — |
| PDF | Técnico / vectorial | `PdfRenderer` (raster) → PDFium vectorial | F2 | — |
| Imagen | PNG, JPG, TIFF | `ImageDecoder` / libtiff | F2 | Detectado |

## 3. Requisitos funcionales (resumen trazable)

| ID | Requisito | Implementación |
|---|---|---|
| RF-01 | Zoom infinito, pan, rotación | `CadCanvas` (matriz afín en doble precisión) |
| RF-02 | Selección de entidades y propiedades | `ViewerViewModel.pick`, `PropertiesCard` |
| RF-03 | Ocultar objetos | `hideSelected` → metadatos versionados |
| RF-04 | Capas: on/off, buscar, agrupar, transparencia, color | `LayerPanel` |
| RF-05 | Distancia, polilínea, área, pendiente, ΔZ, coordenadas | `Measurements`, `Tool` |
| RF-06 | Volúmenes 3D (malla), áreas medias, prismoidal | `Measurements.meshVolume`, `averageEndAreaVolume` |
| RF-07 | UTM ↔ geográficas; local ↔ UTM (Helmert) | `geo/Utm.kt` |
| RF-08 | Anotaciones: texto, flecha, nube, símbolos, foto; GPS; usuario/fecha | `AnnotationPayload`, `NoteDialog` |
| RF-09 | PDF técnico (escala, notas, firma) y CSV/Excel | `Exporters` |
| RF-10 | Compartir (correo, WhatsApp, Teams, OneDrive, SharePoint) | Hoja del sistema `Exporters.share` |
| RF-11 | Clasificación proyecto/frente/túnel/disciplina/fecha + etiquetas | `DocumentEntity`, `TagEntity` |
| RF-12 | Renombrado masivo con patrón | `ExplorerViewModel.renameBatch` |
| RF-13 | Versiones V1.0, V1.1… con usuario, fecha, comentario | `VersionRepository` |
| RF-14 | Comparar, restaurar, ramas, fusionar observaciones | `diff`, `restore`, `createBranch`, `merge` |
| RF-15 | Nunca sobrescribir originales | `FileObjectStore` write-once |
| RF-16 | Autosave 30 s, snapshots, recuperación | `EditSession`, `Journal`, `RetentionPolicy` |
| RF-17 | Sincronización diferencial con resolución de conflictos | `SyncWorker` + API |
| RF-18 | Análisis CAD (repetidos, errores, inconsistencias) | `DrawingAnalyzer` |
| RF-19 | Asistente técnico (área, longitud, conteo) | `DrawingAnalyzer` como herramientas del LLM |
| RF-20 | Auditoría completa | `audit_log` (local) + `AuditEntry` (servidor) |

## 4. Requisitos no funcionales

| ID | Atributo | Objetivo | Cómo se garantiza |
|---|---|---|---|
| RNF-01 | Apertura DXF 1 GB | < 90 s en gama media (Snapdragon 7 / 8 GB), con progreso | Parser streaming, carga por capas (`layerFilter`), teselado en background |
| RNF-02 | DWG 500 MB | < 60 s con ODA + caché de teselado | Motor nativo + caché `.tess` por hash |
| RNF-03 | Fluidez | ≥ 50 fps en pan/zoom con 1 M segmentos visibles | Quadtree + LOD + `drawLines` hairline |
| RNF-04 | Memoria | ≤ 1,5× tamaño de geometría teselada; sin OOM | `FloatArray` compactos, `largeHeap`, paginación de capas |
| RNF-05 | Durabilidad | RPO = 0 operaciones confirmadas en UI | Journal fsync antes de reflejar en la UI |
| RNF-06 | Disponibilidad backend | 99,9 % | Ver `10-recuperacion-desastres.md` |
| RNF-07 | Seguridad | OWASP MASVS L2 | AES-256-GCM Keystore, ECDSA, JWT/OAuth2, TLS 1.3 |
| RNF-08 | Accesibilidad | WCAG 2.1 AA, toques ≥ 48 dp | Tema alto contraste, chips grandes |
| RNF-09 | Compatibilidad | Android 8.0+ (API 26) | `minSdk 26` |
