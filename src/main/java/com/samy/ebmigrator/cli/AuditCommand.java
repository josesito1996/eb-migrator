package com.samy.ebmigrator.cli;

import com.samy.ebmigrator.aws.AwsClients;
import com.samy.ebmigrator.aws.Platforms;
import software.amazon.awssdk.services.elasticbeanstalk.model.DescribeEnvironmentsResponse;
import software.amazon.awssdk.services.elasticbeanstalk.model.EnvironmentDescription;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Comando READ-ONLY: inventario de environments con su plataforma y salud,
 * marcando los que están deprecados (AL2) o retirados (ramas viejas).
 *
 * No modifica nada en AWS. Es el punto de partida para decidir qué migrar.
 */
public final class AuditCommand {

    private final AwsClients aws;

    public AuditCommand(AwsClients aws) {
        this.aws = aws;
    }

    public int run() {
        DescribeEnvironmentsResponse resp = aws.eb().describeEnvironments(b -> b.includeDeleted(false));
        List<EnvironmentDescription> envs = resp.environments().stream()
                .filter(e -> !"Terminated".equalsIgnoreCase(e.statusAsString()))
                .sorted(Comparator.comparing(EnvironmentDescription::applicationName)
                        .thenComparing(EnvironmentDescription::environmentName))
                .collect(Collectors.toList());

        if (envs.isEmpty()) {
            System.out.println("No se encontraron environments activos.");
            return 0;
        }

        String header = String.format("%-28s %-32s %-9s %-8s %-16s",
                "APLICACIÓN", "ENVIRONMENT", "STATUS", "HEALTH", "PLATAFORMA");
        System.out.println(header);
        System.out.println("-".repeat(header.length() + 30));

        int deprecated = 0;
        for (EnvironmentDescription e : envs) {
            String stack = e.solutionStackName();
            String platStatus = Platforms.status(stack);
            if (!"OK".equals(platStatus)) deprecated++;

            System.out.println(String.format("%-28s %-32s %-9s %-8s %-16s  %s",
                    trunc(e.applicationName(), 28),
                    trunc(e.environmentName(), 32),
                    e.statusAsString(),
                    e.healthAsString(),
                    platStatus,
                    stack));
        }

        System.out.println();
        System.out.printf("Total: %d environments · %d requieren migración (deprecados/retirados).%n",
                envs.size(), deprecated);
        return 0;
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
