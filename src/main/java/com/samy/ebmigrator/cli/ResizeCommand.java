package com.samy.ebmigrator.cli;

import com.samy.ebmigrator.aws.AwsClients;
import com.samy.ebmigrator.aws.InstanceTypes;
import com.samy.ebmigrator.aws.ResizeService;
import software.amazon.awssdk.services.elasticbeanstalk.model.EnvironmentDescription;

import java.time.Duration;
import java.util.Optional;

/**
 * Comando {@code resize}: cambia el TIPO DE INSTANCIA de un environment para migrar la
 * familia {@code t2} (burstable, deprecada) a {@code t3}/{@code t3a} (Nitro, más barata) y/o
 * subir la RAM, reduciendo la presión de memoria / GC de la JVM (ver CONTEXTO §6).
 *
 * Aplica {@code update-environment} sobre {@code aws:ec2:instances/InstanceTypes}: solo toca
 * Elastic Beanstalk, así que funciona con el perfil {@code eb-manager} (no necesita EC2).
 *
 * Si no se indica {@code --to}, sugiere un destino (familia {@code t3a}, subiendo un tamaño)
 * y deja al usuario aceptarlo o teclear otro. En modo no interactivo ({@code --yes}) {@code --to}
 * es obligatorio (no se adivina un cambio que reemplaza la instancia).
 */
public final class ResizeCommand {

    /** Familia destino por defecto: t3a (AMD, ~10% más barato que t3). */
    private static final String DEFAULT_TARGET_FAMILY = "t3a";

    private final ResizeService svc;
    private final boolean assumeYes;

    public ResizeCommand(AwsClients aws, boolean assumeYes) {
        this.svc = new ResizeService(aws);
        this.assumeYes = assumeYes;
    }

    public int run(String envName, String toType) {
        EnvironmentDescription env = svc.describeEnvironment(envName);
        Optional<String> current = svc.currentInstanceType(env);
        boolean single = svc.isSingleInstance(env);

        System.out.println("=== eb-migrator · resize (tipo de instancia) ===");
        System.out.printf("  Environment : %s (%s)%n", env.environmentName(), env.environmentId());
        System.out.printf("  Plataforma  : %s%n", env.solutionStackName());
        System.out.printf("  Tipo actual : %s%n",
                current.map(InstanceTypes::describe).orElse("(desconocido)"));
        System.out.printf("  Capacidad   : %s%n", single ? "SingleInstance (sin balanceador)" : "LoadBalanced");

        // Sugerencias basadas en el tipo actual (si lo conocemos).
        if (current.isPresent() && InstanceTypes.isValid(current.get())) {
            String cur = current.get();
            String dropIn = InstanceTypes.withFamily(cur, DEFAULT_TARGET_FAMILY);
            String upsize = InstanceTypes.upsize(cur, DEFAULT_TARGET_FAMILY);
            System.out.println("  Sugerencias :");
            System.out.printf("     · drop-in (misma RAM)   → %s%n", InstanceTypes.describe(dropIn));
            System.out.printf("     · subir 1 tamaño (+RAM) → %s   ← recomendado para JVM/Spring Boot con presión de memoria%n",
                    InstanceTypes.describe(upsize));
            if (InstanceTypes.isT2(cur)) {
                System.out.println("  ⓘ  La familia t2 es burstable/deprecada; t3/t3a es Nitro y más barata.");
            }
        }

        // Pre-check: si el artefacto de la versión de app actual no existe en S3, el reemplazo de
        // instancia que provoca el resize fallaría con "Unable to download from S3 ... 404 NoSuchKey".
        // Mejor detenerse aquí con una remediación clara que dejar que EB falle el update a medias.
        Optional<String> missing = svc.missingCurrentArtifact(env);
        if (missing.isPresent()) {
            System.out.println();
            System.out.println("✗ No se puede redimensionar: el artefacto de la versión de app actual ("
                    + env.versionLabel() + ") no existe en S3:");
            System.out.println("    " + missing.get());
            System.out.println("  EB necesita ese bundle para lanzar la nueva instancia; al reemplazarla");
            System.out.println("  fallaría con 'Unable to download from S3 … 404 NoSuchKey'.");
            System.out.println("  Suele ocurrir cuando el artefacto vivía bajo el prefijo de un environment");
            System.out.println("  origen ya terminado (EB borra resources/environments/<env-id>/...).");
            System.out.println("  Remedia primero re-desplegando un bundle válido (dispara el CodePipeline, o");
            System.out.println("  registra la versión desde una copia estable) y reintenta el resize.");
            return 1;
        }

        String target = resolveTarget(toType, current);
        if (target == null) {
            return 1; // cancelado o sin destino
        }
        if (!InstanceTypes.isValid(target)) {
            throw new IllegalArgumentException("Tipo de instancia destino no válido: '" + target
                    + "'. Usa la forma familia.tamaño, p. ej. t3a.small.");
        }
        if (current.map(c -> c.equalsIgnoreCase(target)).orElse(false)) {
            System.out.println("ⓘ  El environment ya usa " + target + ". Nada que cambiar.");
            return 0;
        }

        System.out.println();
        System.out.printf("Se cambiará el tipo de instancia a %s.%n", InstanceTypes.describe(target));
        if (single) {
            System.out.println("⚠ Es SingleInstance: EB reemplazará la instancia → BREVE INDISPONIBILIDAD del servicio.");
        }
        if (!Cli.confirm("¿Aplicar el cambio de instancia ahora?", assumeYes)) {
            System.out.println("Cancelado.");
            return 1;
        }

        svc.updateInstanceType(env.environmentName(), target);
        System.out.println("✅ update-environment solicitado (aws:ec2:instances/InstanceTypes=" + target + ").");
        System.out.println("   EB pasará a 'Updating' y reemplazará la instancia.");

        try {
            System.out.println("Esperando a que el environment vuelva a Ready…");
            EnvironmentDescription done = svc.waitUntilReady(env.environmentId(), Duration.ofMinutes(15));
            System.out.printf("✅ Listo: status=%s health=%s%n", done.statusAsString(), done.healthAsString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("✗ Espera interrumpida; verifica el estado con 'audit'.");
            return 130;
        }

        InstanceTypes.ramGb(target).ifPresent(gb ->
                System.out.printf("ⓘ  Nueva RAM ≈ %s GB. Ajusta el heap de la JVM (-Xmx) acorde para aprovecharla.%n",
                        gb == Math.rint(gb) ? String.valueOf(gb.longValue()) : String.valueOf(gb)));
        return 0;
    }

    /**
     * Determina el tipo destino: usa {@code --to} si vino; si no, sugiere subir un tamaño
     * (familia t3a) y lo pide por consola. En modo {@code --yes} sin {@code --to} es error.
     */
    private String resolveTarget(String toType, Optional<String> current) {
        if (toType != null && !toType.isBlank()) {
            return toType.trim();
        }
        if (assumeYes) {
            throw new IllegalArgumentException(
                    "En modo no interactivo (--yes) debes indicar el tipo destino con --to (p. ej. --to t3a.small).");
        }
        String suggestion = current.filter(InstanceTypes::isValid)
                .map(c -> InstanceTypes.upsize(c, DEFAULT_TARGET_FAMILY))
                .orElse("t3a.small");
        String chosen = Cli.prompt("Tipo de instancia destino", suggestion);
        if (chosen == null || chosen.isBlank()) {
            System.out.println("Cancelado (sin tipo destino).");
            return null;
        }
        return chosen.trim();
    }
}