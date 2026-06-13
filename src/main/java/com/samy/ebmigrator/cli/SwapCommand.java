package com.samy.ebmigrator.cli;

import com.samy.ebmigrator.aws.AwsClients;
import com.samy.ebmigrator.aws.MigrateService;
import software.amazon.awssdk.services.elasticbeanstalk.model.EnvironmentDescription;

/**
 * Comando {@code swap}: intercambia los CNAMEs de dos environments para preservar la URL.
 * Acción reversible (volver a hacer swap revierte) pero afecta a producción → confirma.
 */
public final class SwapCommand {

    private final MigrateService svc;
    private final boolean assumeYes;

    public SwapCommand(AwsClients aws, boolean assumeYes) {
        this.svc = new MigrateService(aws);
        this.assumeYes = assumeYes;
    }

    public int run(String fromEnv, String toEnv) {
        EnvironmentDescription a = svc.describeEnvironment(fromEnv);
        EnvironmentDescription b = svc.describeEnvironment(toEnv);

        System.out.println("=== eb-migrator · swap CNAMEs ===");
        System.out.printf("  %-34s  →  %s%n", a.environmentName(), a.cname());
        System.out.printf("  %-34s  →  %s%n", b.environmentName(), b.cname());
        System.out.println("Tras el swap, cada URL la servirá el OTRO environment.");

        if (!Cli.confirm("¿Intercambiar las URLs ahora?", assumeYes)) {
            System.out.println("Cancelado.");
            return 1;
        }

        svc.swapCnames(fromEnv, toEnv);
        System.out.println("✅ Swap solicitado. La propagación DNS tarda ~1-2 min.");
        System.out.println("Valida la URL de producción y luego, si todo va bien:");
        System.out.println();
        System.out.println("  1) Reapunta el CodePipeline (etapa Deploy) al nuevo environment:");
        System.out.printf("       eb-migrator repoint-pipeline --from %s --to %s --profile default%n", fromEnv, toEnv);
        System.out.println("  2) Da de baja el environment viejo:");
        System.out.printf("       eb-migrator terminate --env %s%n", fromEnv);
        return 0;
    }
}
