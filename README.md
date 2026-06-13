# eb-migrator

Utilitario CLI en **Java 11** para **auditar y migrar environments de AWS Elastic Beanstalk**
desde plataformas deprecadas (**Amazon Linux 2**) a **Amazon Linux 2023**, usando el
procedimiento **Blue/Green con intercambio de CNAMEs** (preserva la URL de producción).

Automatiza el mismo procedimiento validado a mano en la migración piloto de `samy-app-usuarios`,
añadiendo guardarraíles: confirmaciones, detección del `ImageId` de AL2, uso del artefacto
realmente desplegado y auto-detección del solution stack destino.

---

## 1. Requisitos

| Requisito | Versión usada / nota |
|---|---|
| **JDK** | Java 11 (probado con Temurin 11.0.20) |
| **Maven** | 3.8+ |
| **AWS CLI configurado** | Un perfil local en `~/.aws/credentials` (por defecto `eb-manager`) |
| **Permisos del perfil** | Elastic Beanstalk + lectura de S3 (bucket gestionado de EB). El perfil `eb-manager` tiene `AdministratorAccess-AWSElasticBeanstalk` |

> **Seguridad:** el utilitario **nunca incrusta credenciales**. Las toma del perfil local del
> AWS CLI mediante `ProfileCredentialsProvider`. No imprime secretos.

---

## 2. Compilar

Desde la raíz del proyecto (`eb-migrator-java/`):

```bash
mvn -DskipTests package
```

Genera un **JAR ejecutable autónomo** (incluye el AWS SDK v2 vía `maven-shade-plugin`):

```
target/eb-migrator-1.0.0.jar
```

---

## 3. Ejecutar

Hay **dos modos**, ambos disponibles:

### 3.1 Modo consola interactivo (recomendado)

Sin argumentos abre un **menú** que pide los datos por pantalla:

```bash
java -jar target/eb-migrator-1.0.0.jar
# equivalente explícito:
java -jar target/eb-migrator-1.0.0.jar menu
```

Flujo del menú:

```
==========================================================
  eb-migrator · consola interactiva
==========================================================
Perfil AWS [eb-manager]:           ← Enter usa el valor por defecto
Región [us-east-2]:

------------------------ MENÚ ------------------------
  1) audit             Inventario de environments (solo lectura)
  2) create-replica    Crear réplica AL2023 (no toca producción)
  3) swap              Intercambiar URLs (afecta producción)
  4) repoint-pipeline  Reapuntar la etapa Deploy de CodePipeline al nuevo env
  5) terminate         Dar de baja un environment (destructivo)
  6) power             Suspender autoescalado / apagar / encender
  7) resize            Cambiar el tipo de instancia (t2 → t3/t3a, subir RAM)
  0) salir
------------------------------------------------------
Elige una opción [0]:
```

Cada opción pregunta sus parámetros (environment origen, FROM/TO, etc.) y **mantiene las
confirmaciones de seguridad** (el modo interactivo nunca omite las preguntas).

### 3.2 Modo por flags (scripts / no interactivo)

```bash
java -jar target/eb-migrator-1.0.0.jar <comando> [--flags]
```

| Comando | Descripción | ¿Toca producción? |
|---|---|---|
| `audit` | Inventario de environments con su plataforma y salud; marca `DEPRECADA (AL2)` / `RETIRADA` / `OK`. **Solo lectura.** | No |
| `create-replica --env NOMBRE [--target-stack "..."]` | Crea la réplica AL2023 (plantilla → versión de app → environment) y espera a `Ready`. Imprime la URL temporal. | No |
| `swap --from VIEJO --to NUEVO` | Intercambia los CNAMEs para preservar la URL. Reversible. | **Sí** |
| `repoint-pipeline --from VIEJO --to NUEVO` | Reapunta la etapa Deploy de CodePipeline (clave `EnvironmentName`) del environment viejo al nuevo. Escanea todos los pipelines, hace backup y cambio quirúrgico. **Requiere permisos CodePipeline** (`--profile default`). Reversible (invierte `--from/--to`). | Pipeline (no toca tráfico) |
| `terminate --env NOMBRE` | Da de baja un environment. **Destructivo**, exige teclear el nombre exacto. Espera a `Terminated` real y detecta huérfanas. | Destructivo |
| `power --env NOMBRE --state ESTADO` | Controla el **escalado** (`scaling-off`/`scaling-on`) y la **instancia** (`stop`/`start`/`reboot`/`terminate-instance`). Ver §6. | Según estado |
| `resize --env NOMBRE [--to TIPO]` | Cambia el **tipo de instancia** (migra familia `t2` → `t3`/`t3a` y/o sube la RAM) vía `update-environment`. Sin `--to` sugiere un destino y lo pide por consola. Ver §6.b. | **Sí** (reemplaza la instancia: breve corte en SingleInstance) |

**Flags globales:**

| Flag | Por defecto | Significado |
|---|---|---|
| `--profile P` | `eb-manager` | Perfil del AWS CLI |
| `--region R` | `us-east-2` | Región |
| `--yes` | (off) | No interactivo: omite confirmaciones (úsalo con cuidado) |

Ayuda completa:

```bash
java -jar target/eb-migrator-1.0.0.jar --help
```

---

## 4. Flujo completo de una migración

El utilitario **no encadena solo** los pasos peligrosos: te obliga a validar entre cada uno.

```bash
# 1. Ver qué environments están en plataformas deprecadas (solo lectura)
java -jar target/eb-migrator-1.0.0.jar audit

# 2. Crear la réplica AL2023 (NO toca producción). Imprime una URL temporal.
java -jar target/eb-migrator-1.0.0.jar create-replica --env Samyappcasos-env

#    → validar la URL temporal: login, endpoints reales, /actuator/health, etc.

# 3. Intercambiar las URLs (la de producción pasa a servirla la réplica AL2023)
java -jar target/eb-migrator-1.0.0.jar swap --from Samyappcasos-env --to Samyappcasos-env-al2023

#    → validar la URL de PRODUCCIÓN tras el swap (propagación DNS ~1-2 min)

# 4. Reapuntar el CodePipeline (etapa Deploy) al nuevo environment
#    (requiere permisos CodePipeline → perfil admin)
java -jar target/eb-migrator-1.0.0.jar repoint-pipeline \
     --from Samyappcasos-env --to Samyappcasos-env-al2023 --profile default

# 5. Cuando todo esté confirmado, dar de baja el environment viejo (destructivo)
java -jar target/eb-migrator-1.0.0.jar terminate --env Samyappcasos-env
```

### Qué hace `create-replica` por dentro (4 pasos)

1. **Plantilla de configuración** desde el environment origen; detecta y avisa del `ImageId`
   de AL2 (se elimina al crear el nuevo).
2. **Localiza el artefacto realmente desplegado** en el bucket gestionado de EB
   (`resources/environments/<env-id>/_runtime/_versions/<app>/`), el más reciente. Evita el
   error de permisos del bucket de CodePipeline.
3. **Registra una versión de app** apuntando a ese artefacto.
4. **Crea el environment AL2023** (`--options-to-remove ImageId`) y hace *polling* cada 15 s
   hasta `Ready` (timeout 15 min). Imprime la URL temporal.

El solution stack destino se **auto-detecta** (mismo runtime, p. ej. `Corretto 11`, versión
AL2023 más reciente). Usa `--target-stack "..."` solo para forzar uno concreto.

---

## 5. Rollback

Mientras el environment viejo siga vivo (antes del `terminate`), el rollback es **instantáneo**:
repetir el `swap` entre los dos environments devuelve la URL al original.

```bash
java -jar target/eb-migrator-1.0.0.jar swap --from Samyappcasos-env-al2023 --to Samyappcasos-env
```

> ⚠️ Tras `terminate` ya no hay rollback. Confirma siempre la URL de producción antes de terminar.

---

## 6. Escalado e instancia (`power`)

Todo environment de EB —incluso *SingleInstance*— tiene un **Auto Scaling Group con Min=Max=Desired=1**
que "auto-sana": si apagas la instancia, **la vuelve a lanzar**. El comando `power` controla por
separado dos cosas: el **ESCALADO** (el ASG) y la **INSTANCIA** (encender/apagar/reiniciar/terminar).

| `--state` | Categoría | Qué hace | ¿Perfil EC2? |
|---|---|---|---|
| `scaling-off` | Escalado | **Desactiva el escalado**: suspende el ASG (deja de auto-reponer/auto-sanar). No toca la instancia. | No (`eb-manager` vale) |
| `scaling-on` | Escalado | **Reactiva el escalado**: reanuda el ASG (EB vuelve a gestionar). | No |
| `stop` | Instancia | Apaga la instancia (stop) y desactiva el escalado para que no la reponga. Conserva el EBS. | **Sí** |
| `start` | Instancia | Enciende la instancia (start). El escalado sigue desactivado (control manual). | **Sí** |
| `reboot` | Instancia | Reinicia la instancia (reboot). No toca el escalado. | **Sí** |
| `terminate-instance` | Instancia | **Destruye** la instancia EC2. Si el escalado está activo, EB lanza una nueva (reciclado); si está desactivado, queda sin instancia. No da de baja el environment. | **Sí** |

> Alias retrocompatibles: `off`=`stop`, `on`=`start`, `manage`=`scaling-on`, `restart`=`reboot`.

```bash
# DEV: apagar de noche para no generar costo (y poder encender por la mañana)
java -jar target/eb-migrator-1.0.0.jar power --env Samyappusuarios-env-al2023 --state stop  --profile default
java -jar target/eb-migrator-1.0.0.jar power --env Samyappusuarios-env-al2023 --state start --profile default

# PROD: desactivar el escalado (uso bajo, no se quiere auto-reponer/auto-sanar) y dejarla encendida
java -jar target/eb-migrator-1.0.0.jar power --env Samyappusuarios-env-prod --state scaling-off

# Reiniciar / reciclar la instancia
java -jar target/eb-migrator-1.0.0.jar power --env Samyappusuarios-env-prod --state reboot             --profile default
java -jar target/eb-migrator-1.0.0.jar power --env Samyappusuarios-env-prod --state terminate-instance --profile default

# Volver a la gestión normal de EB (reactivar escalado/auto-sanado)
java -jar target/eb-migrator-1.0.0.jar power --env Samyappusuarios-env-al2023 --state scaling-on
```

**Permisos:** las operaciones de **escalado** (`scaling-off`/`scaling-on`) usan el ASG y funcionan con
`eb-manager`. Las de **instancia** (`stop`/`start`/`reboot`/`terminate-instance`) requieren permisos EC2
(`ec2:Stop/Start/Reboot/TerminateInstances`), que `eb-manager` (mínimo privilegio) NO tiene → usa
`--profile default` (admin). Si te falta el permiso, el comando lo dice; en `stop`, el escalado ya
queda desactivado (solo reintentas el apagado con el perfil admin).

> **Casos de uso del usuario:**
> - **Dev** (`*-env-al2023`): se apaga de noche para ahorrar. Basta `stop` (desactiva escalado + apaga)
>   y `start` por la mañana. El escalado queda desactivado entre medias, así que no se vuelve a prender solo.
> - **Prod** (`*-env-prod`): uso bajo, no se quiere autoescalado. `scaling-off` lo deja encendido pero sin
>   que EB lo reponga/escale. (Para volver al auto-sanado: `scaling-on`.)
>
> **Producción:** si dejas un environment con el escalado desactivado y luego quieres terminarlo,
> **`terminate` reactiva el ASG automáticamente** antes de desmontarlo (ver §7), para no dejar
> instancias huérfanas que atasquen el borrado.

---

## 6.b Cambiar el tipo de instancia (`resize`)

Migra la familia **`t2`** (burstable, deprecada) a **`t3`/`t3a`** (Nitro, ~10% más barata en `t3a`) y/o
**sube la RAM** para quitar la presión de memoria / GC de la JVM (Spring Boot / Java 11) que provoca
picos de latencia (ver [contexto §6](../CONTEXTO-MIGRACION-EB.md#6-migración-de-tipo-de-instancia-t2--t3t3a)).

El cambio se aplica con `update-environment` sobre la opción `aws:ec2:instances / InstanceTypes`:
**solo toca Elastic Beanstalk**, así que basta el perfil `eb-manager` (no necesita permisos EC2).

```bash
# Indicando el tipo destino explícitamente
java -jar target/eb-migrator-1.0.0.jar resize --env Samyappprimary-env-prod --to t3a.small

# Sin --to: muestra el tipo actual, sugiere un destino (t3a, un tamaño más grande) y lo pide por consola
java -jar target/eb-migrator-1.0.0.jar resize --env Samyappcasos-env-prod
```

Qué hace por dentro:

1. **Lee el tipo actual** del environment (opción moderna `aws:ec2:instances/InstanceTypes`, con
   *fallback* a la heredada `aws:autoscaling:launchconfiguration/InstanceType`) y si es **SingleInstance**.
2. **Sugiere** dos destinos en familia `t3a`: el *drop-in* (misma RAM) y **subir un tamaño** (más RAM,
   recomendado para JVM con presión de memoria). Si no pasas `--to`, propone el de más RAM y lo pide.
3. **Avisa de la indisponibilidad**: en SingleInstance, cambiar el tipo **reemplaza la instancia** →
   breve corte del servicio. Pide confirmación.
4. **Aplica** `update-environment` y **espera a que vuelva a `Ready`** (sondeo cada 15 s, timeout 15 min).
5. **Recuerda ajustar el heap de la JVM (`-Xmx`)** acorde a la nueva RAM.

> **Mapeo `t2 → t3/t3a` (CONTEXTO §6):** mismo nº de vCPU/RAM entre `t3` (Intel) y `t3a` (AMD, ~10% más
> barato). Para JVM/Spring Boot que recibe prod, **drop-in no basta** (`t2.micro → t3.micro` mantiene 1 GB,
> y la app está al 92%): subir a **`t3a.small` (2 GB)** como mínimo. Node/estáticos y bajo tráfico →
> drop-in `t3a.micro`. Fallback a `t3` si la AZ no tiene `t3a`.

> **Permisos:** `resize` solo usa Elastic Beanstalk (`elasticbeanstalk:UpdateEnvironment` /
> `DescribeConfigurationSettings`), incluidos en `AdministratorAccess-AWSElasticBeanstalk` → funciona con
> `eb-manager`, **no** requiere `--profile default`.
>
> **Modo no interactivo:** con `--yes` debes indicar `--to` (no se adivina un cambio que reemplaza la
> instancia). En modo interactivo, sin `--to` se sugiere uno y se pide por consola.

---

## 7. Migrar producción con seguridad (terminate robusto)

Migrar producción usa el **mismo flujo** Blue/Green. La diferencia está en el `terminate` del
environment viejo, que se endureció para evitar el problema clásico de EB en que **la terminación se
atasca** (una instancia huérfana retiene el Security Group y el environment "vuelve a Green"):

1. **Antes de terminar**, si el autoescalado del environment estaba suspendido, lo **reanuda** para que
   CloudFormation pueda matar la instancia y no la deje huérfana.
2. **Espera a que quede `Terminated` de verdad** (no solo `Terminating`).
3. Si se atasca, **detecta las instancias huérfanas** (incluso `stopped`, p. ej. de un `power off`
   previo con el ASG ya borrado) y, si el perfil tiene permisos EC2, **las termina automáticamente**,
   espera a que liberen el Security Group y **reintenta** el desmontaje — sin intervención manual.
4. Si el perfil **no** tiene `ec2:TerminateInstances` (caso de `eb-manager`), no puede limpiarlas:
   imprime el comando exacto para que lo hagas con un perfil admin y reintenta:
   ```bash
   aws ec2 terminate-instances --instance-ids i-xxxx --profile default --region us-east-2
   ```

> **Tip:** para que la auto-limpieza funcione sola, ejecuta el `terminate` con un perfil que tenga EC2
> (`--profile default`). Con `eb-manager` el terminate funciona, pero si se atasca tendrás que limpiar
> la huérfana a mano (paso 4).
>
> Recomendación para producción: migra primero dev, y al terminar el environment viejo de producción
> deja la réplica AL2023 validada y el CodePipeline ya reapuntado.

---

## 7.b Reapuntar el CodePipeline al nuevo environment (`repoint-pipeline`)

Cada environment con CI/CD se referencia en su pipeline **por nombre** (clave `EnvironmentName`
de la acción Deploy de Elastic Beanstalk). Tras el `swap` los nombres quedan cruzados y el viejo
se va a terminar, así que el próximo push **fallaría en la etapa Deploy** si el pipeline sigue
apuntando al environment viejo. Este comando lo arregla:

```bash
java -jar target/eb-migrator-1.0.0.jar \
     repoint-pipeline --from Samyappcasos-env --to Samyappcasos-env-al2023 --profile default
```

Qué hace por dentro:

1. **Escanea todos los pipelines** de la región y localiza las acciones Deploy de EB que apuntan
   al environment `--from` (no necesitas saber el nombre del pipeline).
2. Las **lista y pide confirmación**.
3. **Hace backup** del pipeline a un archivo `<pipeline>.backup-<timestamp>.json` en el directorio actual.
4. **Cambio quirúrgico:** reemplaza solo `EnvironmentName` por el valor `--to`. Todo lo demás
   (Source/GitHub, Build/CodeBuild, roles, token OAuth) se conserva intacto.

> **Permisos:** requiere `codepipeline:ListPipelines/GetPipeline/UpdatePipeline`. El perfil
> `eb-manager` (solo Elastic Beanstalk) **no** los tiene → usa `--profile default` (admin). Si falta
> el permiso, el comando lo detecta y te dice exactamente eso.
>
> **Reversible:** el reapuntado es simétrico. Para deshacerlo, invierte los environments:
> `repoint-pipeline --from Samyappcasos-env-al2023 --to Samyappcasos-env`.
>
> Hazlo **antes** del `terminate` del viejo, y verifica con un push o una ejecución manual del pipeline.

---

## 8. Estructura del proyecto

```
eb-migrator-java/
├── pom.xml                       # Java 11 + AWS SDK v2 (elasticbeanstalk, s3, codepipeline, autoscaling, ec2) + shade
└── src/main/java/com/samy/ebmigrator/
    ├── Main.java                 # Punto de entrada: dispatch flags vs. consola interactiva
    ├── aws/
    │   ├── AwsClients.java       # Fábrica de clientes SDK (perfil local, sin credenciales en código)
    │   ├── Platforms.java        # Clasifica solution stacks (AL2 deprecado / rama retirada / OK)
    │   ├── MigrateService.java   # Lógica Blue/Green (los pasos del procedimiento)
    │   ├── PipelineService.java  # CodePipeline: localiza y reapunta la etapa Deploy al nuevo env
    │   ├── PowerService.java     # ASG + EC2: suspender/reanudar autoescalado, apagar/encender, huérfanas
    │   ├── ResizeService.java    # EB: lee/aplica el tipo de instancia (update-environment) y espera Ready
    │   └── InstanceTypes.java    # Mapeo y validación t2 → t3/t3a (tamaños, RAM, drop-in/upsize)
    └── cli/
        ├── Cli.java              # parseo de flags + prompts/confirmaciones por consola
        ├── InteractiveConsole.java  # Menú interactivo (modo sin flags)
        ├── AuditCommand.java     # audit (read-only)
        ├── MigrateCommand.java   # create-replica
        ├── SwapCommand.java      # swap
        ├── RepointPipelineCommand.java # repoint-pipeline (reapunta la etapa Deploy)
        ├── TerminateCommand.java # terminate (robusto: reanuda ASG, espera Terminated, reporta huérfanas)
        ├── PowerCommand.java     # power (off/on/manage)
        └── ResizeCommand.java    # resize (cambio de tipo de instancia t2 → t3/t3a)
```

---

## 9. Notas y solución de problemas

- **Acentos como `�` en la consola de Windows:** es el *code page* del terminal, no el programa.
  Usa **Windows Terminal** o ejecuta `chcp 65001` antes del comando para activar UTF-8.

- **Warnings `SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder"`:** inofensivos.
  El AWS SDK usa SLF4J y no hay binding de logging. Para silenciarlos, añadir `slf4j-nop` como
  dependencia en el `pom.xml`.

- **`Unable to load credentials` / perfil no encontrado:** verifica que el perfil exista:
  ```bash
  aws sts get-caller-identity --profile eb-manager
  ```
  o pásale otro con `--profile`.

- **`create-replica` falla buscando el artefacto:** el environment origen debe tener una versión
  desplegada en el bucket gestionado de EB. Confírmalo con la consola o con `describe-environments`.

- **Tras el swap, el auto-deploy (CodePipeline) falla:** cada environment con CI/CD lo referencia
  por **nombre** en la etapa Deploy. Tras el swap, reapunta el pipeline al nuevo nombre con
  `repoint-pipeline --from <viejo> --to <nuevo>` (ver §7.b). Requiere permisos de CodePipeline,
  que `eb-manager` no tiene → usa `--profile default`.

- **`terminate` se queda en `Terminating` y el environment vuelve a `Green`:** una instancia huérfana
  (a veces `stopped`) retiene el Security Group. Si ejecutas el comando con un perfil con permisos EC2
  (`--profile default`), **lo resuelve solo**: termina la huérfana y reintenta. Con `eb-manager` no puede
  (sin EC2): elimina la(s) instancia(s) con `aws ec2 terminate-instances --instance-ids ... --profile default`
  y reintenta `terminate`.

- **`power off/on` falla con `UnauthorizedOperation`:** el perfil no tiene `ec2:Stop/StartInstances`.
  Usa `--profile default` (admin). El paso de suspender el ASG sí se aplicó.

---

## 10. Contexto

El histórico completo de decisiones y la bitácora de la migración están en
[`../CONTEXTO-MIGRACION-EB.md`](../CONTEXTO-MIGRACION-EB.md).
