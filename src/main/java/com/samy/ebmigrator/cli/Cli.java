package com.samy.ebmigrator.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Utilidades de línea de comandos: parseo de flags {@code --clave valor} y confirmaciones.
 */
public final class Cli {

    private static final BufferedReader IN =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private Cli() {
    }

    /**
     * Parsea flags de la forma {@code --clave valor} y banderas booleanas {@code --flag}.
     * Devuelve un mapa; las banderas booleanas quedan con valor "true".
     */
    public static Map<String, String> parseFlags(String[] args, int from) {
        Map<String, String> out = new HashMap<>();
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) continue;
            String key = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.put(key, args[++i]);
            } else {
                out.put(key, "true");
            }
        }
        return out;
    }

    /**
     * Pide un valor por consola mostrando un valor por defecto entre corchetes.
     * Si el usuario pulsa Enter sin escribir nada, devuelve {@code def}.
     */
    public static String prompt(String label, String def) {
        if (def != null && !def.isBlank()) {
            System.out.print(label + " [" + def + "]: ");
        } else {
            System.out.print(label + ": ");
        }
        System.out.flush();
        try {
            String line = IN.readLine();
            if (line == null) return def;
            line = line.trim();
            return line.isEmpty() ? def : line;
        } catch (IOException e) {
            return def;
        }
    }

    /** Pide un valor obligatorio: repite la pregunta hasta que se escriba algo. */
    public static String promptRequired(String label) {
        while (true) {
            String v = prompt(label, null);
            if (v != null && !v.isBlank()) return v;
            System.out.println("  (este dato es obligatorio)");
        }
    }

    /** Confirmación reforzada: el usuario debe teclear exactamente {@code expected}. */
    public static boolean confirmExact(String expected) {
        System.out.printf("Para confirmar, escribe el nombre exacto del environment (%s): ", expected);
        System.out.flush();
        try {
            String line = IN.readLine();
            return line != null && line.trim().equals(expected);
        } catch (IOException e) {
            return false;
        }
    }

    /** Pide confirmación interactiva (a menos que {@code assumeYes} sea true). */
    public static boolean confirm(String prompt, boolean assumeYes) {
        if (assumeYes) {
            System.out.println(prompt + " [auto-sí]");
            return true;
        }
        System.out.print(prompt + " (s/N): ");
        System.out.flush();
        try {
            String line = IN.readLine();
            if (line == null) return false;
            String v = line.trim().toLowerCase(Locale.ROOT);
            return v.equals("s") || v.equals("si") || v.equals("sí") || v.equals("y") || v.equals("yes");
        } catch (IOException e) {
            return false;
        }
    }
}
