package com.samy.ebmigrator.cli;

import com.samy.ebmigrator.aws.AwsClients;

/**
 * Modo consola interactivo: en lugar de pasar los parámetros como flags
 * (--env, --from, --to…), el utilitario los pide por pantalla con
 * {@code System.out.print} y los lee de la entrada estándar.
 *
 * Se lanza cuando el programa se ejecuta sin argumentos o con el comando
 * {@code menu} / {@code interactive}. El modo por flags sigue disponible.
 *
 * Las confirmaciones de cada acción se mantienen (assumeYes=false): el modo
 * interactivo nunca omite las preguntas de seguridad.
 */
public final class InteractiveConsole {

    private final String defaultProfile;
    private final String defaultRegion;

    public InteractiveConsole(String defaultProfile, String defaultRegion) {
        this.defaultProfile = defaultProfile;
        this.defaultRegion = defaultRegion;
    }

    public int run() {
        System.out.println("==========================================================");
        System.out.println("  eb-migrator · consola interactiva");
        System.out.println("  Migración Blue/Green de Elastic Beanstalk (AL2 -> AL2023)");
        System.out.println("==========================================================");

        String profile = Cli.prompt("Perfil AWS", defaultProfile);
        String region = Cli.prompt("Región", defaultRegion);

        try (AwsClients aws = new AwsClients(profile, region)) {
            System.out.printf("%nConectado con perfil '%s' en región '%s'.%n", profile, region);
            return loop(aws);
        } catch (Exception e) {
            System.err.println("✗ No se pudieron iniciar los clientes AWS: " + e.getMessage());
            return 3;
        }
    }

    private int loop(AwsClients aws) {
        while (true) {
            printMenu();
            String opt = Cli.prompt("Elige una opción", "0");
            try {
                switch (opt) {
                    case "1":
                        new AuditCommand(aws).run();
                        break;
                    case "2":
                        doCreateReplica(aws);
                        break;
                    case "3":
                        doSwap(aws);
                        break;
                    case "4":
                        doRepointPipeline(aws);
                        break;
                    case "5":
                        doTerminate(aws);
                        break;
                    case "6":
                        doPower(aws);
                        break;
                    case "7":
                        doResize(aws);
                        break;
                    case "0":
                        System.out.println("Hasta luego.");
                        return 0;
                    default:
                        System.out.println("Opción no válida: " + opt);
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.err.println("✗ " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("✗ Interrumpido.");
                return 130;
            } catch (Exception e) {
                System.err.println("✗ Error inesperado: " + e);
            }
        }
    }

    private void printMenu() {
        System.out.println();
        System.out.println("------------------------ MENÚ ------------------------");
        System.out.println("  1) audit             Inventario de environments (solo lectura)");
        System.out.println("  2) create-replica    Crear réplica AL2023 (no toca producción)");
        System.out.println("  3) swap              Intercambiar URLs (afecta producción)");
        System.out.println("  4) repoint-pipeline  Reapuntar la etapa Deploy de CodePipeline al nuevo env");
        System.out.println("  5) terminate         Dar de baja un environment (destructivo)");
        System.out.println("  6) power             Escalado on/off · apagar/encender/reiniciar/terminar instancia");
        System.out.println("  7) resize            Cambiar el tipo de instancia (t2 -> t3/t3a, subir RAM)");
        System.out.println("  0) salir");
        System.out.println("------------------------------------------------------");
    }

    private void doCreateReplica(AwsClients aws) throws InterruptedException {
        String env = Cli.promptRequired("Environment origen a migrar");
        String stack = Cli.prompt("Solution stack destino (Enter = auto-detectar)", null);
        new MigrateCommand(aws, false).run(env, stack);
    }

    private void doSwap(AwsClients aws) {
        String from = Cli.promptRequired("Environment FROM (el que cede su URL)");
        String to = Cli.promptRequired("Environment TO (el que tomará la URL)");
        new SwapCommand(aws, false).run(from, to);
    }

    private void doRepointPipeline(AwsClients aws) {
        String from = Cli.promptRequired("Environment VIEJO (al que apunta el pipeline ahora)");
        String to = Cli.promptRequired("Environment NUEVO (al que debe apuntar)");
        System.out.println("Nota: necesita permisos de CodePipeline; 'eb-manager' no los tiene.");
        System.out.println("      Si falla por permisos, relanza el menú con el perfil 'default'.");
        new RepointPipelineCommand(aws, false).run(from, to);
    }

    private void doTerminate(AwsClients aws) throws InterruptedException {
        String env = Cli.promptRequired("Environment a terminar");
        new TerminateCommand(aws, false).run(env);
    }

    private void doPower(AwsClients aws) {
        String env = Cli.promptRequired("Environment");
        System.out.println("Estados disponibles:");
        System.out.println("  scaling-off / scaling-on    desactivar / reactivar el escalado (ASG)");
        System.out.println("  stop / start / reboot       apagar / encender / reiniciar la instancia");
        System.out.println("  terminate-instance          destruir la instancia EC2");
        String state = Cli.prompt("Estado", "scaling-off");
        System.out.println("Recuerda: stop/start/reboot/terminate-instance necesitan un perfil con permisos EC2 (ej. default).");
        new PowerCommand(aws, false).run(env, state);
    }

    private void doResize(AwsClients aws) {
        String env = Cli.promptRequired("Environment a redimensionar");
        System.out.println("Deja el tipo destino en blanco para que sugiera uno (familia t3a, subiendo un tamaño).");
        System.out.println("Solo toca Elastic Beanstalk → basta el perfil eb-manager.");
        String to = Cli.prompt("Tipo destino (Enter = sugerir)", null);
        new ResizeCommand(aws, false).run(env, to);
    }
}
