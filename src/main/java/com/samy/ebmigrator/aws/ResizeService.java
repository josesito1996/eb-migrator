package com.samy.ebmigrator.aws;

import software.amazon.awssdk.services.elasticbeanstalk.model.ApplicationVersionDescription;
import software.amazon.awssdk.services.elasticbeanstalk.model.ConfigurationOptionSetting;
import software.amazon.awssdk.services.elasticbeanstalk.model.DescribeConfigurationSettingsResponse;
import software.amazon.awssdk.services.elasticbeanstalk.model.EnvironmentDescription;
import software.amazon.awssdk.services.elasticbeanstalk.model.S3Location;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cambia el TIPO DE INSTANCIA de un environment de Elastic Beanstalk
 * (migración de familia {@code t2} → {@code t3}/{@code t3a}, ver CONTEXTO §6).
 *
 * El tipo se controla con la opción {@code aws:ec2:instances / InstanceTypes}. Cambiarlo
 * provoca el **reemplazo de la instancia** (en SingleInstance hay una breve indisponibilidad
 * mientras EB lanza la nueva). Solo toca Elastic Beanstalk → funciona con el perfil
 * {@code eb-manager} (no necesita permisos EC2 directos).
 */
public final class ResizeService {

    /** Namespace/opción modernos para el tipo de instancia (lista separada por comas). */
    private static final String NS_INSTANCES = "aws:ec2:instances";
    private static final String OPT_INSTANCE_TYPES = "InstanceTypes";
    /** Namespace/opción heredados (configuración por launch configuration). */
    private static final String NS_LAUNCH = "aws:autoscaling:launchconfiguration";
    private static final String OPT_INSTANCE_TYPE = "InstanceType";
    /** Tipo de environment (SingleInstance vs LoadBalanced) para avisar de la indisponibilidad. */
    private static final String NS_ENV = "aws:elasticbeanstalk:environment";
    private static final String OPT_ENV_TYPE = "EnvironmentType";

    private final AwsClients aws;

    public ResizeService(AwsClients aws) {
        this.aws = aws;
    }

    /** Lee el environment activo por nombre (lanza si no existe). */
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
     * Tipo(s) de instancia configurado(s) actualmente. Busca primero la opción moderna
     * ({@code aws:ec2:instances/InstanceTypes}) y cae a la heredada
     * ({@code aws:autoscaling:launchconfiguration/InstanceType}).
     */
    public Optional<String> currentInstanceType(EnvironmentDescription env) {
        return readOption(env, NS_INSTANCES, OPT_INSTANCE_TYPES)
                .filter(v -> !v.isBlank())
                .or(() -> readOption(env, NS_LAUNCH, OPT_INSTANCE_TYPE));
    }

    /** ¿El environment es de instancia única (sin balanceador)? → el cambio implica corte breve. */
    public boolean isSingleInstance(EnvironmentDescription env) {
        return readOption(env, NS_ENV, OPT_ENV_TYPE)
                .map(v -> "SingleInstance".equalsIgnoreCase(v))
                .orElse(true); // por defecto asumimos single (el caso de este proyecto)
    }

    private Optional<String> readOption(EnvironmentDescription env, String namespace, String optionName) {
        DescribeConfigurationSettingsResponse settings = aws.eb()
                .describeConfigurationSettings(b -> b
                        .applicationName(env.applicationName())
                        .environmentName(env.environmentName()));
        return settings.configurationSettings().stream()
                .flatMap(cs -> cs.optionSettings().stream())
                .filter(o -> namespace.equals(o.namespace()) && optionName.equals(o.optionName()))
                .map(ConfigurationOptionSetting::value)
                .filter(v -> v != null)
                .findFirst();
    }

    /**
     * Comprueba que el {@code SourceBundle} de la versión de app actualmente asociada al
     * environment exista en S3. Si NO existe, un reemplazo de instancia (lo que provoca el
     * cambio de tipo) fallaría con {@code S3 404 NoSuchKey} al re-descargar el bundle.
     *
     * Caso típico: el artefacto de la versión vivía bajo {@code resources/environments/<env-id>/...}
     * de un environment origen que ya se terminó (EB borra ese prefijo) → versión colgada.
     *
     * @return la ubicación {@code s3://bucket/key} que falta, o vacío si existe / no se pudo
     *         determinar (sin version label, sin bundle, o un error que no sea 404 — no bloqueamos por eso).
     */
    public Optional<String> missingCurrentArtifact(EnvironmentDescription env) {
        String label = env.versionLabel();
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        List<ApplicationVersionDescription> versions = aws.eb()
                .describeApplicationVersions(b -> b.applicationName(env.applicationName()).versionLabels(label))
                .applicationVersions();
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        S3Location bundle = versions.get(0).sourceBundle();
        if (bundle == null || bundle.s3Bucket() == null || bundle.s3Key() == null) {
            return Optional.empty();
        }
        try {
            aws.s3().headObject(b -> b.bucket(bundle.s3Bucket()).key(bundle.s3Key()));
            return Optional.empty(); // el bundle existe → OK
        } catch (NoSuchKeyException e) {
            return Optional.of("s3://" + bundle.s3Bucket() + "/" + bundle.s3Key());
        } catch (S3Exception e) {
            // HeadObject sobre un objeto inexistente puede llegar como 404 genérico (sin NoSuchKey tipado).
            if (e.statusCode() == 404) {
                return Optional.of("s3://" + bundle.s3Bucket() + "/" + bundle.s3Key());
            }
            return Optional.empty(); // otro error (permisos, red…): no bloqueamos el resize por esto.
        }
    }

    /**
     * Aplica el nuevo tipo de instancia con {@code update-environment}
     * (opción {@code aws:ec2:instances/InstanceTypes}). El cambio es asíncrono:
     * EB pasa a {@code Updating} y reemplaza la instancia.
     */
    public void updateInstanceType(String envName, String newType) {
        aws.eb().updateEnvironment(b -> b
                .environmentName(envName)
                .optionSettings(ConfigurationOptionSetting.builder()
                        .namespace(NS_INSTANCES)
                        .optionName(OPT_INSTANCE_TYPES)
                        .value(newType)
                        .build()));
    }

    /**
     * Espera a que el environment vuelva a {@code Ready} tras el update, informando
     * status/health en cada sondeo. Devuelve el estado final.
     */
    public EnvironmentDescription waitUntilReady(String envId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        EnvironmentDescription env;
        do {
            Thread.sleep(15_000);
            env = aws.eb().describeEnvironments(b -> b.environmentIds(envId).includeDeleted(false))
                    .environments().get(0);
            System.out.printf("   … status=%s health=%s%n", env.statusAsString(), env.healthAsString());
            if ("Ready".equalsIgnoreCase(env.statusAsString())) {
                return env;
            }
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Timeout esperando a que el environment vuelva a Ready tras el cambio de instancia.");
    }
}