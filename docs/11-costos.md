# 11 · Análisis de costos

> Estimaciones de referencia en USD (2026), equipo nearshore/LatAm. Ajustar con cotizaciones reales; las licencias de terceros (en particular ODA) se negocian caso a caso.

## 1. Desarrollo

| Fase | Duración | Equipo (FTE) | Costo estimado |
|---|---|---|---|
| F0 Fundaciones | 3 sem | 2 | 12 000 |
| F1 MVP | 10 sem | 4 (2 Android, 1 backend, 1 QA/UX) | 80 000 |
| F2 Producción 2D + DWG | 12 sem | 5 | 120 000 |
| F3 3D / BIM | 14 sem | 5 | 140 000 |
| **Total hasta F3** | ~10 meses | | **≈ 352 000** |

Tarifa media supuesta: 4 000 USD/FTE-mes (combinado).

## 2. Licencias y servicios

| Concepto | Costo anual estimado | Comentario |
|---|---|---|
| ODA (Drawings SDK, membresía comercial) | 3 000 – 25 000+ | Depende del nivel de membresía y de la distribución; obligatorio para DWG nativo |
| Google Play Developer | 25 (único) | |
| Azure: API (2 réplicas), PostgreSQL Flexible HA, Redis, Blob GRS 1 TB, Front Door | 9 000 – 18 000 | Escala con usuarios/almacenamiento |
| Firebase Crashlytics / Analytics | 0 | |
| Certificado firma de código / PKI (Enterprise) | 500 – 3 000 | |
| LLM asistente (por uso) | 0,5 – 3 USD/usuario-mes | Opcional; sólo metadatos |
| Pentest anual | 8 000 – 15 000 | |

## 3. Modelo de ingresos sugerido

| Plan | Precio | Contenido |
|---|---|---|
| Community | Gratis | DXF, PDF, mediciones, anotaciones, versiones locales |
| Pro | 9 USD/usuario-mes | DWG, 3D/IFC, nube 50 GB, exportes avanzados |
| Enterprise | 25–40 USD/usuario-mes (mín. 25) | SSO, auditoría/WORM, MDM, on-premise, SLA |

**Punto de equilibrio** (opex anual ≈ 120 000 incl. mantenimiento): ~400 usuarios Pro o ~300 Enterprise.

## 4. Optimización
- Deduplicación por hash reduce almacenamiento en nube 30–60 % (los mismos planos los importan muchos usuarios).
- Núcleo Kotlin puro reutilizable en escritorio/servidor → evita reescrituras.
- Conversión pesada (FBX, IFC grandes) en servidor bajo demanda en lugar de en el dispositivo.
