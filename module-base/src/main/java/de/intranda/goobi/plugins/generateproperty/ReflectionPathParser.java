package de.intranda.goobi.plugins.generateproperty;

import java.lang.reflect.Method;
import java.util.List;

public class ReflectionPathParser {
    public static String parse(Object root, String expression) throws Exception {
        Object current = root;

        // 1. Aufteilen am Punkt, z.B. ["schritte[0]", "bearbeitungsbenutzer", "standort"]
        String[] parts = expression.split("\\.");

        for (String part : parts) {
            // Prüfen ob Indexierung vorhanden: z.B. schritte[0]
            if (part.contains("[") && part.contains("]")) {
                String property = part.substring(0, part.indexOf('['));
                int index = Integer.parseInt(part.substring(part.indexOf('[') + 1, part.indexOf(']')));

                current = callGetter(current, property);

                if (current instanceof List<?> list) {
                    current = list.get(index);
                } else {
                    throw new IllegalArgumentException("Property " + property + " is not a List");
                }
            } else {
                // normaler Getter-Aufruf
                current = callGetter(current, part);
            }

            if (current == null) {
                return null; // oder throw Exception je nach Wunsch
            }
        }

        return current.toString();
    }

    private static Object callGetter(Object obj, String property) throws Exception {
        // Build getter name: getProperty (capitalize first letter)
        String methodName = "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        Method method = obj.getClass().getMethod(methodName);
        return method.invoke(obj);
    }
}
