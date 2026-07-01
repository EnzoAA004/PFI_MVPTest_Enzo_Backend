package ar.edu.uade.pfi.backend.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ResponseNormalizer {
    private ResponseNormalizer() {
    }

    public static Map<String, Object> normalizeMap(Map<String, Object> response) {
        if (response == null) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        response.forEach((key, value) -> {
            String normalizedKey = toCamelCase(key);
            normalized.putIfAbsent(normalizedKey, normalizeObject(value));
        });
        return normalized;
    }

    public static Object normalizeObject(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            mapValue.forEach((key, nestedValue) -> {
                if (key != null) {
                    normalized.putIfAbsent(toCamelCase(key.toString()), normalizeObject(nestedValue));
                }
            });
            return normalized;
        }

        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            iterable.forEach(item -> normalized.add(normalizeObject(item)));
            return normalized;
        }

        return value;
    }

    private static String toCamelCase(String key) {
        if (key == null || key.isBlank() || !key.contains("_")) {
            return key;
        }

        StringBuilder camelCase = new StringBuilder();
        boolean uppercaseNext = false;
        for (char character : key.toCharArray()) {
            if (character == '_') {
                uppercaseNext = true;
                continue;
            }
            camelCase.append(uppercaseNext ? Character.toUpperCase(character) : character);
            uppercaseNext = false;
        }
        return camelCase.toString();
    }
}
