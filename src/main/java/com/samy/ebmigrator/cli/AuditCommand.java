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

        // Encabezados de cada columna.
        String[] headers = {"APLICACIÓN", "ENVIRONMENT", "STATUS", "HEALTH", "PLATAFORMA", "SOLUTION STACK"};

        // Calculamos el ancho de cada columna a partir del contenido real,
        // de modo que ningún valor se recorte (el más largo manda).
        int[] width = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            width[i] = headers[i].length();
        }
        for (EnvironmentDescription e : envs) {
            width[0] = Math.max(width[0], safe(e.applicationName()).length());
            width[1] = Math.max(width[1], safe(e.environmentName()).length());
            width[2] = Math.max(width[2], safe(e.statusAsString()).length());
            width[3] = Math.max(width[3], safe(e.healthAsString()).length());
            width[4] = Math.max(width[4], Platforms.status(e.solutionStackName()).length());
            width[5] = Math.max(width[5], safe(e.solutionStackName()).length());
        }

        String fmt = String.format("%%-%ds  %%-%ds  %%-%ds  %%-%ds  %%-%ds  %%-%ds%n",
                width[0], width[1], width[2], width[3], width[4], width[5]);

        String header = String.format(fmt,
                (Object[]) headers).stripTrailing();
        System.out.println(header);
        System.out.println("-".repeat(header.length()));

        int deprecated = 0;
        for (EnvironmentDescription e : envs) {
            String stack = e.solutionStackName();
            String platStatus = Platforms.status(stack);
            if (!"OK".equals(platStatus)) deprecated++;

            System.out.print(String.format(fmt,
                    safe(e.applicationName()),
                    safe(e.environmentName()),
                    safe(e.statusAsString()),
                    safe(e.healthAsString()),
                    platStatus,
                    safe(stack)));
        }

        System.out.println();
        System.out.printf("Total: %d environments · %d requieren migración (deprecados/retirados).%n",
                envs.size(), deprecated);
        return 0;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
