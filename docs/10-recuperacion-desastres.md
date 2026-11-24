# 10 · Protección de datos y recuperación ante desastres

## 1. Defensa en profundidad (dispositivo)

| Capa | Mecanismo | Protege contra |
|---|---|---|
| 1 | **Journal WAL** por operación con fsync + CRC32 | Cierre inesperado, crash, batería agotada, reinicio |
| 2 | **Autosave 30 s** atómico (temp + fsync + rename) + checkpoint del journal | Crecimiento del journal; reaperturas rápidas |
| 3 | **Versiones inmutables** (commits SHA-256) con retención configurable | Errores humanos ("deshice todo") |
| 4 | **Originales write-once** y de sólo lectura | Sobrescritura accidental |
| 5 | **Verificación de integridad** (hash al leer, `fsck`) | Corrupción de almacenamiento / tarjeta SD |
| 6 | **Android Auto Backup** (índice + repositorio) | Pérdida/cambio de teléfono |
| 7 | **Sincronización a nube** | Destrucción del dispositivo |

Retención de snapshots automáticos (`RetentionPolicy`): todo en la última hora, horario 48 h, diario 30 días, semanal después; o ilimitado.

## 2. Recuperación tras fallo (automática)
1. Al abrir el documento, `EditSession` carga `working.json` y reproduce las operaciones del journal posteriores.
2. Un registro truncado (corte a mitad de escritura) se detecta por longitud/CRC y se descarta; el archivo se trunca al último registro válido.
3. Se informa al usuario cuántos cambios se recuperaron.
4. Si `working.json` está dañado, se reconstruye desde la última versión + journal completo.
5. Si un objeto está corrupto, `fsck` lo identifica y se re-descarga desde la nube (mismo hash).

## 3. Backend

| Parámetro | Objetivo |
|---|---|
| RPO | ≤ 5 min (PostgreSQL PITR + Blob GRS); **0** para datos ya confirmados por el dispositivo (siguen en el dispositivo) |
| RTO | ≤ 1 h (región secundaria) |
| PostgreSQL | HA zona-redundante, PITR 35 días, réplica geo de lectura |
| Blobs | Versionado + soft delete 30 días + inmutabilidad WORM para originales + GRS |
| Redis | Sólo caché: reconstruible, sin backup |
| Pruebas DR | Simulacro trimestral de *failover* y restauración PITR a entorno aislado |

## 4. Runbook — pérdida de región primaria
1. Declarar incidente; congelar despliegues.
2. Promover réplica PostgreSQL secundaria; apuntar cadena de conexión (Key Vault).
3. Conmutar Blob a la región secundaria (failover de cuenta GRS).
4. Escalar API en la región secundaria; Front Door redirige tráfico.
5. Los dispositivos continúan offline sin interrupción; la outbox se vacía cuando la API vuelve.
6. Verificar con `fsck` de servidor (hashes de objetos referenciados por `Refs`).
7. Postmortem sin culpables en 5 días.
