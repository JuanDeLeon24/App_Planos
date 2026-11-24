# CAD Field Viewer Pro

Aplicación Android para **visualizar, medir, anotar y versionar planos CAD (DXF/DWG) en campo**, pensada para ingeniería civil, geología, topografía, minería, túneles y construcción. Funciona **100 % offline**, sincroniza después y está diseñada para **no perder nunca información**.

> Nombre provisional. Estado: **MVP a nivel de código fuente** — núcleo con 25 pruebas verdes; app Android (APK debug) y API compilan en CI; pendiente validación en dispositivo y piloto de campo.

## Qué incluye este repositorio

| Carpeta | Contenido | Estado |
|---|---|---|
| [`android/core/cad`](android/core/cad) | Parser DXF en streaming (carga por capas, progreso, cancelación), escritor DXF, modelo de entidades, teselado de arcos/bulges, quadtree, mediciones (distancia 2D/3D, polilínea, área, centroide, pendiente, ΔZ, volumen de malla, áreas medias, prismoidal), UTM↔WGS84, Helmert local↔UTM, detección de formato por firma (DWG/DXF/PDF/IFC/glTF…), analizador geométrico | ✅ Probado |
| [`android/core/versioning`](android/core/versioning) | Almacén por contenido SHA-256 (write-once, atómico, verificado), commits V1.0/V1.1…, ramas, fusión de 3 vías sin pérdida, restaurar, diff, fsck, **journal WAL con CRC**, sesión de edición con recuperación tras fallo, política de retención | ✅ Probado |
| [`android/app`](android/app) | Kotlin + Jetpack Compose + Room + WorkManager: explorador documental (clasificación, filtros, renombrado masivo), visor 2D (zoom/pan/rotación, culling, capas, selección, propiedades), herramientas de medición con *snapping*, observaciones (texto/flecha/nube/símbolos/foto + GPS), historial/comparar/restaurar/ramas, autosave 30 s, PDF técnico + CSV, compartir, sync diferencial, AES-256-GCM y firma ECDSA en Keystore | ✅ Compila en CI (APK debug) |
| [`backend`](backend) | .NET 9 Minimal API + PostgreSQL + Redis: protocolo de sync diferencial (negotiate / objetos verificados / refs con compare-and-swap), permisos por proyecto, auditoría, JWT/OAuth2, Docker Compose | ✅ Compila en CI (APK debug) |
| [`docs`](docs) | Los 15 entregables: benchmarking, arquitectura, UML, modelo de datos, UX/UI, roadmap/MVP/Enterprise, pruebas, sync, DR, costos, riesgos, Play Store, mantenimiento, manuales | ✅ |

## Garantías anti-pérdida (resumen)

1. Cada acción del usuario se escribe en un **journal con fsync antes** de mostrarse.
2. **Autosave** atómico cada 30 s.
3. Al reabrir tras un cierre inesperado se **reproducen** las operaciones pendientes.
4. Los **originales jamás se sobrescriben** (objetos inmutables, verificados por hash).
5. Restaurar crea una **versión nueva**; el historial nunca se reescribe.
6. En conflictos de sincronización se **conservan ambas versiones**.

## Compilar

```bash
cd android && ./gradlew -p core test          # núcleo: cualquier JDK 17+
cd android && ./gradlew :app:assembleDebug    # app: requiere Android SDK 35
cd backend && docker compose up --build       # API: requiere .env con POSTGRES_PASSWORD y DEV_JWT_KEY
```

## Decisiones clave

- **DWG**: el MVP lee DXF con motor propio; el DWG nativo se integra con **ODA Drawings SDK** (licencia comercial) mediante la interfaz `CadImporter`. LibreDWG se descarta en la app por ser GPL. Ver [docs/03-arquitectura.md](docs/03-arquitectura.md).
- **Precisión**: geometría en float relativa a un origen local y traslación en doble precisión (coordenadas UTM sin pérdida).
- **IA**: el asistente sólo orquesta funciones deterministas (`DrawingAnalyzer`); nunca inventa cifras.
