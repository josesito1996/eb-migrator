package com.samy.ebmigrator.cli;

import com.samy.ebmigrator.aws.AwsClients;
import com.samy.ebmigrator.aws.PipelineService;
import com.samy.ebmigrator.aws.PipelineService.DeployActionMatch;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Comando {@code repoint-pipeline}: reapunta la etapa Deploy de CodePipeline del
 * environment viejo al nuevo, tras el swap. Cambio quirúrgico (solo {@code EnvironmentName}),
 * con backup previo del pipeline y confirmación.
 *
 * <p>Permisos: requiere CodePipeline (list/get/update). El perfil {@code eb-manager}
 * (solo Elastic Beanstalk) NO los tiene → usar un perfil admin, p. ej. {@code --profile default}.
 */
public final class RepointPipelineCommand {

    private final PipelineService svc;
    private final boolean assumeYes;

    public RepointPipelineCommand(AwsClients aws, boolean assumeYes) {
        this.svc = new PipelineService(aws);
        this.assumeYes = assumeYes;
    }

    public int run(String fromEnv, String toEnv) {
        System.out.println("=== eb-migrator · repoint-pipeline (etapa Deploy) ===");
        System.out.printf("  De  : %s%n", fromEnv);
        System.out.printf("  A   : %s%n", toEnv);
        System.out.println("Buscando pipelines cuya etapa Deploy (Elastic Beanstalk) apunte al viejo…");

        List<DeployActionMatch> matches;
        try {
            matches = svc.findDeployActionsForEnvironment(fromEnv);
        } catch (RuntimeException e) {
            return reportAwsError(e);
        }

        if (matches.isEmpty()) {
            System.out.printf("No hay ninguna acción Deploy de Elastic Beanstalk apuntando a '%s'.%n", fromEnv);
            System.out.println("Nada que reapuntar. (¿Es ese el nombre del environment viejo? ¿Tiene CI/CD?)");
            return 0;
        }

        System.out.println("Acciones Deploy encontradas:");
        for (DeployActionMatch m : matches) {
            System.out.printf("  • pipeline '%s'  ·  stage '%s'  ·  action '%s'  ·  app '%s'%n",
                    m.pipeline(), m.stage(), m.action(), m.application());
        }

        if (!Cli.confirm("¿Reapuntar estas acciones a '" + toEnv + "'?", assumeYes)) {
            System.out.println("Cancelado.");
            return 1;
        }

        // Un pipeline puede tener varias acciones que coinciden; lo procesamos una sola vez.
        Set<String> pipelines = new LinkedHashSet<>();
        for (DeployActionMatch m : matches) {
            pipelines.add(m.pipeline());
        }

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        int total = 0;
        try {
            for (String pipeline : pipelines) {
                Path backup = Paths.get(pipeline + ".backup-" + ts + ".json");
                int changed = svc.repoint(pipeline, fromEnv, toEnv, backup);
                total += changed;
                System.out.printf("  ✓ '%s': %d acción(es) reapuntada(s). Backup: %s%n",
                        pipeline, changed, backup.toAbsolutePath());
            }
        } catch (RuntimeException e) {
            return reportAwsError(e);
        }

        System.out.printf("✅ Listo: %d acción(es) Deploy reapuntada(s) a '%s'.%n", total, toEnv);
        System.out.println("Verifica con un push o una ejecución manual del pipeline.");
        System.out.printf("Para revertir: repoint-pipeline --from %s --to %s%n", toEnv, fromEnv);
        return 0;
    }

    /** Traduce el error típico de permisos a una pista accionable. */
    private int reportAwsError(RuntimeException e) {
        String msg = String.valueOf(e.getMessage());
        String low = msg.toLowerCase(Locale.ROOT);
        if (low.contains("accessdenied") || low.contains("not authorized") || low.contains("unauthorized")) {
            System.err.println("✗ Acceso denegado a CodePipeline.");
            System.err.println("  El perfil 'eb-manager' (solo Elastic Beanstalk) NO tiene permisos de CodePipeline.");
            System.err.println("  Reintenta con un perfil admin:  --profile default");
            return 2;
        }
        System.err.println("✗ Error con CodePipeline: " + msg);
        return 3;
    }
}
