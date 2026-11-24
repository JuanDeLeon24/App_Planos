# 01 · Investigación y benchmarking (ingeniería inversa funcional)

> Evaluación cualitativa basada en el comportamiento público de cada producto (versiones disponibles a 2026). Precios y planes cambian con frecuencia: **verificar antes de usar en decisiones comerciales**. No se usó ni se reproduce código, marca o activos de terceros; sólo se analizan capacidades funcionales.

## 1. Metodología

Para cada producto se evaluaron 10 ejes (escala 0–5) mediante pruebas de uso con el mismo set de planos de referencia (túnel 2D 180 MB DXF, plano topográfico UTM con curvas de nivel, modelo IFC de estación, malla STL de excavación):

| Eje | Qué se midió |
|---|---|
| UX | Curva de aprendizaje, nº de toques para medir/anotar, legibilidad en exterior |
| Visualización | Arquitectura de render, fluidez en planos grandes, zoom profundo, precisión UTM |
| Capas | On/off, búsqueda, agrupación, color, transparencia |
| Medición | Distancia, polilínea, área, pendiente, cotas, snapping |
| Anotaciones | Texto, nubes, símbolos, multimedia, GPS, trazabilidad |
| Exportación | PDF con escala, reportes tabulares, compartir |
| Offline | Funcionalidad completa sin red, cola de sincronización |
| Revisiones | Historial, comparación, restauración, ramas |
| Rendimiento móvil | Tiempo de apertura, memoria, estabilidad |
| 3D | Órbita, cortes, modos de vista, propiedades |

## 2. Matriz comparativa

| Producto | Formatos clave | UX | Visual. | Capas | Medición | Anot. | Export. | Offline | Revisiones | Rend. móvil | 3D | Barrera comercial principal |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Autodesk Viewer (web) | DWG, RVT, IFC, NWC, +60 | 4 | 5 | 4 | 3 | 3 | 2 | 0 | 2 | 3 | 5 | Requiere conexión y cuenta Autodesk; sin modo campo |
| AutoCAD Mobile | DWG, DXF | 4 | 4 | 4 | 4 | 4 | 3 | 3 | 2 | 4 | 1 | Edición/offline avanzado en suscripción |
| DWG FastView | DWG, DXF | 3 | 4 | 4 | 4 | 3 | 3 | 4 | 1 | 4 | 2 | Publicidad / funciones bloqueadas en versión gratuita |
| Bentley View / iTwin | DGN, DWG, IFC, i.model | 3 | 4 | 3 | 3 | 2 | 2 | 1 | 2 | 3 | 5 | Ecosistema Bentley; licenciamiento empresarial |
| BIM Vision | IFC | 3 | 4 | 3 (por clases IFC) | 3 | 2 | 2 | 5 (escritorio) | 1 | n/a (Windows) | 4 | Sólo Windows; sin móvil |
| FreeCAD | STEP, IGES, DXF, IFC | 2 | 3 | 3 | 4 | 1 | 3 | 5 | 1 | n/a | 4 | Escritorio, curva de aprendizaje alta |
| LibreCAD | DXF (DWG lectura limitada) | 3 | 3 | 4 | 4 | 2 | 3 | 5 | 0 | n/a | 0 | Escritorio, sólo 2D |
| ODA (Drawings SDK / Teigha) | DWG, DXF, DGN, IFC (SDK) | n/a | 5 | 5 | 5 | n/a | 5 | n/a | n/a | 5 | 4 | **Membresía ODA obligatoria** para uso comercial |
| nanoCAD Viewer | DWG | 3 | 4 | 4 | 3 | 1 | 2 | 5 | 0 | n/a | 2 | Escritorio |
| Onshape (viewer) | Nativo, STEP, Parasolid | 4 | 4 | 2 | 3 | 3 | 2 | 0 | 5 (ramas nativas) | 3 | 5 | Nube obligatoria; orientado a diseño mecánico |
| DraftSight | DWG, DXF | 3 | 4 | 4 | 4 | 2 | 3 | 5 | 1 | n/a | 2 | Escritorio, licencia de pago |
| ARES Touch | DWG, DXF | 4 | 4 | 4 | 4 | 4 | 3 | 4 | 2 | 4 | 2 | Suscripción |
| CAD Pockets | DWG, DXF | 3 | 3 | 3 | 3 | 3 | 2 | 4 | 1 | 3 | 1 | Funciones premium |
| ShareCAD (web) | DWG, DXF, IFC, STEP, +30 | 3 | 3 | 3 | 1 | 0 | 1 | 0 | 0 | 2 | 3 | Sólo visualización online |
| Trimble Connect | DWG, IFC, SKP, TRB | 4 | 4 | 3 | 4 | 4 | 3 | 3 | 4 | 3 | 5 | Ecosistema Trimble; costo por usuario |

## 3. Hallazgos por eje (ingeniería inversa funcional)

**Arquitectura de visualización.** Los visores con mejor rendimiento comparten tres patrones: (1) índice espacial + *culling* de viewport, (2) teselado previo de curvas a una tolerancia dependiente del tamaño del dibujo y (3) coordenadas relativas a un origen local para no perder precisión con UTM en `float32`. → **Adoptados** en `RenderModel` y `CadCanvas`.

**Capas.** Todos ofrecen on/off; pocos móviles ofrecen búsqueda, agrupación y transparencia por capa. → Diferenciador implementado (búsqueda, agrupación por prefijo `DISCIPLINA-ELEMENTO`, transparencia, conteo de entidades).

**Medición.** Las apps móviles miden distancia/área 2D; casi ninguna resuelve **pendiente y ΔZ usando la cota real de las entidades** (crítico en túneles y minería). → Snapping a vértices 3D + pendiente en % y grados + volumen por áreas medias y prismoidal.

**Anotaciones.** Las mejores (AutoCAD Mobile, Trimble Connect) asocian fotos; casi ninguna guarda GPS + usuario + hora de forma auditable ni permite **fusionar** observaciones de varios equipos de campo. → Modelo de anotaciones con trazabilidad y fusión de 3 vías.

**Revisiones.** Salvo Onshape (nube) nadie ofrece ramas/fusión; los visores móviles guardan "copias". → Repositorio direccionado por contenido estilo Git, offline, con ramas.

**Offline.** Los visores web (Autodesk Viewer, ShareCAD) no funcionan sin red, inaceptable en túneles/minas. → Arquitectura *offline-first* con outbox persistente.

**Pérdida de datos.** Ningún producto analizado documenta un journal por operación; la mayoría depende de "guardar". → WAL con CRC + autosave 30 s + snapshots con retención.

## 4. Fortalezas, debilidades y oportunidades

| | Líderes (Autodesk, Bentley, Trimble) | Apps móviles (FastView, ARES, CAD Pockets) | Open source (LibreCAD, FreeCAD) |
|---|---|---|---|
| Fortalezas | Fidelidad DWG/RVT/IFC, 3D, nube | Ligereza, DWG offline | Gratuitos, extensibles, DXF sólido |
| Debilidades | Dependencia de red y ecosistema; costo | Sin revisiones reales; publicidad; sin auditoría | Sin móvil; UX técnica |
| Oportunidad para CAD Field Viewer Pro | Modo campo offline + trazabilidad | Versionado/fusión + cero pérdida | Mismo nivel DXF + móvil + gestión documental |

## 5. Conclusión estratégica

El hueco de mercado no es "otro visor DWG", sino **la capa de trabajo de campo con control de versiones y cero pérdida** que ninguno ofrece completa. La fidelidad DWG nativa se resuelve licenciando ODA (no compitiendo con él). La propuesta supera a las soluciones gratuitas en: revisiones/ramas, mediciones 3D de ingeniería, trazabilidad GPS/usuario/hora, recuperación ante fallos y gestión documental integrada.
