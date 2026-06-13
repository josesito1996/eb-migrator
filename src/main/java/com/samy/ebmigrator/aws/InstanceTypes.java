package com.samy.ebmigrator.aws;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mapeo y validación de tipos de instancia EC2 para la migración de familia
 * {@code t2} (burstable, deprecada) a {@code t3}/{@code t3a} (Nitro, más barata).
 *
 * Regla del proyecto (ver CONTEXTO-MIGRACION-EB.md §6):
 *  - Preferir {@code t3a} (AMD, ~10% más barato) sobre {@code t3} (Intel); fallback a t3 si la AZ no tiene t3a.
 *  - JVM/Spring Boot que recibe prod → mínimo 2 GB ({@code t3a.small}); por eso para apps con presión
 *    de memoria se recomienda **subir un tamaño** (drop-in NO basta: t2.micro→t3.micro mantiene 1 GB).
 *  - Node/estáticos y bajo tráfico → drop-in del mismo tamaño basta.
 */
public final class InstanceTypes {

    /** Escalera de tamaños de menor a mayor (familias t2/t3/t3a comparten esta escalera). */
    private static final List<String> SIZES =
            Arrays.asList("nano", "micro", "small", "medium", "large", "xlarge", "2xlarge");

    /** RAM aproximada (GB) por tamaño, para informar al usuario. */
    private static final double[] RAM_GB = {0.5, 1, 2, 4, 8, 16, 32};

    /** {@code <familia>.<tamaño>}, p. ej. "t2.micro", "t3a.small". */
    private static final Pattern TYPE = Pattern.compile("^([a-z]+[0-9]+[a-z]*)\\.([a-z0-9]+)$");

    private InstanceTypes() {
    }

    /** ¿Tiene forma de tipo de instancia EC2 válido ({@code familia.tamaño})? */
    public static boolean isValid(String type) {
        return type != null && TYPE.matcher(type.trim().toLowerCase(Locale.ROOT)).matches();
    }

    /** Familia del tipo, p. ej. "t2" de "t2.micro"; vacío si no se reconoce. */
    public static String family(String type) {
        Matcher m = matcher(type);
        return m == null ? "" : m.group(1);
    }

    /** Tamaño del tipo, p. ej. "micro" de "t2.micro"; vacío si no se reconoce. */
    public static String size(String type) {
        Matcher m = matcher(type);
        return m == null ? "" : m.group(2);
    }

    /** ¿Pertenece a la familia burstable deprecada {@code t2}? */
    public static boolean isT2(String type) {
        return "t2".equals(family(type));
    }

    /** Cambia solo la familia conservando el tamaño, p. ej. ("t2.micro","t3a") → "t3a.micro". */
    public static String withFamily(String type, String newFamily) {
        Matcher m = matcher(type);
        if (m == null) {
            throw new IllegalArgumentException("Tipo de instancia no reconocido: " + type);
        }
        return newFamily + "." + m.group(2);
    }

    /**
     * Sube un escalón de tamaño dentro de la familia indicada, p. ej. ("t2.micro","t3a") → "t3a.small".
     * Si ya está en el máximo de la escalera, devuelve el drop-in del mismo tamaño.
     */
    public static String upsize(String type, String newFamily) {
        String size = size(type);
        int idx = SIZES.indexOf(size);
        int next = (idx >= 0 && idx < SIZES.size() - 1) ? idx + 1 : idx;
        return newFamily + "." + SIZES.get(Math.max(next, 0));
    }

    /** RAM aproximada en GB del tamaño de un tipo; vacío si no se reconoce. */
    public static Optional<Double> ramGb(String type) {
        int idx = SIZES.indexOf(size(type));
        return idx >= 0 ? Optional.of(RAM_GB[idx]) : Optional.empty();
    }

    /** Etiqueta legible con la RAM, p. ej. "t3a.small (2 GB)". */
    public static String describe(String type) {
        return ramGb(type)
                .map(gb -> String.format(Locale.ROOT, "%s (%s GB)", type, trimGb(gb)))
                .orElse(type);
    }

    private static Matcher matcher(String type) {
        if (type == null) return null;
        Matcher m = TYPE.matcher(type.trim().toLowerCase(Locale.ROOT));
        return m.matches() ? m : null;
    }

    private static String trimGb(double gb) {
        return gb == Math.rint(gb) ? String.valueOf((long) gb) : String.valueOf(gb);
    }
}