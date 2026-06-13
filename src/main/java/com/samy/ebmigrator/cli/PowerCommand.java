package com.samy.ebmigrator.cli;

import com.samy.ebmigrator.aws.AwsClients;
import com.samy.ebmigrator.aws.PowerService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.util.List;
import java.util.Locale;

/**
 * Comando {@code power}: suspende/reanuda el autoescalado y apaga/enciende a voluntad
 * las instancias de un environment.
 *
 * Estados:
 *   off     → suspende el ASG (deja de auto-reponer) y apaga las instancias (stop).
 *   on      → enciende las instancias (start). El ASG queda suspendido (gestión manual).
 *   manage  → reanuda el ASG: devuelve a EB el auto-sanado (estado normal).
 *
 * Permisos: suspender/reanudar usa el ASG (lo permite {@code eb-manager}); apagar/encender
 * requiere {@code ec2:Stop/StartInstances} → usa {@code --profile default} (admin).
 */
public final class PowerCommand {

    private final PowerService svc;
    private final boolean assumeYes;

    public PowerCommand(AwsClients aws, boolean assumeYes) {
        this.svc = new PowerService(aws);
        this.assumeYes = assumeYes;
    }

    public int run(String envName, String stateRaw) {
        String state = stateRaw == null ? "" : stateRaw.trim().toLowerCase(Locale.ROOT);
        if (!state.equals("off") && !state.equals("on") && !state.equals("manage")) {
            throw new IllegalArgumentException("--state debe ser 'off', 'on' o 'manage' (recibido: '" + stateRaw + "').");
        }

        String envId = svc.resolveEnvId(envName);
        AutoScalingGroup asg = svc.findAsg(envId);
        String asgName = asg.autoScalingGroupName();
        List<String> instances = svc.instanceIds(envId);

        System.out.println("=== eb-migrator · power " + state + " ===");
        System.out.printf("  Environment : %s (%s)%n", envName, envId);
        System.out.printf("  ASG         : %s  [%s]%n", asgName,
                svc.isSuspended(asg) ? "suspendido" : "activo");
        System.out.printf("  Instancias  : %s%n", instances.isEmpty() ? "(ninguna)" : String.join(", ", instances));

        switch (state) {
            case "off":
                return powerOff(asgName, instances);
            case "on":
                return powerOn(asgName, instances);
            case "manage":
                return manage(asgName);
            default:
                return 1; // inalcanzable
        }
    }

    private int powerOff(String asgName, List<String> instances) {
        System.out.println("Se suspenderá el autoescalado y se APAGARÁN las instancias (la app dejará de responder).");
        if (!Cli.confirm("¿Apagar este environment?", assumeYes)) {
            System.out.println("Cancelado.");
            return 1;
        }
        svc.suspend(asgName);
        System.out.println("✅ Autoescalado suspendido (no repondrá instancias).");
        try {
            svc.stop(instances);
            System.out.println("✅ Instancias apagándose (stop). El disco EBS se conserva.");
        } catch (AwsServiceException e) {
            return reportEc2AuthError("apagar", e);
        }
        return 0;
    }

    private int powerOn(String asgName, List<String> instances) {
        try {
            svc.start(instances);
            System.out.println("✅ Instancias encendiéndose (start).");
        } catch (AwsServiceException e) {
            return reportEc2AuthError("encender", e);
        }
        System.out.println("ⓘ  El autoescalado sigue suspendido (gestión manual). Para devolver el auto-sanado a EB:");
        System.out.println("   eb-migrator power --env <NOMBRE> --state manage");
        return 0;
    }

    private int manage(String asgName) {
        svc.resume(asgName);
        System.out.println("✅ Autoescalado reanudado: EB vuelve a gestionar el environment (auto-sanado).");
        return 0;
    }

    /** Mensaje claro cuando el perfil no tiene permisos EC2 (típico con eb-manager). */
    private int reportEc2AuthError(String accion, AwsServiceException e) {
        boolean authIssue = e.statusCode() == 403
                || (e.awsErrorDetails() != null
                && e.awsErrorDetails().errorCode() != null
                && e.awsErrorDetails().errorCode().toLowerCase(Locale.ROOT).contains("unauthorized"));
        if (authIssue) {
            System.err.println("✗ El perfil actual no puede " + accion + " instancias EC2 (falta ec2:Stop/StartInstances).");
            System.err.println("  El autoescalado YA quedó suspendido; reintenta solo el apagado/encendido con un perfil admin:");
            System.err.println("  eb-migrator power ... --profile default");
            return 2;
        }
        System.err.println("✗ Error EC2 al " + accion + ": " + e.getMessage());
        return 3;
    }
}
