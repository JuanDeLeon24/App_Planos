# 08 · Plan de pruebas

## 1. Pirámide

| Nivel | Herramienta | Alcance | Estado |
|---|---|---|---|
| Unitarias núcleo | JUnit 5 + kotlin.test | Parser DXF, geometría, medición, UTM, quadtree, versionado, fusión, journal, sesión | **25 pruebas, verdes** (`./gradlew -p core test`) |
| Unitarias app | JUnit 4 / Robolectric | ViewModels, exportadores, patrones de renombrado | Base creada |
| Integración Room | `androidx.room:room-testing` | DAOs, migraciones (`MigrationTestHelper`) | F1 |
| UI | Compose UI Test | Flujos: importar → medir → anotar → versionar | F1 |
| API | xUnit + `WebApplicationFactory` + Testcontainers (PostgreSQL, Redis) | negotiate/objects/refs/409 | F1 |
| Contrato | Pact (cliente Kotlin ↔ API) | Esquemas JSON del protocolo de sync | F2 |
| Rendimiento | Macrobenchmark + Perfetto | Apertura, fps, memoria | F1/F2 |
| Caos / durabilidad | Scripts adb | Matar proceso, cortar batería simulada, llenar disco | F1 |
| Seguridad | MobSF, OWASP MASTG, ZAP (API) | MASVS L2 | F2 |
| Campo | Piloto | Túnel real, guantes, sin red 8 h | F1 |

## 2. Casos críticos (cero pérdida)

| ID | Escenario | Resultado esperado | Cobertura |
|---|---|---|---|
| DUR-01 | Proceso muere tras 3 operaciones, antes de autosave | Al reabrir, las 3 operaciones están presentes y se avisa | `EditSessionTest` ✔ |
| DUR-02 | Corte de energía durante escritura de registro del journal | Registro parcial descartado; anteriores intactos | `VersioningTest.journal…` ✔ |
| DUR-03 | Objeto alterado en disco | `CorruptObjectException`; `fsck` lo reporta | `VersioningTest.object store…` ✔ |
| DUR-04 | Restaurar versión antigua | Se crea versión nueva; historial intacto | ✔ |
| DUR-05 | Dos equipos editan la misma observación offline | Fusión conserva ambas versiones + conflicto reportado | ✔ |
| DUR-06 | Borrado vs. modificación concurrente | Gana la modificación | ✔ |
| DUR-07 | Disco lleno durante importación | Error claro; ningún objeto parcial; original del usuario intacto | F1 (instrumentada) |
| DUR-08 | Servidor rechaza ref (409) | Fusión local y reintento; outbox no se vacía hasta 200 | F1 (Testcontainers) |
| DUR-09 | Desinstalación/reinstalación | Restauración desde Auto Backup + nube | F2 |

## 3. Rendimiento (dispositivos de referencia)

| Dispositivo | RAM | DXF 50 MB | DXF 1 GB | Pan/zoom |
|---|---|---|---|---|
| Gama baja (Helio G85) | 4 GB | < 15 s | modo capas | ≥ 30 fps |
| Gama media (SD 7 Gen 1) | 8 GB | < 6 s | < 90 s | ≥ 50 fps |
| Rugerizado (Samsung XCover / Cat) | 4–6 GB | < 12 s | < 150 s | ≥ 30 fps |

Corpus: planos reales anonimizados + generados (`DxfParserTest` genera 200 000 entidades en streaming).

## 4. Criterios de aceptación de release
- 100 % pruebas unitarias/integración verdes; cobertura núcleo ≥ 85 %.
- 0 defectos críticos/altos abiertos; crash-free sessions ≥ 99,5 % en beta cerrada.
- DUR-01…08 verdes en dispositivo físico.
- Validación de mediciones contra AutoCAD/Civil 3D en 30 casos (error ≤ 1 mm a escala 1:1).
