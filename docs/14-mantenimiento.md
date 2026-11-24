# 14 · Plan de mantenimiento evolutivo

## 1. Cadencia
| Tipo | Frecuencia | Contenido |
|---|---|---|
| Parche (x.y.Z) | Según necesidad (< 48 h para críticos) | Errores, seguridad |
| Menor (x.Y.0) | Cada 4–6 semanas | Funciones del roadmap, mejoras UX |
| Mayor (X.0.0) | Anual | Nuevos módulos (3D, BIM, suite) |
| Dependencias | Mensual (Renovate) | AndroidX, Kotlin, .NET, ODA |
| `targetSdk` | Anual (requisito Play, agosto) | Cambios de plataforma |

## 2. Observabilidad
- App: Crashlytics (crash-free ≥ 99,5 %), métricas de rendimiento (tiempo de apertura por MB, fps), eventos de recuperación del journal (cuántas veces salvó datos).
- Backend: OpenTelemetry → Azure Monitor/Grafana; SLO 99,9 %; alertas en 409 anómalos, errores de hash, latencia p95 > 500 ms.

## 3. Gestión de deuda técnica
- 20 % de cada sprint a deuda/refactor.
- ADRs en `docs/adr/` para cada decisión estructural.
- Compatibilidad: el formato de objetos/commits es **versionado** (`schemaVersion` en F2) y siempre legible hacia atrás; nunca se migra destructivamente un repositorio de usuario.

## 4. Evolución hacia suite de gestión técnica
1. Flujos de trabajo: RFI, no conformidades (NCR), liberaciones de frente, actas.
2. Portal web (Compose Multiplatform / Blazor) sobre la misma API.
3. Integraciones: Primavera P6 / MS Project (avance por progresiva), ERP, GIS (ArcGIS/QGIS vía GeoPackage).
4. Gemelo digital del túnel: progresivas, convergencias, instrumentación geotécnica en tiempo real.
5. API pública + webhooks para integradores.
