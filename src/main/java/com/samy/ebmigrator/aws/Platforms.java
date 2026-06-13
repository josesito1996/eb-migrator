package com.samy.ebmigrator.aws;

import java.util.Locale;

/**
 * Heurísticas para clasificar plataformas (solution stacks) de Elastic Beanstalk
 * y saber si están deprecadas.
 */
public final class Platforms {

    private Platforms() {
    }

    /**
     * ¿El solution stack corre sobre Amazon Linux 2 (deprecado)?
     * Distingue AL2 de AL2023 (que NO está deprecado).
     */
    public static boolean isAmazonLinux2(String solutionStack) {
        if (solutionStack == null) return false;
        String s = solutionStack.toLowerCase(Locale.ROOT);
        return s.contains("amazon linux 2") && !s.contains("amazon linux 2023");
    }

    /** ¿La rama de plataforma está retirada (p. ej. Node.js 14)? */
    public static boolean isRetiredBranch(String solutionStack) {
        if (solutionStack == null) return false;
        String s = solutionStack.toLowerCase(Locale.ROOT);
        return s.contains("node.js 14") || s.contains("node.js 12") || s.contains("node.js 10");
    }

    /** Etiqueta corta del estado de la plataforma para el informe de auditoría. */
    public static String status(String solutionStack) {
        if (isRetiredBranch(solutionStack)) return "RETIRADA";
        if (isAmazonLinux2(solutionStack)) return "DEPRECADA (AL2)";
        return "OK";
    }
}
