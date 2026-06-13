package com.samy.ebmigrator.aws;

import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.ec2.model.Instance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Operaciones sobre el Auto Scaling Group y las instancias EC2 de un environment de EB.
 *
 * Cada environment de Elastic Beanstalk (incluso SingleInstance) tiene un ASG con
 * Min=Max=Desired=1 que "auto-sana": si apagas la instancia, la vuelve a lanzar.
 * Este servicio permite **suspender** esa gestión para apagar a voluntad, y
 * **reanudarla** para devolver el control a EB.
 *
 * Nota de permisos: suspender/reanudar el ASG funciona con el perfil de EB
 * ({@code eb-manager}). Apagar/encender instancias requiere {@code ec2:Stop/StartInstances},
 * que ese perfil de mínimo privilegio NO tiene → usa un perfil con permisos EC2
 * (p. ej. {@code --profile default}).
 */
public final class PowerService {

    private final AwsClients aws;

    public PowerService(AwsClients aws) {
        this.aws = aws;
    }

    /** Resuelve el EnvironmentId a partir del nombre (env activo). */
    public String resolveEnvId(String envName) {
        List<software.amazon.awssdk.services.elasticbeanstalk.model.EnvironmentDescription> envs = aws.eb()
                .describeEnvironments(b -> b.environmentNames(envName).includeDeleted(false))
                .environments();
        if (envs.isEmpty()) {
            throw new IllegalArgumentException("No existe el environment '" + envName + "' (activo).");
        }
        return envs.get(0).environmentId();
    }

    /** Localiza el Auto Scaling Group del environment por su tag {@code elasticbeanstalk:environment-id}. */
    public AutoScalingGroup findAsg(String envId) {
        List<AutoScalingGroup> groups = aws.autoScaling().describeAutoScalingGroups(b -> b.filters(
                        software.amazon.awssdk.services.autoscaling.model.Filter.builder()
                                .name("tag:elasticbeanstalk:environment-id")
                                .values(envId)
                                .build()))
                .autoScalingGroups();
        if (groups.isEmpty()) {
            throw new IllegalStateException("No se encontró Auto Scaling Group para el environment " + envId + ".");
        }
        return groups.get(0);
    }

    /** ¿El ASG tiene procesos suspendidos (autoescalado en pausa)? */
    public boolean isSuspended(AutoScalingGroup asg) {
        return !asg.suspendedProcesses().isEmpty();
    }

    /** Suspende TODOS los procesos del ASG: deja de auto-reponer/auto-sanar. */
    public void suspend(String asgName) {
        aws.autoScaling().suspendProcesses(b -> b.autoScalingGroupName(asgName));
    }

    /** Reanuda los procesos del ASG: EB vuelve a gestionar (auto-sanado). */
    public void resume(String asgName) {
        aws.autoScaling().resumeProcesses(b -> b.autoScalingGroupName(asgName));
    }

    /**
     * IDs de las instancias del environment (por tag {@code elasticbeanstalk:environment-id}),
     * incluyendo apagadas, para poder encender/apagar en cualquier estado.
     */
    public List<String> instanceIds(String envId) {
        return aws.ec2().describeInstances(b -> b.filters(
                        software.amazon.awssdk.services.ec2.model.Filter.builder()
                                .name("tag:elasticbeanstalk:environment-id").values(envId).build(),
                        software.amazon.awssdk.services.ec2.model.Filter.builder()
                                .name("instance-state-name")
                                .values("running", "pending", "stopping", "stopped").build()))
                .reservations().stream()
                .flatMap(r -> r.instances().stream())
                .map(Instance::instanceId)
                .collect(Collectors.toList());
    }

    /** Apaga (stop) las instancias indicadas. Requiere {@code ec2:StopInstances}. */
    public void stop(List<String> instanceIds) {
        if (instanceIds.isEmpty()) return;
        aws.ec2().stopInstances(b -> b.instanceIds(instanceIds));
    }

    /** Enciende (start) las instancias indicadas. Requiere {@code ec2:StartInstances}. */
    public void start(List<String> instanceIds) {
        if (instanceIds.isEmpty()) return;
        aws.ec2().startInstances(b -> b.instanceIds(instanceIds));
    }

    /** Reinicia (reboot) las instancias indicadas. Requiere {@code ec2:RebootInstances}. */
    public void reboot(List<String> instanceIds) {
        if (instanceIds.isEmpty()) return;
        aws.ec2().rebootInstances(b -> b.instanceIds(instanceIds));
    }

    /**
     * Instancias EC2 todavía vivas (no terminadas) etiquetadas con este environment.
     * Sirve para detectar "huérfanas" que impiden terminar el environment (retienen el
     * Security Group). Devuelve los IDs.
     */
    public List<String> liveInstanceIds(String envId) {
        return aws.ec2().describeInstances(b -> b.filters(
                        software.amazon.awssdk.services.ec2.model.Filter.builder()
                                .name("tag:elasticbeanstalk:environment-id").values(envId).build(),
                        software.amazon.awssdk.services.ec2.model.Filter.builder()
                                .name("instance-state-name")
                                .values("running", "pending", "stopping", "stopped").build()))
                .reservations().stream()
                .flatMap(r -> r.instances().stream())
                .map(Instance::instanceId)
                .collect(Collectors.toList());
    }

    /**
     * Termina (destruye) las instancias indicadas. Requiere {@code ec2:TerminateInstances}.
     * Se usa para limpiar instancias huérfanas que retienen el Security Group y atascan el
     * terminate del environment (incluso si están {@code stopped}).
     */
    public void terminateInstances(List<String> instanceIds) {
        if (instanceIds.isEmpty()) return;
        aws.ec2().terminateInstances(b -> b.instanceIds(instanceIds));
    }

    /**
     * Espera a que ya no queden instancias vivas del environment (terminadas de verdad),
     * para que el Security Group quede libre antes de reintentar el terminate del environment.
     * Devuelve {@code true} si todas desaparecieron antes del timeout.
     */
    public boolean waitInstancesGone(String envId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (liveInstanceIds(envId).isEmpty()) return true;
            Thread.sleep(10_000);
        }
        return liveInstanceIds(envId).isEmpty();
    }
}
