package com.samy.ebmigrator;

import com.samy.ebmigrator.aws.AwsClients;
import com.samy.ebmigrator.cli.AuditCommand;
import com.samy.ebmigrator.cli.Cli;
import com.samy.ebmigrator.cli.InteractiveConsole;
import com.samy.ebmigrator.cli.MigrateCommand;
import com.samy.ebmigrator.cli.PowerCommand;
import com.samy.ebmigrator.cli.RepointPipelineCommand;
import com.samy.ebmigrator.cli.ResizeCommand;
import com.samy.ebmigrator.cli.SwapCommand;
import com.samy.ebmigrator.cli.TerminateCommand;

import java.util.Map;

/**
 * eb-migrator — utilitario CLI para auditar y migrar environments de Elastic Beanstalk
 * de Amazon Linux 2 a AL2023 mediante el procedimiento Blue/Green (preserva la URL).
 *
 * Uso:
 *   eb-migrator audit
 *   eb-migrator create-replica --env NOMBRE [--target-stack "..."]
 *   eb-migrator swap --from VIEJO --to NUEVO
 *   eb-migrator terminate --env NOMBRE
 *
 * Flags globales: --profile (def. eb-manager), --region (def. us-east-2), --yes (no interactivo).
 */
public final class Main {

    private static final String DEFAULT_PROFILE = "eb-manager";
    private static final String DEFAULT_REGION = "us-east-2";

    public static void main(String[] args) {
        if (args.length > 0 && isHelp(args[0])) {
            printUsage();
            System.exit(0);
        }
        // Sin argumentos o con "menu"/"interactive": consola interactiva (pide los
        // parámetros por pantalla con System.out.print en vez de recibirlos como flags).
        if (args.length == 0 || args[0].equals("menu") || args[0].equals("interactive")) {
            System.exit(new InteractiveConsole(DEFAULT_PROFILE, DEFAULT_REGION).run());
        }

        String command = args[0];
        Map<String, String> flags = Cli.parseFlags(args, 1);
        String profile = flags.getOrDefault("profile", DEFAULT_PROFILE);
        String region = flags.getOrDefault("region", DEFAULT_REGION);
        boolean assumeYes = flags.containsKey("yes");

        int exit;
        try (AwsClients aws = new AwsClients(profile, region)) {
            switch (command) {
                case "audit":
                    exit = new AuditCommand(aws).run();
                    break;
                case "create-replica":
                    exit = new MigrateCommand(aws, assumeYes)
                            .run(required(flags, "env"), flags.get("target-stack"));
                    break;
                case "swap":
                    exit = new SwapCommand(aws, assumeYes)
                            .run(required(flags, "from"), required(flags, "to"));
                    break;
                case "repoint-pipeline":
                    exit = new RepointPipelineCommand(aws, assumeYes)
                            .run(required(flags, "from"), required(flags, "to"));
                    break;
                case "terminate":
                    exit = new TerminateCommand(aws, assumeYes).run(required(flags, "env"));
                    break;
                case "power":
                    exit = new PowerCommand(aws, assumeYes)
                            .run(required(flags, "env"), required(flags, "state"));
                    break;
                case "resize":
                    exit = new ResizeCommand(aws, assumeYes)
                            .run(required(flags, "env"), flags.get("to"));
                    break;
                default:
                    System.err.println("Comando desconocido: " + command);
                    printUsage();
                    exit = 1;
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("✗ " + e.getMessage());
            exit = 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("✗ Interrumpido.");
            exit = 130;
        } catch (Exception e) {
            System.err.println("✗ Error inesperado: " + e);
            exit = 3;
        }
        System.exit(exit);
    }

    private static String required(Map<String, String> flags, String key) {
        String v = flags.get(key);
        if (v == null || v.isBlank() || "true".equals(v)) {
            throw new IllegalArgumentException("Falta el argumento obligatorio --" + key);
        }
        return v;
    }

    private static boolean isHelp(String a) {
        return a.equals("-h") || a.equals("--help") || a.equals("help");
    }

    private static void printUsage() {
        System.out.println(String.join("\n",
                "eb-migrator — auditar y migrar environments de Elastic Beanstalk (AL2 → AL2023).",
                "",
                "Sin argumentos abre la CONSOLA INTERACTIVA (pide los datos por pantalla).",
                "También: 'menu' o 'interactive' para forzarla.",
                "",
                "Comandos (modo por flags):",
                "  audit                              Inventario de environments y plataformas deprecadas (read-only).",
                "  create-replica --env NOMBRE        Crea la réplica AL2023 (no toca producción) y espera Ready.",
                "                 [--target-stack S]  Solution stack destino (auto-detectado si se omite).",
                "  swap --from VIEJO --to NUEVO       Intercambia las URLs para preservar la de producción.",
                "  repoint-pipeline --from VIEJO --to NUEVO",
                "                                     Reapunta la etapa Deploy de CodePipeline al nuevo environment.",
                "                                     (requiere permisos CodePipeline → usa --profile default)",
                "  terminate --env NOMBRE             Da de baja un environment (destructivo, espera a Terminated).",
                "  power --env NOMBRE --state E       Controla ESCALADO e INSTANCIA. E =",
                "                                       scaling-off  desactiva el escalado (suspende el ASG)",
                "                                       scaling-on   reactiva el escalado (reanuda el ASG)",
                "                                       stop         apaga la instancia (y desactiva escalado)",
                "                                       start        enciende la instancia",
                "                                       reboot       reinicia la instancia",
                "                                       terminate-instance  destruye la instancia EC2",
                "                                     (stop/start/reboot/terminate-instance requieren EC2 → --profile default)",
                "  resize --env NOMBRE [--to TIPO]    Cambia el tipo de instancia (t2 → t3/t3a, sube RAM).",
                "                                     Sin --to sugiere un destino t3a y lo pide por consola.",
                "                                     Solo Elastic Beanstalk → basta el perfil eb-manager.",
                "",
                "Flags globales:",
                "  --profile P   Perfil AWS CLI (def. " + DEFAULT_PROFILE + ")",
                "  --region R    Región (def. " + DEFAULT_REGION + ")",
                "  --yes         No interactivo (omite confirmaciones)",
                "",
                "Ejemplo de flujo completo:",
                "  eb-migrator audit",
                "  eb-migrator create-replica --env Samyappcasos-env",
                "  # validar la URL temporal…",
                "  eb-migrator swap --from Samyappcasos-env --to Samyappcasos-env-al2023",
                "  # validar producción…",
                "  eb-migrator repoint-pipeline --from Samyappcasos-env --to Samyappcasos-env-al2023 --profile default",
                "  eb-migrator terminate --env Samyappcasos-env"));
    }
}
