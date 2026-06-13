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
  1) audit           Inventario de environments (solo lectura)
  2) create-replica  Crear réplica AL2023 (no toca producción)
  3) swap            Intercambiar URLs (afecta producción)
  4) terminate       Dar de baja un environment (destructivo)
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
| `terminate --env NOMBRE` | Da de baja un environment. **Destructivo**, exige teclear el nombre exacto. Espera a `Terminated` real y detecta huérfanas. | Destructivo |
| `power --env NOMBRE --state off\|on\|manage` | Controla el autoescalado y el encendido (ver §6). | Apaga/enciende |

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
#    → REAPUNTAR el CodePipeline (etapa Deploy) al nuevo nombre de environment

# 4. Cuando todo esté confirmado, dar de baja el environment viejo (destructivo)
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

## 6. Apagar/encender environments y autoescalado (`power`)

Todo environment de EB —incluso *SingleInstance*— tiene un **Auto Scaling Group con Min=Max=Desired=1**
que "auto-sana": si apagas la instancia, **la vuelve a lanzar**. El comando `power` controla eso.

```bash
# Apagar (suspende el ASG para que no reponga, y hace stop de la instancia; conserva el EBS)
java -jar target/eb-migrator-1.0.0.jar power --env Samyappcasos-env-al2023 --state off --profile default

# Encender (start). El ASG queda suspendido → gestión manual
java -jar target/eb-migrator-1.0.0.jar power --env Samyappcasos-env-al2023 --state on  --profile default

# Devolver el auto-sanado a EB (reanuda el ASG) — estado normal
java -jar target/eb-migrator-1.0.0.jar power --env Samyappcasos-env-al2023 --state manage
```

| `--state` | Qué hace |
|---|---|
| `off` | Suspende los procesos del ASG **y** apaga (stop) las instancias. La app deja de responder. |
| `on` | Enciende (start) las instancias. El ASG **sigue suspendido** (control manual). |
| `manage` | Reanuda el ASG: EB vuelve a gestionar (auto-sanado). Úsalo para volver a la normalidad. |

**Permisos:** suspender/reanudar el ASG funciona con `eb-manager`. Pero **apagar/encender instancias
requiere `ec2:Stop/StartInstances`**, que `eb-manager` (mínimo privilegio) NO tiene → usa
`--profile default` (admin) para `off`/`on`. Si te falta el permiso, el comando lo dice y, en `off`,
el ASG ya queda suspendido (solo reintentas el stop con el perfil admin).

> **Producción:** normalmente NO apagas producción (el auto-sanado es deseable). Si alguna vez
> suspendes un environment y luego quieres terminarlo, **`terminate` reanuda el ASG automáticamente**
> antes de desmontarlo (ver §7), para no dejar instancias huérfanas que atasquen el borrado.

---

## 7. Migrar producción con seguridad (terminate robusto)

Migrar producción usa el **mismo flujo** Blue/Green. La diferencia está en el `terminate` del
environment viejo, que se endureció para evitar el problema clásico de EB en que **la terminación se
atasca** (una instancia huérfana retiene el Security Group y el environment "vuelve a Green"):

1. **Antes de terminar**, si el autoescalado del environment estaba suspendido, lo **reanuda** para que
   CloudFormation pueda matar la instancia y no la deje huérfana.
2. **Espera a que quede `Terminated` de verdad** (no solo `Terminating`).
3. Si se atasca, **detecta las instancias huérfanas** y te imprime el comando exacto para eliminarlas
   con un perfil admin y reintentar:
   ```bash
   aws ec2 terminate-instances --instance-ids i-xxxx --profile default --region us-east-2
   ```

> Recomendación para producción: migra primero dev, y al terminar el environment viejo de producción
> deja la réplica AL2023 validada y el CodePipeline ya reapuntado.

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
    │   └── PowerService.java     # ASG + EC2: suspender/reanudar autoescalado, apagar/encender, huérfanas
    └── cli/
        ├── Cli.java              # parseo de flags + prompts/confirmaciones por consola
        ├── InteractiveConsole.java  # Menú interactivo (modo sin flags)
        ├── AuditCommand.java     # audit (read-only)
        ├── MigrateCommand.java   # create-replica
        ├── SwapCommand.java      # swap
        ├── TerminateCommand.java # terminate (robusto: reanuda ASG, espera Terminated, reporta huérfanas)
        └── PowerCommand.java     # power (off/on/manage)
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
  por **nombre** en la etapa Deploy. Tras el swap, reapunta el pipeline al nuevo nombre
  (`<env>-al2023`). Esto **no** lo hace el utilitario; requiere permisos de CodePipeline.

- **`terminate` se queda en `Terminating` y el environment vuelve a `Green`:** una instancia huérfana
  retiene el Security Group. El comando ya lo detecta y reporta; elimina la(s) instancia(s) con
  `aws ec2 terminate-instances --instance-ids ... --profile default` y reintenta `terminate`.

- **`power off/on` falla con `UnauthorizedOperation`:** el perfil no tiene `ec2:Stop/StartInstances`.
  Usa `--profile default` (admin). El paso de suspender el ASG sí se aplicó.

---

## 10. Contexto

El histórico completo de decisiones y la bitácora de la migración están en
[`../CONTEXTO-MIGRACION-EB.md`](../CONTEXTO-MIGRACION-EB.md).
