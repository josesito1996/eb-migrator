# Contexto unificado: administración y migración de AWS Elastic Beanstalk

> Documento único de contexto del workspace. Fusiona la guía manual por consola, la bitácora de
> decisiones/acciones y el diagnóstico de latencia + migración de instancias.
> **Última actualización:** 2026-06-13 · **Cuenta AWS:** `949078273502` · **Región:** `us-east-2`
>
> El utilitario CLI que automatiza este procedimiento se documenta aparte en
> [`eb-migrator-java/README.md`](eb-migrator-java/README.md).

## Índice

1. [Resumen ejecutivo y objetivos](#1-resumen-ejecutivo-y-objetivos)
2. [Seguridad y usuarios IAM](#2-seguridad-y-usuarios-iam)
3. [Inventario de environments e instancias](#3-inventario-de-environments-e-instancias)
4. [Estrategia Blue/Green (AL2 → AL2023)](#4-estrategia-bluegreen-al2--al2023)
5. [Guía manual por consola AWS](#5-guía-manual-por-consola-aws)
6. [Migración de tipo de instancia (t2 → t3/t3a)](#6-migración-de-tipo-de-instancia-t2--t3t3a)
7. [Apagado de entornos dev y problema del terminate atascado](#7-apagado-de-entornos-dev-y-problema-del-terminate-atascado)
8. [Bitácora de cambios ejecutados](#8-bitácora-de-cambios-ejecutados)
9. [Próximos pasos](#9-próximos-pasos)
10. [Apéndice: comandos útiles](#10-apéndice-comandos-útiles)

---

## 1. Resumen ejecutivo y objetivos

Dos líneas de trabajo sobre los environments de Elastic Beanstalk de **LegalByte** (us-east-2):

- **Migración de plataforma (SO):** actualizar environments en plataformas **deprecadas
  (Amazon Linux 2)** a **Amazon Linux 2023**, mantediendo la versión de Java y la URL de
  producción. Se hace **Blue/Green con intercambio de CNAMEs** (sin tocar DNS). Procedimiento
  validado a mano con el piloto `samy-app-usuarios` y luego automatizado en el utilitario
  `eb-migrator-java`.
- **Migración de tipo de instancia (rendimiento/costo):** varios entornos de producción corren en
  `t2.micro` (1 GB RAM), instancia única, **al 92% de memoria** → presión de GC en la JVM
  (Spring Boot / Java 11) → picos de latencia. Plan: migrar la familia `t2` (burstable, deprecada)
  a **`t3`/`t3a`** (Nitro, más barato), subiendo a 2 GB (`t3a.small`) donde hay presión de memoria.

Ambas líneas alimentan el utilitario CLI `eb-migrator-java` (auditar, replicar, swap, reapuntar
pipeline, terminar, encender/apagar).

---

## 2. Seguridad y usuarios IAM

### Principios aplicados
- **Nunca** se pegan credenciales en el chat. El AWS CLI usa las credenciales locales de la máquina
  (`~/.aws/credentials`).
- Los secretos (secret access keys) **no se imprimen** en pantalla ni en la conversación; se escriben
  directamente a los archivos de perfil del CLI.
- **Mínimo privilegio:** cada usuario solo recibe los permisos que necesita.

### Usuarios IAM
| Usuario | Permisos | Uso |
|---|---|---|
| `admin-user` | `AdministratorAccess` | Administración general (preexistente). Necesario para CodePipeline y EC2. |
| `eb-manager` | `AdministratorAccess-AWSElasticBeanstalk` | Solo Elastic Beanstalk. CLI/API (sin consola web). |

**Detalle de `eb-manager` (Fase 1 — COMPLETADA):**
- **ARN:** `arn:aws:iam::949078273502:user/eb-manager`
- **Política:** `arn:aws:iam::aws:policy/AdministratorAccess-AWSElasticBeanstalk` (acotada a EB)
- **Access Key ID:** `AKIA5Z6LZFXPIQKOGKGU` (el secreto no se expuso; está en `~/.aws/credentials` bajo `[eb-manager]`)
- **Perfil CLI:** `eb-manager` · región `us-east-2` · salida `json` → usar `--profile eb-manager`
- **Verificado:** ✅ administra EB; ✅ NO puede tocar IAM ni otros servicios (`iam:ListUsers` → `AccessDenied`). No escala privilegios.

> **Mapa de perfiles por tarea:**
> - **EB** (audit, create-replica, swap, terminate, escalado del ASG) → `eb-manager` basta.
> - **EC2** (stop/start/reboot/terminate de instancias, limpiar huérfanas) → `--profile default` (admin); `eb-manager` no tiene EC2.
> - **CodePipeline** (reapuntar Deploy) → `--profile default` (admin); `eb-manager` no tiene CodePipeline.

**Recomendaciones de seguridad pendientes:**
- Activar **MFA** en `admin-user`.
- Guardar el secreto de `eb-manager` en un gestor de contraseñas.

---

## 3. Inventario de environments e instancias

### 3.1 Estado de plataforma (al iniciar — todos en AL2 deprecado)

| Aplicación | Environment | Plataforma | Health |
|---|---|---|---|
| app-intercorp-service-prod | Appintercorpserviceprod-env | Corretto 11 AL2 3.4.5 | Green |
| node-app-landing | Nodeapplanding-env | Node.js 14 AL2 5.6.0 | Grey |
| app-intercorp-service-dev | Appintercorpservicedev-env | Corretto 11 AL2 3.4.5 | Grey |
| node-app-landing | Nodeapplanding-env-prod | Node.js 14 AL2 5.5.2 | Grey |
| app-jurisprudencia-service | Appjurisprudenciaservice-env-prod | Corretto 11 AL2 3.2.14 | Grey |
| samy-office-files | Samyofficefiles-env-prod | Corretto 11 AL2 3.2.14 | Grey |
| samy-app-primary | Samyappprimary-env-prod | Corretto 11 AL2 3.2.14 | Yellow |
| samy-app-usuarios | Samyappusuarios-env-prod | Corretto 11 AL2 3.2.14 | Green |
| samy-app-casos | Samyappcasos-env-prod | Corretto 11 AL2 3.2.14 | Yellow |
| samy-config-server | Samyconfigserver-env | Corretto 11 AL2 3.3.1 | Green |
| samy-office-files | Samyofficefiles-env | Corretto 11 AL2 3.2.10 | Grey |
| app-jurisprudencia-service | Appjurisprudenciaservice-env | Corretto 11 AL2 3.3.1 | Grey |
| samy-app-usuarios | **Samyappusuarios-env** (piloto) | Corretto 11 AL2 3.2.2 | Green |
| samy-app-casos | Samyappcasos-env | Corretto 11 AL2 3.6.2 | Green |
| samy-app-primary | Samyappprimary-env | Corretto 11 AL2 3.2.2 | Green |

**Plataformas críticas:** `Node.js 14 on AL2` (rama retirada) y todas las `Corretto 11 on AL2` (AL2 en retiro).

### 3.2 Estado de instancias EC2 (2026-06-13)

Hay migración en curso: existen gemelos `-al2023` (Amazon Linux 2023) para casos/usuarios/configserver,
pero parte del prod legacy en `t2.micro` sigue **running** sirviendo tráfico. Prioridad = los **running en t2.micro**.

**Running — candidatos activos a migrar (t2.micro):**

| Entorno | Instancia | Tipo | Acción |
|---|---|---|---|
| `Samyappprimary-env-prod` (read-model) | `i-0f2cfb639aa18e11e` | t2.micro | **P1** → t3.small. Degradado (92% RAM); cuello de botella de latencia. |
| `Samyappcasos-env-prod` (API casos) | `i-09f30a66c3153dabc` | t2.micro | **P1** → t3.small. Degradado (92% RAM). Existe gemelo `-prod-al2023` (t3.micro) → cutover pendiente. |
| `Appintercorpserviceprod-env` | `i-03f010b2a2c5de63b` | t2.micro | P2 → t3.micro/small según uso. |

**Running — ya en t3 (vigilar memoria; t3.micro = 1 GB):**

| Entorno | Instancia | Tipo |
|---|---|---|
| `Samyappcasos-env-prod-al2023` | `i-0160786ff5d395a5b` | t3.micro |
| `Samyappusuarios-env-prod-al2023` | `i-0ce6d433eea5c4271` | t3.micro |
| `Samyconfigserver-env-al2023` | `i-0dbc1e14e0ef6f000` | t3.micro |

**Stopped — t2.micro (migrar al re-encender, o limpiar si son legacy muertos):**

| Entorno | Instancia | Tipo |
|---|---|---|
| `Samyappprimary-env-al2023` | `i-08f88a55aba6db81a` | t2.micro (gemelo del read-model, aún NO es t3) |
| `Samyappusuarios-env-al2023` | `i-0752c05933627eb21` | t2.micro |
| `Appjurisprudenciaservice-env-prod` | `i-0bf0f3ac512026d13` | t2.micro |
| `Appjurisprudenciaservice-env` | `i-025a3312812c8ec41` | t2.micro |
| `Samyofficefiles-env-prod` | `i-047bacc966d426c47` | t2.micro |
| `Samyofficefiles-env` | `i-0c69a7fbda53f0ac7` | t2.micro |
| `Nodeapplanding-env-prod` | `i-0415603f73c4a328c` | t2.micro |
| `Nodeapplanding-env` | `i-067fe0464f583a7ce` | t2.micro |

**Stopped — ya en t3 (sin acción):** `Appintercorpservicedev-env-al2023` (`i-0c84694d344daaeed`, t3.micro).

> `Samyappusuarios-env-prod` está **Terminated** (reemplazado por su gemelo `-al2023`): migración ya completada.

**Resumen:** 11 instancias en `t2.micro` (2 prod running críticas, 1 prod running secundaria, 8 stopped);
5 ya en `t3.micro` (3 running, 1 stopped, + dev).

### 3.3 Topología relevante

- **Config Server**: `Samyconfigserver-env` (Spring Cloud Config) en
  `http://samyconfigserver-env.eba-p4hpjge8.us-east-2.elasticbeanstalk.com`. Sirve las URLs entre
  servicios bajo el prefijo `external-url.*`:
  - `external-url.primary-url` → read-model (`samyappprimary-env...`)
  - `external-url.users-url`, `external-url.office-files-url`, `external-url.jpa-url`.
- Los entornos EB son **internos a la VPC**: no aceptan TCP desde fuera (curl externo a su `:80` cuelga
  hasta timeout — security group correcto). Para medir latencia usar **CloudWatch / enhanced health**, no curl externo.
- Los entornos prod observados son de **instancia única (sin load balancer)** y **no publican
  enhanced-health metrics a CloudWatch** (`AWS/ElasticBeanstalk/ApplicationLatency*` sale null). El % de
  memoria viene del agente de enhanced health, no de CWAgent (`CWAgent/mem_used_percent` no disponible).

---

## 4. Estrategia Blue/Green (AL2 → AL2023)

**No se actualiza "en sitio".** AL2 → AL2023 son ramas distintas del SO; hay que crear un environment
nuevo y luego intercambiar la URL.

```
1. Crear environment NUEVO (temporal) en AL2023: misma config + mismo artefacto, SingleInstance.
2. Probarlo en su URL temporal (sin afectar el environment actual).
3. Swap de CNAMEs: el nuevo toma la URL de producción → la URL queda IGUAL.
4. Reapuntar el CodePipeline (la etapa Deploy referencia el env por NOMBRE, que sí cambia).
5. El environment viejo queda como respaldo (rollback instantáneo) y se termina al final.
```

### Decisiones confirmadas por el usuario (piloto y repetibles)
1. **No hay dominio propio** → el swap de URL preserva todo sin tocar DNS.
2. **Mantener Java 11** (Corretto 11 sobre AL2023) — solo cambia el SO.
3. **Reutilizar el artefacto ya desplegado** (aísla el cambio de SO de cualquier cambio de código).
4. **Mantener SingleInstance** — NO implementar balanceador de carga.
5. **Preguntar antes de ejecutar cualquier cambio.**

### Avisos clave (aprendidos)
- **El nombre del environment cambia** (EB no permite renombrar); la **URL sí se conserva**.
- **El CI/CD (CodePipeline) apunta al environment por NOMBRE** → hay que **reapuntar la etapa Deploy**
  tras el swap, o el auto-deploy desde GitHub falla. Hay pipelines `-dev` y `-prod` por servicio.
- **Riesgo del artefacto:** las versiones de la app suelen apuntar al bucket de CodePipeline (temporal,
  inaccesible al crear un environment nuevo) → usar el **artefacto realmente desplegado** en el bucket de EB.
- **`JAVA_HOME` hardcodeado:** en AL2 era `/usr/lib/jvm/java-11-amazon-corretto.x86_64`; en AL2023 puede
  cambiar. Validar el arranque en la URL temporal ANTES del swap. (En el piloto `samy-app-usuarios` no hizo falta tocarlo.)
- **`ImageId`/AMI fijo de AL2** en la plantilla: hay que quitarlo para que AL2023 use su propio AMI.

### Plantilla de procedimiento (CLI, repetible)
1. `create-configuration-template` desde el env origen (revisar y quitar `ImageId` de AL2).
2. Localizar el artefacto desplegado en `elasticbeanstalk-<region>-<acct>/resources/environments/<env-id>/_runtime/_versions/...`.
3. `create-application-version` apuntando a ese artefacto (bucket de EB, **no** el de CodePipeline).
4. `create-environment` en la plataforma AL2023 destino, con `--options-to-remove ...ImageId`.
5. Validar en la URL temporal (HTTP + flujos reales).
6. `swap-environment-cnames` para preservar la URL.
7. `update-pipeline` / `repoint-pipeline` la etapa Deploy al nuevo nombre.
8. Validar y, cuando se confirme, `terminate-environment` del viejo.

### Datos del piloto `Samyappusuarios-env`
| Característica | Valor |
|---|---|
| Environment ID | `e-mstft3pcjk` |
| Tipo | SingleInstance (sin balanceador), `t2.micro` (x86_64) |
| Plataforma origen | `Corretto 11 / AL2 / 3.2.2` |
| Plataforma destino | `Corretto 11 / AL2023 / 4.12.2` (mantiene Java 11) |
| CNAME | `Samyappusuarios-env.eba-aja3zm2w.us-east-2.elasticbeanstalk.com` |
| Instance profile | `aws-elasticbeanstalk-ec2-role` |
| Service role | `arn:aws:iam::949078273502:role/aws-elasticbeanstalk-service-role` |
| Variables de entorno | `GRADLE_HOME`, `JAVA_HOME`, `M2`, `M2_HOME`, `SERVER_PORT` |
| Código fuente | Spring Boot, Java 11 |

---

## 5. Guía manual por consola AWS

> Procedimiento **Blue/Green** que **preserva la misma URL** y mantiene la versión de Java.
> Pensado para replicarlo a mano en cada environment. Probado con `samy-app-usuarios`.
> El utilitario `eb-migrator-java` automatiza estos mismos pasos con guardarraíles.

### Paso 0 · Anota los datos del environment actual
En **Elastic Beanstalk** → tu app → environment → **Configuration**, anota: plataforma actual; tipo
(*Single instance* / *Load balanced* en *Capacity*); tipo de instancia; **Instance profile** y
**Service role** (*Security*); **variables de entorno** (ojo `JAVA_HOME`); la **URL (CNAME)**.

### Paso 1 · Guarda la configuración como plantilla
Selecciona el environment → **Actions** → **Save configuration** → nombre (ej. `usuarios-al2023-cfg`) → **Save**.
Guarda toda la config (capacidad, roles, variables) para reutilizarla.

### Paso 2 · Consigue el artefacto desplegado (.jar/.zip)
- **Opción A (recomendada) — reutilizar el binario que ya corre:** **S3** → bucket
  `elasticbeanstalk-us-east-2-949078273502` → `resources/environments/<ENV-ID>/_runtime/_versions/<nombre-app>/`
  (el `<ENV-ID>` es el `e-xxxxxxxx`). Descarga ese objeto (~decenas de MB) y renómbralo a `app.zip`/`app.jar`.
- **Opción B — build nuevo:** genera el artefacto desde el código fuente (el mismo que produce tu pipeline).

### Paso 3 · Crea el environment NUEVO en AL2023
1. App → **Create new environment** → **Web server environment**.
2. **Environment name:** temporal claro (ej. `Samyappusuarios-env-al2023`); CNAME automático (lo arregla el swap).
3. **Platform:** *Corretto 11* · branch *Corretto 11 running on 64bit Amazon Linux 2023* · versión más reciente (ej. `4.12.2`).
4. **Application code:** *Upload your code* → sube el artefacto del Paso 2 (evita el error de permisos S3 del bucket de CodePipeline).
5. **Configure more options:** **Single instance** (o lo que tenías). No actives balanceador si el original no lo tenía.
6. **Apply configuration** → elige tu plantilla `usuarios-al2023-cfg` (hereda roles, instancia y variables).
   - **IMPORTANTE:** si la plantilla trae un **AMI / Image ID** fijo (de AL2), quítalo / déjalo en blanco.
7. **Create environment** y espera a **Health: OK / Green**.

### Paso 4 · Prueba el environment nuevo en su URL temporal
Copia la **URL temporal** (`...-al2023.eba-xxxx.us-east-2.elasticbeanstalk.com`), prueba los **flujos
reales** (login, endpoints de negocio) y revisa *Logs* → *Request logs* si algo falla.

### Paso 5 · Verifica `JAVA_HOME` (si tu app lo usa)
Si la app falla al arrancar y usa `JAVA_HOME`: Configuration → Environment properties → corrige la ruta a
la de AL2023 **o** elimina la variable para que la plataforma use la suya → **Apply** y reprueba. (En el
piloto no hizo falta.)

### Paso 6 · Intercambia las URLs (Swap)
Lista de environments → selecciona el **nuevo** (`...-al2023`) → **Actions** → **Swap environment URLs** →
elige el **viejo** → **Swap**. Espera ~1-2 min (DNS). La **URL original** ahora la sirve el environment
AL2023; el viejo queda como **rollback** (otro swap revierte).

### Paso 7 · ⚠️ Actualiza el CodePipeline (auto-deploy de GitHub)
**CodePipeline** → pipeline del servicio (ej. `pipeline-samy-app-usuarios-dev`) → **Edit** → etapa
**Deploy** → editar la acción de EB → cambia **Environment name** al nuevo (`Samyappusuarios-env-al2023`).
La *Application name* no cambia. **Done** → **Save**. (Si hay pipelines `-dev` y `-prod`, actualiza cada uno.)

### Paso 8 · Da de baja el environment viejo (cuando estés seguro)
Déjalo encendido unas horas/días como rollback; cuando confirmes, **Actions** → **Terminate environment**.

> ⚠️ **Si el `Terminate` se queda en `Terminating` y vuelve a `Green`:** casi siempre es una **instancia
> EC2 huérfana** que retiene el Security Group del stack (CloudFormation hace rollback). Revisa los
> *eventos*: si ves *"...AWSEBSecurityGroup ... has a dependent object"*, ve a **EC2 → Instances**, filtra
> por la etiqueta `elasticbeanstalk:environment-id`, **termina la instancia a mano** y repite el Terminate.
> El utilitario `eb-migrator` ya lo hace solo.
>
> ⚠️ **No suspendas el Auto Scaling antes de terminar:** un ASG suspendido no mata su instancia → genera
> la huérfana. Si lo suspendiste, **reanúdalo** antes de terminar.

### Checklist rápido por environment
- [ ] Paso 0: datos anotados · [ ] Paso 1: config guardada · [ ] Paso 2: artefacto obtenido
- [ ] Paso 3: env AL2023 creado (Green) · [ ] Paso 4: probado en URL temporal · [ ] Paso 5: `JAVA_HOME` verificado
- [ ] Paso 6: **Swap URLs** · [ ] Paso 7: **CodePipeline reapuntado** · [ ] Paso 8: env viejo terminado

> **Costos:** durante la migración hay **2 instancias activas** (vieja + nueva); al terminar el viejo vuelve a una.

---

## 6. Migración de tipo de instancia (t2 → t3/t3a)

> Origen: diagnóstico de latencia del endpoint `GET /api-caso/findById/{id}`.

**Problema:** varios entornos prod corren en `t2.micro` (1 GB), instancia única, **al 92% de memoria** →
presión de GC en la JVM → picos de latencia. `t2.micro` es **burstable**: bajo carga agota créditos de
CPU y cae al baseline (~10% de 1 vCPU).

**Objetivo:** `t2.micro` → `t3.small` (2 GB) mínimo, ideal `t3.medium` (4 GB) para el read-model. `t3` es
Nitro, **más barato** que `t2` y soporta modo `unlimited` (evita throttle de créditos). Acompañar con
ajuste de heap JVM (`-Xmx`) acorde a la nueva RAM.

### Cómo cambiar el tipo de instancia en EB
Se controla con `aws:ec2:instances` (`InstanceTypes`). Cambiarlo provoca **reemplazo de instancia / breve
indisponibilidad** en single-instance.

```bash
aws elasticbeanstalk update-environment --environment-name <ENV> --region us-east-2 \
  --option-settings Namespace=aws:ec2:instances,OptionName=InstanceTypes,Value=t3.small
```

Consideraciones para el utilitario: detectar familia `t2` y mapear a `t3`/`t3a`; en single-instance prod
avisar de la indisponibilidad; migrar también a AL2023 donde aplique; reajustar `-Xmx` tras el cambio de
RAM; t3 requiere **HVM + ENA** (las plataformas EB modernas/al2023 ya cumplen).

### Mapeo t2 → t3 / t3a
`t3a` = mismas vCPU/RAM que `t3` pero CPU **AMD EPYC**, **~10% más barato** on-demand. Mismo
comportamiento burstable y `unlimited` por defecto. Single-thread algo menor (irrelevante para apps
I/O-bound); disponibilidad no garantizada en todas las AZ (us-east-2a/b/c lo soportan). Seguro para JVM/Java 11.

| t2 (origen) | t3 (Intel) | t3a (AMD, ~10% menos) | vCPU | RAM | t3 recomendado si hay presión de memoria |
|---|---|---|---|---|---|
| t2.nano | t3.nano | t3a.nano | 2 | 0.5 GB | t3.micro |
| **t2.micro** | t3.micro | t3a.micro | 2 | **1 GB** | **t3.small (2 GB)** ← caso de este proyecto |
| t2.small | t3.small | t3a.small | 2 | 2 GB | t3.medium |
| t2.medium | t3.medium | t3a.medium | 2 | 4 GB | t3.large |
| t2.large | t3.large | t3a.large | 2 | 8 GB | — |
| t2.xlarge | t3.xlarge | t3a.xlarge | 4 | 16 GB | — |
| t2.2xlarge | t3.2xlarge | t3a.2xlarge | 8 | 32 GB | — |

> ⚠️ **Drop-in NO basta para apps con alta memoria.** `t2.micro → t3.micro` mantiene 1 GB; las apps Spring
> Boot están al 92% en 1 GB, así que un t3.micro seguiría apretado. Para read-model y casos: subir a
> **t3.small (2 GB)** como mínimo.

### Tamaño recomendado por entorno

| Entorno | Rol | Actual | Recomendado | Por qué |
|---|---|---|---|---|
| `Samyappprimary-env-prod` | read-model (JVM, lo llama findById 4x) | t2.micro | **t3a.small (2 GB)** | P1. 92% RAM, GC thrash; cuello de botella de latencia. Subir RAM + ajustar `-Xmx`. |
| `Samyappcasos-env-prod` | API casos (JVM) | t2.micro | **t3a.small (2 GB)** | P1. 92% RAM. Alinear con su gemelo `-al2023` (hoy t3.micro=1GB → también a small). |
| `Samyappcasos-env-prod-al2023` | API casos (gemelo migrado) | t3.micro | **t3a.small (2 GB)** | t3 pero solo 1 GB; si recibe prod, misma presión. |
| `Samyappusuarios-env-prod-al2023` | usuarios (JVM) | t3.micro | t3a.small si JVM, o micro si bajo uso | Vigilar memoria; subir si Yellow por RAM. |
| `Appintercorpserviceprod-env` | intercorp | t2.micro | **t3a.micro** (drop-in) o small según uso | P2. Migrar familia; tamaño según consumo real. |
| `Samyconfigserver-env-al2023` | config server (bajo tráfico) | t3.micro | t3a.micro (sin cambio de tamaño) | Carga baja; solo Intel→AMD por costo. |
| `Samyappprimary-env-al2023` | gemelo read-model (stopped) | t2.micro | **t3a.small (2 GB)** | Si se reactiva como destino, debe nacer en small. |
| `Samyappusuarios-env-al2023` | usuarios (stopped) | t2.micro | t3a.micro/small | Migrar familia al reactivar. |
| `Appjurisprudenciaservice-env-prod` | jurisprudencia (stopped) | t2.micro | t3a.micro | Drop-in; revisar si JVM necesita small. |
| `Appjurisprudenciaservice-env` | jurisprudencia dev (stopped) | t2.micro | t3a.micro | Dev. |
| `Samyofficefiles-env-prod` | office files (stopped) | t2.micro | t3a.micro/small | Según peso de archivos en memoria. |
| `Samyofficefiles-env` | office files dev (stopped) | t2.micro | t3a.micro | Dev. |
| `Nodeapplanding-env-prod` | landing (Node) | t2.micro | t3a.micro (drop-in) | Node, sin presión JVM. |
| `Nodeapplanding-env` | landing dev (Node) | t2.micro | t3a.micro | Dev. |
| `Appintercorpservicedev-env-al2023` | intercorp dev (stopped) | t3.micro | sin cambio | Ya t3, dev. |

> **Regla resumida:** JVM/Spring Boot que recibe prod → mínimo **2 GB** (`t3a.small`); `t3a.micro` solo en
> dev/bajo tráfico. Node/estáticos y bajo tráfico (configserver) → drop-in `t3a.micro`. Preferir `t3a` sobre
> `t3` por costo; fallback a `t3` si la AZ no tiene `t3a`.

### Falsos rastros descartados (NO causan la latencia)
- Alarmas CloudWatch `casos-ReadCapacityUnitsLimit-BasicAlarm` e `inspectores-ReadCapacityUnitsLimit-BasicAlarm`:
  vigilan **consumo** (no throttling), ambas OK. `ReadThrottleEvents = 0` en 3 días; pico de `inspectores`
  = 28/min (12% del umbral). Tabla `casos` ya en On-Demand; `inspectores` en provisioned 5 RCU sin throttling
  real. Recomendación aparte: borrar/retunear esas alarmas heredadas (ruido).

### Cambio ya aplicado en el repo `caso-rest-aws`
- `ExternalDbAws.java`: default de `caso.cache.referencia.ttl-segundos` subido de **300 → 1800s** (cachea
  inspectores/materias del read-model, reduce llamadas). Ojo: el Config Server puede sobrescribir ese valor.

---

## 7. Apagado de entornos dev y problema del terminate atascado

### Apagar entornos de DEV para ahorrar (sin que se reactiven solos)
Aunque sea *Single instance*, EB crea un **ASG con Min=Max=Desired=1** que "auto-sana": si apagas la
instancia, la repone. Para apagar a voluntad conservando el disco:

1. **Suspende el autoescalado:** EC2 → Auto Scaling Groups → el ASG del env (`awseb-<env-id>-...`) →
   *Suspend processes*. CLI: `aws autoscaling suspend-processes --auto-scaling-group-name <ASG> --profile eb-manager`.
2. **Apaga la instancia (stop):** EC2 → Instances → *Stop instance*. CLI:
   `aws ec2 stop-instances --instance-ids <id> --profile default` (requiere EC2; `eb-manager` no lo tiene).
3. **Encender:** *Start instance* / `aws ec2 start-instances ... --profile default`.
4. **Volver a la gestión normal de EB:** *Resume processes* / `aws autoscaling resume-processes ...`.

> Apagada, EB muestra **Grey/Severe** (cosmético). Sigues pagando el **EBS** (barato); ahorras el cómputo.
> **No apliques esto a producción.** El utilitario lo automatiza con `power --env X --state stop|start|scaling-on`.

### El terminate atascado (instancia huérfana retiene el Security Group)
Patrón recurrente: al terminar el env viejo, el borrado del Security Group falla (*"has a dependent
object"*) → CloudFormation hace **rollback** → el env vuelve a `Ready/Green`. Causa: una **instancia EC2
huérfana** (a veces `stopped`, desvinculada de su ASG ya borrado) sigue viva reteniendo el SG.

**Solución:** terminar la instancia huérfana a mano (`aws ec2 terminate-instances ... --profile default`) y
reintentar el `terminate-environment`. `eb-manager` no puede terminar EC2.

> **Lección (refuerzo):** NO apagar la instancia con `power off`/suspender el ASG **antes** de `terminate`.
> Un ASG suspendido o una instancia `stopped` deja huérfanas que atascan el borrado. Por eso el utilitario
> **reanuda el ASG antes de terminar**, espera a `Terminated` real y **auto-limpia** las huérfanas (con perfil EC2).

---

## 8. Bitácora de cambios ejecutados

> Registro cronológico de cada acción que **modifica** AWS (los comandos de solo lectura no se listan).

### 2026-06-12 · Fase 1 — Usuario IAM
- **Creado** usuario `eb-manager` (`aws iam create-user`) con tags `purpose` y `created-by`.
- **Adjuntada** política `AdministratorAccess-AWSElasticBeanstalk` (`aws iam attach-user-policy`).
- **Creada** access key y configurado perfil CLI `eb-manager` (secreto no expuesto).

### 2026-06-12 · Fase 2 — Migración piloto `Samyappusuarios-env` (Blue/Green)
- **Paso 1 · Plantilla creada** — `create-configuration-template` `samyappusuarios-al2023` desde
  `e-mstft3pcjk`. Arrastró `ImageId = ami-0cee3d2194bb12cc9` (AL2) → se elimina en Paso 2 con
  `--options-to-remove`. Verificado: `EnvironmentType=SingleInstance`, `t2.micro`, `IamInstanceProfile=aws-elasticbeanstalk-ec2-role`. ✅
- **Paso 2 (1er intento) · FALLÓ** — `create-environment` (env `e-pyrzmcruua`). Error S3:
  *"You don't have permission to copy an Amazon S3 object"* desde el bucket de **CodePipeline**
  (artefacto temporal, el rol de servicio de EB no puede leerlo). El env quedó `Terminated`; producción intacta.
- **Diagnóstico:** el artefacto realmente desplegado SÍ existe en el bucket de EB:
  `s3://elasticbeanstalk-us-east-2-949078273502/resources/environments/e-mstft3pcjk/_runtime/_versions/samy-app-usuarios/...` (~44 MB).
- **Paso 2a · Nueva versión registrada** — `create-application-version` `samyappusuarios-al2023-src`
  apuntando al artefacto del **bucket de EB** (mismo binario que producción).
- **Paso 2b (2º intento) · Entorno nuevo creado** — `Samyappusuarios-env-al2023` · ID `e-ed3jsqgg2t` ·
  `Corretto 11 / AL2023 / 4.12.2`, SingleInstance, sin balanceador, sin `ImageId`. **`Ready/Green` en ~2 min.**
  CNAME temporal `...-al2023.eba-aja3zm2w...` · EIP `3.14.154.4` · instancia `i-0e2d0f038be80e786`.
- **Paso 3 · Validación (solo lectura) — OK.** El problema de `JAVA_HOME` NO ocurrió (Spring Boot arrancó).
  HTTP nuevo vs. producción en `/`, `/actuator/health`, `/login`, `/api`: comportamiento idéntico (ambos 404).
- **Paso 4 · Swap de CNAMEs** — `swap-environment-cnames`. La URL real la sirve ahora el AL2023 (`e-ed3jsqgg2t`).
  Verificación post-swap: HTTP 404 idéntico. ✅ El env productivo pasó a llamarse `Samyappusuarios-env-al2023`
  (nombres no renombrables); la URL se mantuvo. Rollback: repetir el swap.
- **Paso 5 · Terminación del AL2 viejo (corregido 2026-06-12)** — ⚠️ Una versión previa de la bitácora daba
  por terminado `e-mstft3pcjk`, pero al re-verificar seguía **`Ready`** (instancia viva, generando costo). El
  terminate original nunca surtió efecto. **Acción real:** `terminate-environment --environment-id e-mstft3pcjk`
  (por **ID**, no nombre, porque quedaron cruzados tras el swap), tras confirmar que producción seguía
  Green → `Terminating`. **MIGRACIÓN DE `samy-app-usuarios` COMPLETADA.** ✅
  - **Lección:** `terminate-environment` es **asíncrono** → verificar siempre el estado posterior con
    `describe-environments`. Operar por `--environment-id` cuando los nombres estén cruzados.

### 2026-06-12 · CI/CD del piloto reapuntado
- Pipeline `pipeline-samy-app-usuarios-dev` (Source: GitHub `josesito1996/cognito-user-service`, rama
  `feature-branch-samy`, webhook; Build: CodeBuild `compile-samy-app-usuarios-dev`).
- **✅ RESUELTO:** `aws codepipeline update-pipeline` — cambio quirúrgico solo de `EnvironmentName` →
  `Samyappusuarios-env-al2023`. Source y token OAuth preservados. Backup en `pipeline-usuarios-dev.backup.json`.
  Pendiente: confirmar con un push o ejecución manual.

### 2026-06-13 · Más migraciones dev + incidencias
- **Migrados a AL2023** (mismo Blue/Green) los **dev** `samy-app-casos` y `samy-app-primary`, además del
  piloto. Quedaron como `Samyappcasos-env-al2023` (`e-cfyzxh9dpt`), `Samyappprimary-env-al2023`
  (`e-jkb8fut8uk`) y `Samyappusuarios-env-al2023` (`e-ed3jsqgg2t`). Los `-prod` son **otros** y siguen en AL2.
- **⚠️ `terminate` atascado (`Samyappcasos-env` / `e-nqipmp2bsz`):** fallaba al borrar el SG (`sg-071...`:
  *"has a dependent object"*) → rollback → volvía a `Ready/Green`. Causa: instancia huérfana (`i-0f43...`)
  desvinculada de su ASG ya borrado. **Solución:** terminar la huérfana a mano (perfil admin) y reintentar → completó.
- **Apagado de los 3 entornos dev** para ahorrar: `suspend-processes` (`eb-manager`) + `stop-instances`
  (`default`/admin). ASG/instancias: casos `...mZGzhxPtxvqD`/`i-075d862d2ca87dd9e`, usuarios
  `...zzuyQQB6rmfc`/`i-0752c05933627eb21`, primary `...a16VGCfoR1Zs`/`i-08f88a55aba6db81a`.
- **⚠️ Incidente `terminate` (Appintercorpservicedev-env / `e-3nxp2xn8mg`):** se había **apagado con `power off`**
  antes de terminar → instancia `stopped` pero viva (`i-003ad80d99ab76ca7`) reteniendo el SG `sg-00973aefd77235dfb`
  → rollback → `Ready/Grey`. **Solución:** terminar la huérfana (perfil `default`) y reintentar → `Terminated`.
  CodePipeline de intercorp reapuntado (backup `pipeline-intercorp-dev.backup-20260613-125241.json`).
  > **Lección (refuerzo):** NO apagar la instancia con `power off` antes de `terminate`. Esto motivó la auto-limpieza del comando.

### 2026-06-13 · Fase 3 — Utilitario `eb-migrator-java`
- `terminate` **endurecido**: reanuda el ASG si estaba suspendido, **espera a `Terminated` real** y, si se
  atasca, **detecta, reporta y (con perfil EC2) auto-termina** las instancias huérfanas (incluidas `stopped`),
  espera a que liberen el SG y **reintenta**. Sin EC2 cae a reportar el comando manual.
- Nuevo comando **`power`** ampliado a control separado de **ESCALADO** (`scaling-off`/`scaling-on`, los
  permite `eb-manager`) e **INSTANCIA** (`stop`/`start`/`reboot`/`terminate-instance`, requieren EC2 →
  `--profile default`). Alias retrocompatibles: `off`=`stop`, `on`=`start`, `manage`=`scaling-on`, `restart`=`reboot`.
  Uso: **dev** (`*-al2023`) se apaga de noche con `stop`/`start`; **prod** (`*-env-prod`) se deja con `scaling-off` encendido.
- Nuevo comando **`repoint-pipeline --from VIEJO --to NUEVO`**: escanea TODOS los pipelines, localiza la
  acción Deploy de EB que apunta al env viejo, hace **backup** (`<pipeline>.backup-<ts>.json`) y cambia
  **solo** `EnvironmentName` (conserva Source/Build/roles/token). Requiere CodePipeline → `--profile default`.
  Reversible invirtiendo `--from/--to`. Añadido al menú interactivo (opción 4).
- Pasos `create-replica` hechos **idempotentes** (borra-y-recrea plantilla y versión si ya existían).
- **Modo consola interactivo** (menú) además del modo por flags.

---

## 9. Próximos pasos

- [x] Migración piloto `samy-app-usuarios` + dev de `casos` y `primary` a AL2023.
- [x] Procedimiento documentado como plantilla repetible (este archivo).
- [x] **Fase 3:** utilitario `eb-migrator-java` con `audit/create-replica/swap/repoint-pipeline/terminate/power`,
      modo consola interactivo, `terminate` robusto (auto-limpia huérfanas) y reapuntado de CodePipeline integrado.
- [ ] Confirmar el CodePipeline migrado (`pipeline-samy-app-usuarios-dev`) con un push o ejecución manual.
- [ ] Reapuntar los pipelines de `casos` y `primary` dev a sus nuevos environments `-al2023`
      (`repoint-pipeline --from … --to … --profile default`).
- [ ] **Migrar los environments `-prod`** (siguen en AL2): `create-replica` → validar → `swap` → reapuntar
      pipeline `-prod` → `terminate` (ya robusto contra el atasco del Security Group).
- [ ] **Migración de instancia t2 → t3a.small** de los prod críticos (`Samyappprimary-env-prod`,
      `Samyappcasos-env-prod`): subir RAM a 2 GB y ajustar `-Xmx`; avisar de la breve indisponibilidad.
- [ ] Migrar el resto del inventario (incl. `node-app-landing` en Node.js 14 — rama retirada).
- [ ] Limpiar/retunear las alarmas CloudWatch heredadas de `casos`/`inspectores` (ruido).
- [ ] (Opcional) Silenciar warnings SLF4J y forzar stdout UTF-8 en el utilitario.

---

## 10. Apéndice: comandos útiles

```bash
# Identidad del perfil
aws sts get-caller-identity --profile eb-manager

# Listar environments con plataforma y salud
aws elasticbeanstalk describe-environments --profile eb-manager \
  --query "Environments[].{App:ApplicationName,Env:EnvironmentName,Platform:PlatformArn,Status:Status,Health:Health}" \
  --output table

# Listar environments con salud y tier (diagnóstico de instancia)
aws elasticbeanstalk describe-environments --region us-east-2 \
  --query "Environments[].{Env:EnvironmentName,Status:Status,Health:Health,CNAME:CNAME,Tier:Tier.Name}" --output table

# Detalle de un environment
aws elasticbeanstalk describe-environments --environment-names Samyappusuarios-env --profile eb-manager

# Causa de la salud (Yellow/Warning) a nivel de instancia
aws elasticbeanstalk describe-instances-health --environment-name <ENV> --attribute-names All --region us-east-2 \
  --query "InstanceHealthList[].{Id:InstanceId,Health:HealthStatus,Causes:Causes,CPUIdle:System.CPUUtilization.Idle}" --output json

# Recursos del entorno (ids de instancia, ASG, LB)
aws elasticbeanstalk describe-environment-resources --environment-name <ENV> --region us-east-2 \
  --query "EnvironmentResources.{Instances:Instances[].Id,LB:LoadBalancers[].Name,ASG:AutoScalingGroups[].Name}"

# Tipo de instancia EC2
aws ec2 describe-instances --instance-ids <INSTANCE_ID> --region us-east-2 \
  --query "Reservations[].Instances[].{Type:InstanceType,State:State.Name,AZ:Placement.AvailabilityZone,LaunchTime:LaunchTime}"

# Plataformas Corretto disponibles en AL2023
aws elasticbeanstalk list-platform-versions --profile eb-manager \
  --filters "Type=PlatformBranchName,Operator=contains,Values=Corretto"
```
