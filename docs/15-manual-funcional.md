# 15 · Documentación funcional y técnica

## Parte A — Manual de usuario

### A.1 Primeros pasos
1. **Crear proyecto**: botón *Crear proyecto* (p.ej. "Túnel Línea 2").
2. **Importar planos**: *Importar planos* → seleccionar uno o varios archivos (almacenamiento, Drive, OneDrive o adjuntos). Clasificar por disciplina, frente y túnel. El original se guarda como **V1.0 inmutable**.
3. También puede *Compartir → CAD Field Viewer Pro* desde correo o WhatsApp.

### A.2 Navegar
- Un dedo: desplazar. Pellizcar: zoom. Doble toque: acercar. Botón ⤢: ver todo.
- Rotación: menú ⋮ → *Permitir rotación*.

### A.3 Capas
Icono ≋ → buscar, encender/apagar, ajustar transparencia, *Todas/Ninguna*. Las capas se agrupan por prefijo (`GEO-…`, `TOP-…`). El estado de capas se guarda con el documento.

### A.4 Medir
Elegir herramienta en la barra inferior y tocar el plano (los puntos se ajustan a vértices cercanos):
- **Distancia**: 2 puntos → distancia 2D, 3D y ΔZ.
- **Polilínea**: n puntos → longitud acumulada.
- **Área**: ≥ 3 puntos → área y perímetro.
- **Pendiente**: 2 puntos con cota → % y grados.
- **Coordenada**: X, Y, Z del punto.
*Guardar* convierte la medición en una observación trazable.

### A.5 Observaciones de campo
*Nota*, *Flecha* o *Nube* → tocar el lugar → descripción, símbolo técnico y opcionalmente foto. Se registra automáticamente usuario, fecha, hora y GPS.

### A.6 Versiones
- 💾 *Guardar versión*: crea V1.1, V1.2… con su comentario. Firmada digitalmente.
- ⋮ → *Historial*: tocar dos versiones para **compararlas**; ↺ para **restaurar** (crea una versión nueva, nunca borra historia); ⑂ para **crear rama** o **fusionar** observaciones de otra rama.
- Aunque no guarde versión, **nada se pierde**: cada acción se registra al instante y se autoguarda cada 30 s.

### A.7 Exportar y compartir
⋮ → *Exportar PDF técnico + CSV*: PDF A3 con plano, observaciones numeradas, escala gráfica, cajetín y firma; CSV abre en Excel. Se abre la hoja de compartir (correo, WhatsApp, Teams, OneDrive, SharePoint).

### A.8 Sincronización
Automática con Wi-Fi; ⟳ fuerza la sincronización. Iconos: ☁✓ sincronizado · ☁↑ pendiente · ⚠ conflicto (ambas versiones se conservaron; revisar en historial).

## Parte B — Guía técnica para desarrolladores

### B.1 Estructura
```
android/
  core/            build Kotlin/JVM independiente (cad, versioning)
  app/             aplicación Android (Compose, Room, WorkManager)
backend/
  CadField.Api/    .NET 9 Minimal API
  docker-compose.yml
docs/              esta documentación
```

### B.2 Compilar y probar
```bash
cd android && ./gradlew -p core test          # núcleo (cualquier JVM 17+)
cd android && ./gradlew :app:assembleDebug    # requiere Android SDK 35
cd backend && docker compose up --build       # requiere .env con POSTGRES_PASSWORD y DEV_JWT_KEY
```

### B.3 Agregar un formato nuevo
1. Implementar `CadImporter` (`supported`, `import(stream, size, progress)`) produciendo `CadDocument`.
2. Registrarlo en `DocumentRepository.importers` (p.ej. en `CadFieldApp.onCreate`).
3. Añadir firma en `FormatDetector` si procede y pruebas con archivos de corpus.

### B.4 Agregar una herramienta de medición
1. Función pura en `Measurements` + prueba unitaria con valor de referencia.
2. Valor en `enum Tool`, rama en `ViewerViewModel.describe/onTap` y chip en `ViewerScreen.tools`.

### B.5 Invariantes que no deben romperse
- Nunca escribir sobre un objeto existente del `ObjectStore`.
- Toda edición pasa por `EditSession.execute` (journal antes que memoria).
- Nunca `fallbackToDestructiveMigration` en Room.
- La UI nunca muestra un cambio que no esté en el journal.
- Las cifras del asistente IA provienen sólo de `DrawingAnalyzer`/`Measurements`.
