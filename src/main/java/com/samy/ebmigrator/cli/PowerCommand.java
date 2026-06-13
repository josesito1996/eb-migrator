package com.samy.ebmigrator.cli;

import com.samy.ebmigrator.aws.AwsClients;
import com.samy.ebmigrator.aws.PowerService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.util.List;
import java.util.Locale;

/**
 * Comando {@code power}: controla por separado el ESCALADO (Auto Scaling Group) y la
 * propia INSTANCIA (encender/apagar/reiniciar/terminar) de un environment.
 *
 * Estados ({@code --state}):
 *   scaling-off  → desactiva el escalado: suspende el ASG (deja de auto-reponer/auto-sanar). No toca la instancia.
 *   scaling-on   → reactiva el escalado: reanuda el ASG (EB vuelve a gestionar). [alias: manage]
 *   stop         → apaga la instancia (stop) y suspende el ASG para que no la reponga. [alias: off]
 *   start        → enciende la instancia (start). El ASG queda suspendido (gestión manual). [alias: on]
 *   reboot       → reinicia la instancia (reboot). No toca el ASG. [alias: restart]
 *   terminate-instance → termina (destruye) la instancia EC2. Si el escalado está activo, EB lanza
 *                        una nueva (reciclado); si está desactivado, queda sin instancia. [alias: kill]
 *
 * Permisos: las operaciones de ESCALADO (scaling-off/on) usan el ASG y las permite {@code eb-manager};
 * las de INSTANCIA (stop/start/reboot/terminate-instance) requieren permisos EC2
 * ({@code ec2:Stop/Start/Reboot/TerminateInstances}) → usa {@code --profile default} (admin).
 */
public final class PowerCommand {

    private final PowerService svc;
    private final boolean assumeYes;

    public PowerCommand(AwsClients aws, boolean assumeYes) {
        this.svc = new PowerService(aws);
        this.assumeYes = assumeYes;
    }

    public int run(String envName, String stateRaw) {
        String state = normalize(stateRaw);
        if (state == null) {
            throw new IllegalArgumentException("--state inválido: '" + stateRaw + "'. Usa uno de: "
                    + "scaling-off | scaling-on | stop | start | reboot | terminate-instance "
                    + "(alias: off=stop, on=start, manage=scaling-on, restart=reboot).");
        }

        String envId = svc.resolveEnvId(envName);
        AutoScalingGroup asg = svc.findAsg(envId);
        String asgName = asg.autoScalingGroupName();
        List<String> instances = svc.instanceIds(envId);

        System.out.println("=== eb-migrator · power " + state + " ===");
        System.out.printf("  Environment : %s (%s)%n", envName, envId);
        System.out.printf("  Escalado    : %s%n", svc.isSuspended(asg) ? "DESACTIVADO (ASG suspendido)" : "activo");
        System.out.printf("  Instancias  : %s%n", instances.isEmpty() ? "(ninguna)" : String.join(", ", instances));

        switch (state) {
            case "scaling-off":
                return scalingOff(asg, asgName);
            case "scaling-on":
                return scalingOn(asgName);
            case "stop":
                return stop(asgName, instances);
            case "start":
                return start(instances);
            case "reboot":
                return reboot(instances);
            case "terminate-instance":
                return terminateInstance(asg, instances);
            default:
                return 1; // inalcanzable
        }
    }

    /** Traduce alias y normaliza el estado a su forma canónica; null si no es válido. */
    private static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "scaling-off":
            case "suspend":
                return "scaling-off";
            case "scaling-on":
            case "manage":
            case "resume":
                return "scaling-on";
            case "stop":
            case "off":
                return "stop";
            case "start":
            case "on":
                return "start";
            case "reboot":
            case "restart":
                return "reboot";
            case "terminate-instance":
            case "kill":
                return "terminate-instance";
            default:
                return null;
        }
    }

    // --- Operaciones de ESCALADO (no requieren EC2) ---

    private int scalingOff(AutoScalingGroup asg, String asgName) {
        if (svc.isSuspended(asg)) {
            System.out.println("ⓘ  El escalado ya estaba desactivado (ASG suspendido). Nada que hacer.");
            return 0;
        }
        svc.suspend(asgName);
        System.out.println("✅ Escalado DESACTIVADO: el ASG no repondrá ni auto-sanará la instancia.");
        System.out.println("   Ahora puedes apagar/encender a voluntad:");
        System.out.println("   eb-migrator power --env <NOMBRE> --state stop   (apagar, requiere --profile default)");
        System.out.println("   eb-migrator power --env <NOMBRE> --state start  (encender)");
        return 0;
    }

    private int scalingOn(String asgName) {
        svc.resume(asgName);
        System.out.println("✅ Escalado REACTIVADO: EB vuelve a gestionar el environment (auto-sanado).");
        System.out.println("ⓘ  Si la instancia estaba apagada, EB la repondrá/encenderá.");
        return 0;
    }

    // --- Operaciones de INSTANCIA (requieren permisos EC2) ---

    private int stop(String asgName, List<String> instances) {
        System.out.println("Se DESACTIVARÁ el escalado y se APAGARÁ la instancia (la app dejará de responder).");
        if (!Cli.confirm("¿Apagar este environment?", assumeYes)) {
            System.out.println("Cancelado.");
            return 1;
        }
        // Suspender primero: si no, el ASG repone/reemplaza la instancia apagada.
        svc.suspend(asgName);
        System.out.println("✅ Escalado desactivado (no repondrá la instancia).");
        try {
            svc.stop(instances);
            System.out.println("✅ Instancia apagándose (stop). El disco EBS se conserva.");
        } catch (AwsServiceException e) {
            return reportEc2AuthError("apagar", e);
        }
        return 0;
    }

    private int start(List<String> instances) {
        try {
            svc.start(instances);
            System.out.println("✅ Instancia encendiéndose (start).");
        } catch (AwsServiceException e) {
            return reportEc2AuthError("encender", e);
        }
        System.out.println("ⓘ  El escalado sigue desactivado (gestión manual). Para devolver el auto-sanado a EB:");
        System.out.println("   eb-migrator power --env <NOMBRE> --state scaling-on");
        return 0;
    }

    private int reboot(List<String> instances) {
        if (instances.isEmpty()) {
            System.out.println("No hay instancias que reiniciar (¿está apagado el environment?).");
            return 1;
        }
        if (!Cli.confirm("¿Reiniciar la instancia? (breve corte del servicio)", assumeYes)) {
            System.out.println("Cancelado.");
            return 1;
        }
        try {
            svc.reboot(instances);
            System.out.println("✅ Instancia reiniciándose (reboot). El escalado no se toca.");
        } catch (AwsServiceException e) {
            return reportEc2AuthError("reiniciar", e);
        }
        return 0;
    }

    private int terminateInstance(AutoScalingGroup asg, List<String> instances) {
        if (instances.isEmpty()) {
            System.out.println("No hay instancias que terminar.");
            return 1;
        }
        System.out.println("⚠ Esto DESTRUYE la(s) instancia(s) EC2 (se pierde el disco EBS de la instancia).");
        if (svc.isSuspended(asg)) {
            System.out.println("  El escalado está desactivado → el environment quedará SIN instancia.");
            System.out.println("  (Para volver a tener instancia: --state scaling-on, y EB la repone.)");
        } else {
            System.out.println("  El escalado está ACTIVO → EB lanzará una instancia nueva (reciclado).");
        }
        System.out.println("  Nota: esto NO da de baja el environment. Para eso usa el comando 'terminate'.");
        if (!Cli.confirm("¿Terminar la(s) instancia(s) " + String.join(", ", instances) + "?", assumeYes)) {
            System.out.println("Cancelado.");
            return 1;
        }
        try {
            svc.terminateInstances(instances);
            System.out.println("✅ Instancia(s) terminándose.");
        } catch (AwsServiceException e) {
            return reportEc2AuthError("terminar", e);
        }
        return 0;
    }

    /** Mensaje claro cuando el perfil no tiene permisos EC2 (típico con eb-manager). */
    private int reportEc2AuthError(String accion, AwsServiceException e) {
        boolean authIssue = e.statusCode() == 403
                || (e.awsErrorDetails() != null
                && e.awsErrorDetails().errorCode() != null
                && e.awsErrorDetails().errorCode().toLowerCase(Locale.ROOT).contains("unauthorized"));
        if (authIssue) {
            System.err.println("✗ El perfil actual no puede " + accion + " instancias EC2 (faltan permisos EC2).");
            System.err.println("  Reintenta la operación con un perfil admin:  eb-migrator power ... --profile default");
            return 2;
        }
        System.err.println("✗ Error EC2 al " + accion + ": " + e.getMessage());
        return 3;
    }
}
