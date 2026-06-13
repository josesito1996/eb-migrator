package com.samy.ebmigrator.cli;

import com.samy.ebmigrator.aws.AwsClients;
import com.samy.ebmigrator.aws.MigrateService;
import com.samy.ebmigrator.aws.MigrateService.ArtifactLocation;
import com.samy.ebmigrator.aws.Platforms;
import software.amazon.awssdk.services.elasticbeanstalk.model.CreateEnvironmentResponse;
import software.amazon.awssdk.services.elasticbeanstalk.model.EnvironmentDescription;

import java.time.Duration;
import java.util.Optional;

/**
 * Comando {@code create-replica}: ejecuta los pasos NO destructivos del Blue/Green:
 * plantilla → versión de app (artefacto desplegado) → environment AL2023 → espera Ready.
 *
 * NO toca el environment de producción (no hace swap ni terminate). Imprime la URL
 * temporal para que valides antes de cambiar la URL con {@code swap}.
 */
public final class MigrateCommand {

    private final AwsClients aws;
    private final MigrateService svc;
    private final boolean assumeYes;

    public MigrateCommand(AwsClients aws, boolean assumeYes) {
        this.aws = aws;
        this.svc = new MigrateService(aws);
        this.assumeYes = assumeYes;
    }

    public int run(String sourceEnvName, String targetStackOverride) throws InterruptedException {
        EnvironmentDescription src = svc.describeEnvironment(sourceEnvName);
        String app = src.applicationName();
        String envId = src.environmentId();
        String sourceStack = src.solutionStackName();

        System.out.println("=== eb-migrator · create-replica ===");
        System.out.printf("Aplicación : %s%n", app);
        System.out.printf("Origen     : %s (%s)%n", sourceEnvName, envId);
        System.out.printf("Plataforma : %s  [%s]%n", sourceStack, Platforms.status(sourceStack));

        if (!Platforms.isAmazonLinux2(sourceStack) && !Platforms.isRetiredBranch(sourceStack)) {
            System.out.println("ⓘ  Este environment NO está en una plataforma deprecada. Nada que migrar.");
            if (!Cli.confirm("¿Continuar de todos modos?", assumeYes)) return 0;
        }

        String targetStack = (targetStackOverride != null && !targetStackOverride.isBlank())
                ? targetStackOverride
                : svc.resolveTargetStack(sourceStack);
        System.out.printf("Destino    : %s%n", targetStack);

        String newEnvName = sourceEnvName + "-al2023";
        String templateName = app + "-al2023-cfg";
        String versionLabel = app + "-al2023-src";
        System.out.printf("Nuevo env  : %s%n", newEnvName);
        System.out.println();

        if (!Cli.confirm("Se crearán plantilla, versión de app y un environment NUEVO (sin tocar producción). ¿Proceder?", assumeYes)) {
            System.out.println("Cancelado.");
            return 1;
        }

        // Paso 1
        System.out.println("[1/4] Creando plantilla de configuración…");
        svc.createConfigurationTemplate(app, templateName, envId);
        Optional<String> imageId = svc.findHardcodedImageId(app, templateName);
        imageId.ifPresent(id -> System.out.printf("      ⚠ ImageId AL2 detectado (%s) → se eliminará al crear el environment.%n", id));

        // Paso 2
        System.out.println("[2/4] Localizando el artefacto desplegado en S3…");
        ArtifactLocation deployed = svc.locateDeployedArtifact(envId, app);
        System.out.printf("      → %s%n", deployed);
        // Copia a una ubicación estable: el artefacto desplegado vive bajo el prefijo del env origen,
        // que EB borra al terminarlo. Apuntar la versión a una clave estable evita que quede colgada
        // (S3 404) y que un futuro resize/reemplazo de instancia del env migrado falle.
        ArtifactLocation artifact = svc.copyToStableLocation(deployed, app, versionLabel);
        System.out.printf("      ↳ copiado a ubicación estable: %s%n", artifact);

        // Paso 3
        System.out.println("[3/4] Registrando versión de la app desde el bucket de EB…");
        svc.createApplicationVersion(app, versionLabel, artifact);

        // Paso 4
        System.out.println("[4/4] Creando el environment AL2023 (esto tarda ~2-4 min)…");
        CreateEnvironmentResponse created = svc.createReplicaEnvironment(app, newEnvName, templateName, targetStack, versionLabel);
        String newId = created.environmentId();
        System.out.printf("      Environment %s (%s) en creación…%n", newEnvName, newId);

        EnvironmentDescription ready = svc.waitUntilReady(newId, Duration.ofMinutes(15));

        System.out.println();
        System.out.println("✅ Réplica lista.");
        System.out.printf("   Nombre : %s (%s)%n", ready.environmentName(), newId);
        System.out.printf("   Health : %s%n", ready.healthAsString());
        System.out.printf("   URL temporal: http://%s%n", ready.cname());
        System.out.println();
        System.out.println("Siguiente: valida la URL temporal (login, endpoints reales). Cuando esté OK:");
        System.out.printf("   eb-migrator swap --from %s --to %s%n", sourceEnvName, newEnvName);
        return 0;
    }
}
