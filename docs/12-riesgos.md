# 12 · Riesgos del proyecto

| ID | Riesgo | Prob. | Impacto | Mitigación | Contingencia |
|---|---|---|---|---|---|
| R1 | Licenciamiento DWG (ODA) costoso o con restricciones de distribución | Media | Alto | MVP sin DWG (DXF propio); `CadImporter` desacopla el motor; negociar membresía temprano | Conversión DWG→DXF en servidor con ODA File Converter bajo licencia de servidor |
| R2 | Uso de LibreDWG (GPL) contamina el código cerrado | Baja | Muy alto | Prohibido en la app (ADR-001); revisión de licencias en CI (`licensee`/OSS Review Toolkit) | Retirar dependencia y reemitir build |
| R3 | Rendimiento insuficiente con DXF 1 GB en gama baja | Alta | Alto | Streaming, carga por capas, caché de teselado, GLES en F2 | Modo "sólo capas seleccionadas" obligatorio < 6 GB RAM |
| R4 | Pérdida de datos por bug en sincronización | Baja | Muy alto | Objetos inmutables, CAS, outbox, pruebas DUR, fusión conservadora | Datos siguen en dispositivo; `fsck` + re-push |
| R5 | Precisión de medición cuestionada (unidades, escalas, UTM) | Media | Alto | Validación contra AutoCAD/Civil 3D; unidades `$INSUNITS`; doble precisión; pruebas con referencias geodésicas | Aviso de unidades desconocidas; calibración por 2 puntos |
| R6 | Fragmentación Android / OEMs que matan procesos en segundo plano | Alta | Medio | WorkManager; journal hace irrelevante la muerte del proceso | Guía por OEM (dontkillmyapp) |
| R7 | Rechazo/demora en Play Store por permisos (ubicación, cámara) | Media | Medio | Permisos en contexto, sin ubicación en segundo plano, Data Safety completo | Ubicación opcional |
| R8 | Seguridad: fuga de planos confidenciales | Media | Muy alto | AES-256 Keystore, TLS 1.3, roles, auditoría, MDM, pentest | Revocación remota de tokens; borrado selectivo MDM |
| R9 | Adopción en campo (UX, guantes, luz) | Media | Alto | Piloto temprano en túnel real, pruebas con guantes, alto contraste | Iteraciones quincenales con usuarios |
| R10 | Dependencia de un proveedor de nube | Baja | Medio | Contenedores + PostgreSQL estándar + `IBlobStore` | Migración a S3/MinIO u on-premise |
| R11 | IA da cifras incorrectas | Media | Alto | El LLM sólo orquesta funciones deterministas y cita capas; desactivable | Sin IA, funciones manuales equivalentes |
| R12 | Alcance excesivo (suite completa) | Alta | Alto | MVP cerrado, roadmap por fases con criterios de salida | Re-priorizar por valor de piloto |
