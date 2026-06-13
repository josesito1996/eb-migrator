package com.samy.ebmigrator.cli;

import com.samy.ebmigrator.aws.AwsClients;
import com.samy.ebmigrator.aws.MigrateService;
import com.samy.ebmigrator.aws.PowerService;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.elasticbeanstalk.model.EnvironmentDescription;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Comando {@code terminate}: da de baja un environment (el viejo, tras validar el swap).
 * Acción DESTRUCTIVA e irreversible → exige confirmación escribiendo el nombre.
 *
 * Endurecido para producción: antes de terminar **reanuda el ASG** si estaba suspendido
 * (un ASG suspendido no mata su instancia y deja "huérfanas" que retienen el Security
 * Group y atascan el desmontaje). Después **espera a que quede Terminated de verdad** y,
 * si se atasca por instancias huérfanas (incluso {@code stopped}), **las termina
 * automáticamente** (si el perfil tiene permisos EC2) y **reintenta** el terminate.
 * Si no hay permisos EC2, cae al modo anterior: reporta el comando manual.
 */
public final class TerminateCommand {

    private final MigrateService svc;
    private final PowerService power;
    private final boolean assumeYes;

    public TerminateCommand(AwsClients aws, boolean assumeYes) {
        this.svc = new MigrateService(aws);
        this.power = new PowerService(aws);
        this.assumeYes = assumeYes;
    }

    public int run(String envName) throws InterruptedException {
        EnvironmentDescription env = svc.describeEnvironment(envName);
        String envId = env.environmentId();

        System.out.println("=== eb-migrator · terminate (DESTRUCTIVO) ===");
        System.out.printf("  Environment : %s (%s)%n", env.environmentName(), envId);
        System.out.printf("  Plataforma  : %s%n", env.solutionStackName());
        System.out.printf("  CNAME       : %s%n", env.cname());
        System.out.println("Esto elimina la instancia y el rollback. NO se puede deshacer.");

        if (!assumeYes && !Cli.confirmExact(envName)) {
            System.out.println("Cancelado (el nombre no coincide).");
            return 1;
        }
        if (assumeYes) {
            System.out.println("Confirmación omitida por --yes.");
        }

        // Desmontaje limpio: si el autoescalado quedó suspendido (p. ej. de un power off),
        // reanudarlo para que CloudFormation pueda terminar la instancia y no deje huérfanas.
        try {
            AutoScalingGroup asg = power.findAsg(envId);
            if (power.isSuspended(asg)) {
                System.out.println("ⓘ  El autoescalado estaba suspendido → reanudándolo para un desmontaje limpio.");
                power.resume(asg.autoScalingGroupName());
            }
        } catch (RuntimeException e) {
            // Sin ASG (env ya degradado) o sin permisos: seguimos; el terminate lo intenta igual.
        }

        svc.terminate(envName);
        System.out.printf("Terminación de '%s' solicitada. Esperando a que complete…%n", envName);

        boolean done = svc.waitUntilTerminated(envId, Duration.ofMinutes(20));
        if (done) {
            System.out.println("✅ Environment terminado por completo.");
            return 0;
        }

        // No llegó a Terminated → casi siempre por instancias huérfanas que retienen el SG
        // (típico: una instancia 'stopped' de un power off previo, con el ASG ya borrado).
        System.err.println("⚠ La terminación NO completó (el environment sigue vivo / volvió a Ready).");
        List<String> orphans = power.liveInstanceIds(envId);
        if (orphans.isEmpty()) {
            System.err.println("  No se detectaron instancias huérfanas. Revisa los eventos del environment en la consola de EB.");
            return 2;
        }

        System.err.println("  Causa: instancias huérfanas que retienen el Security Group:");
        orphans.forEach(id -> System.err.println("    - " + id));

        // Auto-limpieza: terminar las huérfanas y reintentar el desmontaje.
        System.out.println("Terminando las instancias huérfanas automáticamente…");
        try {
            power.terminateInstances(orphans);
        } catch (Ec2Exception e) {
            if (isUnauthorized(e)) {
                System.err.println("  ✗ El perfil actual no tiene ec2:TerminateInstances.");
                System.err.println("    Termínalas con un perfil admin y reintenta el terminate:");
                System.err.println("    aws ec2 terminate-instances --instance-ids "
                        + String.join(" ", orphans) + " --profile default --region us-east-2");
                return 2;
            }
            throw e;
        }

        System.out.println("Esperando a que las huérfanas desaparezcan (liberan el Security Group)…");
        power.waitInstancesGone(envId, Duration.ofMinutes(5));

        svc.terminate(envName);
        System.out.println("Reintentando la terminación del environment…");
        boolean done2 = svc.waitUntilTerminated(envId, Duration.ofMinutes(20));
        if (done2) {
            System.out.println("✅ Environment terminado por completo (tras limpiar las huérfanas).");
            return 0;
        }

        System.err.println("⚠ Sigue sin completar tras limpiar las huérfanas. Revisa los eventos en la consola de EB.");
        List<String> still = power.liveInstanceIds(envId);
        if (!still.isEmpty()) {
            System.err.println("  Instancias aún vivas: " + String.join(" ", still));
        }
        return 2;
    }

    /** ¿El error de EC2 es por falta de permisos (perfil de mínimo privilegio sin EC2)? */
    private static boolean isUnauthorized(Ec2Exception e) {
        String code = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
        if ("UnauthorizedOperation".equals(code) || "AccessDenied".equals(code)) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("not authorized");
    }
}