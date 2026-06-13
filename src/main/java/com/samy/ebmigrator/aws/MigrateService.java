package com.samy.ebmigrator.aws;

import software.amazon.awssdk.services.elasticbeanstalk.model.ConfigurationOptionSetting;
import software.amazon.awssdk.services.elasticbeanstalk.model.CreateApplicationVersionResponse;
import software.amazon.awssdk.services.elasticbeanstalk.model.CreateEnvironmentResponse;
import software.amazon.awssdk.services.elasticbeanstalk.model.DescribeConfigurationSettingsResponse;
import software.amazon.awssdk.services.elasticbeanstalk.model.ElasticBeanstalkException;
import software.amazon.awssdk.services.elasticbeanstalk.model.EnvironmentDescription;
import software.amazon.awssdk.services.elasticbeanstalk.model.OptionSpecification;
import software.amazon.awssdk.services.elasticbeanstalk.model.S3Location;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementa el procedimiento Blue/Green validado en la migración piloto:
 * crear una réplica del environment en la plataforma AL2023 destino,
 * reutilizando el artefacto realmente desplegado.
 *
 * Cada método mapea a un paso de la plantilla repetible del documento de contexto.
 */
public final class MigrateService {

    private final com.samy.ebmigrator.aws.AwsClients aws;

    public MigrateService(com.samy.ebmigrator.aws.AwsClients aws) {
        this.aws = aws;
    }

    /** Paso 0: lee el environment origen por nombre. */
    public EnvironmentDescription describeEnvironment(String name) {
        List<EnvironmentDescription> envs = aws.eb()
                .describeEnvironments(b -> b.environmentNames(name).includeDeleted(false))
                .environments();
        if (envs.isEmpty()) {
            throw new IllegalArgumentException("No existe el environment '" + name + "' (activo) en esta región/cuenta.");
        }
        return envs.get(0);
    }

    /**
     * Paso 6 (auto-detección): encuentra el solution stack AL2023 equivalente
     * al del environment origen (mismo runtime, p. ej. "running Corretto 11").
     */
    public String resolveTargetStack(String sourceStack) {
        String runtime = runtimeSuffix(sourceStack); // p. ej. "running Corretto 11"
        if (runtime == null) {
            throw new IllegalStateException("No se pudo extraer el runtime de: " + sourceStack);
        }
        List<String> stacks = aws.eb().listAvailableSolutionStacks().solutionStacks();
        return stacks.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).contains("amazon linux 2023"))
                .filter(s -> s.toLowerCase(Locale.ROOT).endsWith(runtime.toLowerCase(Locale.ROOT)))
                .max(Comparator.comparing(MigrateService::stackVersion))
                .orElseThrow(() -> new IllegalStateException(
                        "No hay solution stack AL2023 para el runtime '" + runtime + "'. "
                                + "Especifica --target-stack manualmente."));
    }

    /**
     * Paso 1: guarda la configuración del origen como plantilla.
     * Idempotente: si ya existe una plantilla con ese nombre (de un intento previo
     * que quedó a medias), la elimina y la vuelve a crear con estado fresco.
     */
    public void createConfigurationTemplate(String app, String templateName, String sourceEnvId) {
        deleteConfigurationTemplateIfExists(app, templateName);
        aws.eb().createConfigurationTemplate(b -> b
                .applicationName(app)
                .templateName(templateName)
                .environmentId(sourceEnvId)
                .description("Plantilla AL2023 generada por eb-migrator desde " + sourceEnvId));
    }

    /** Borra una plantilla de configuración si existe; ignora el error si no existe. */
    private void deleteConfigurationTemplateIfExists(String app, String templateName) {
        try {
            aws.eb().deleteConfigurationTemplate(b -> b.applicationName(app).templateName(templateName));
        } catch (ElasticBeanstalkException e) {
            // No existía (o no se pudo borrar): seguimos. create lanzará si hay un problema real.
        }
    }

    /**
     * Inspecciona la plantilla y devuelve el ImageId (AMI de AL2) si está fijado,
     * para avisar que se eliminará al crear el environment AL2023.
     */
    public Optional<String> findHardcodedImageId(String app, String templateName) {
        DescribeConfigurationSettingsResponse settings = aws.eb()
                .describeConfigurationSettings(b -> b.applicationName(app).templateName(templateName));
        return settings.configurationSettings().stream()
                .flatMap(cs -> cs.optionSettings().stream())
                .filter(o -> "aws:autoscaling:launchconfiguration".equals(o.namespace())
                        && "ImageId".equals(o.optionName()))
                .map(ConfigurationOptionSetting::value)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    /**
     * Paso 2: localiza el artefacto realmente desplegado en el bucket gestionado de EB
     * ({@code resources/environments/<env-id>/_runtime/_versions/<app>/}).
     * Devuelve la clave S3 del objeto más reciente.
     */
    public ArtifactLocation locateDeployedArtifact(String sourceEnvId, String app) {
        String bucket = aws.eb().createStorageLocation().s3Bucket();
        String prefix = String.format("resources/environments/%s/_runtime/_versions/%s/", sourceEnvId, app);

        List<S3Object> objects = aws.s3().listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket).prefix(prefix).build())
                .contents();

        S3Object latest = objects.stream()
                .filter(o -> o.size() != null && o.size() > 0)
                .max(Comparator.comparing(S3Object::lastModified))
                .orElseThrow(() -> new IllegalStateException(
                        "No se encontró artefacto desplegado en s3://" + bucket + "/" + prefix));

        return new ArtifactLocation(bucket, latest.key(), latest.size());
    }

    /**
     * Paso 3: registra una nueva versión de la app apuntando al artefacto del bucket de EB.
     * Idempotente: si la version label ya existe (intento previo), la elimina primero
     * SIN borrar el bundle en S3 (es el artefacto desplegado, no debe tocarse).
     */
    public void createApplicationVersion(String app, String versionLabel, ArtifactLocation artifact) {
        deleteApplicationVersionIfExists(app, versionLabel);
        CreateApplicationVersionResponse resp = aws.eb().createApplicationVersion(b -> b
                .applicationName(app)
                .versionLabel(versionLabel)
                .description("Artefacto desplegado reutilizado por eb-migrator")
                .sourceBundle(S3Location.builder()
                        .s3Bucket(artifact.bucket())
                        .s3Key(artifact.key())
                        .build())
                .autoCreateApplication(false)
                .process(false));
        // resp se ignora; lanza excepción si falla.
        assert resp != null;
    }

    /** Borra una versión de app si existe, conservando el bundle en S3. Ignora si no existe. */
    private void deleteApplicationVersionIfExists(String app, String versionLabel) {
        try {
            aws.eb().deleteApplicationVersion(b -> b
                    .applicationName(app)
                    .versionLabel(versionLabel)
                    .deleteSourceBundle(false));
        } catch (ElasticBeanstalkException e) {
            // No existía: seguimos.
        }
    }

    /**
     * Paso 4: crea el environment réplica en AL2023, heredando la plantilla,
     * con la nueva versión y eliminando el ImageId fijo de AL2.
     */
    public CreateEnvironmentResponse createReplicaEnvironment(
            String app, String newEnvName, String templateName, String targetStack, String versionLabel) {
        return aws.eb().createEnvironment(b -> b
                .applicationName(app)
                .environmentName(newEnvName)
                .templateName(templateName)
                .solutionStackName(targetStack)
                .versionLabel(versionLabel)
                .optionsToRemove(OptionSpecification.builder()
                        .namespace("aws:autoscaling:launchconfiguration")
                        .optionName("ImageId")
                        .build()));
    }

    /** Paso 5: espera a que el environment quede Ready, devolviendo su estado final. */
    public EnvironmentDescription waitUntilReady(String envId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        EnvironmentDescription env;
        do {
            Thread.sleep(15_000);
            env = describeById(envId);
            System.out.printf("   … status=%s health=%s%n", env.statusAsString(), env.healthAsString());
            if ("Ready".equalsIgnoreCase(env.statusAsString())) {
                return env;
            }
            if ("Terminated".equalsIgnoreCase(env.statusAsString())) {
                throw new IllegalStateException("El environment terminó en estado Terminated. Revisa los eventos en la consola.");
            }
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Timeout esperando a que el environment quede Ready.");
    }

    /** Paso 6: intercambia los CNAMEs para preservar la URL de producción. */
    public void swapCnames(String sourceEnvName, String destEnvName) {
        aws.eb().swapEnvironmentCNAMEs(b -> b
                .sourceEnvironmentName(sourceEnvName)
                .destinationEnvironmentName(destEnvName));
    }

    /** Paso 8: da de baja el environment viejo. */
    public void terminate(String envName) {
        aws.eb().terminateEnvironment(b -> b.environmentName(envName));
    }

    /**
     * Espera a que el environment quede realmente {@code Terminated}.
     * Devuelve {@code true} si terminó; {@code false} si se atascó y volvió a {@code Ready}
     * (caso del Security Group con instancia huérfana) o si venció el timeout.
     */
    public boolean waitUntilTerminated(String envId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(15_000);
            List<EnvironmentDescription> envs = aws.eb()
                    .describeEnvironments(b -> b.environmentIds(envId).includeDeleted(true))
                    .environments();
            if (envs.isEmpty()) return true;
            String status = envs.get(0).statusAsString();
            System.out.printf("   … status=%s%n", status);
            if ("Terminated".equalsIgnoreCase(status)) return true;
            if ("Ready".equalsIgnoreCase(status)) return false; // rollback → atascado
        }
        return false;
    }

    public EnvironmentDescription describeById(String envId) {
        return aws.eb()
                .describeEnvironments(b -> b.environmentIds(envId).includeDeleted(false))
                .environments().get(0);
    }

    // --- helpers ---

    /** Extrae el sufijo de runtime, p. ej. "running Corretto 11". */
    static String runtimeSuffix(String stack) {
        if (stack == null) return null;
        int idx = stack.toLowerCase(Locale.ROOT).indexOf("running ");
        return idx < 0 ? null : stack.substring(idx);
    }

    /** Parsea la versión "vX.Y.Z" del solution stack para comparar (mayor = más nueva). */
    static Long stackVersion(String stack) {
        // Devuelve un long comparable a partir de vMAJOR.MINOR.PATCH; 0 si no se puede.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("v(\\d+)\\.(\\d+)\\.(\\d+)").matcher(stack);
        if (!m.find()) return 0L;
        long major = Long.parseLong(m.group(1));
        long minor = Long.parseLong(m.group(2));
        long patch = Long.parseLong(m.group(3));
        return major * 1_000_000L + minor * 1_000L + patch;
    }

    /** Localización de un artefacto en S3. */
    public static final class ArtifactLocation {
        private final String bucket;
        private final String key;
        private final long size;

        ArtifactLocation(String bucket, String key, Long size) {
            this.bucket = bucket;
            this.key = key;
            this.size = size == null ? 0 : size;
        }

        public String bucket() { return bucket; }
        public String key() { return key; }
        public long size() { return size; }

        @Override
        public String toString() {
            return String.format("s3://%s/%s (%.1f MB)", bucket, key, size / 1_048_576.0);
        }
    }
}
