/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.tools;

import io.github.polymcreborn.api.annotation.Experimental;
import io.github.polymcreborn.api.annotation.Internal;
import io.github.polymcreborn.api.annotation.Stable;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Build-only deterministic reflection signature generator. */
public final class ApiSignatureGenerator {
    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "io.github.polymcreborn.api.",
            "io.github.theepicblock.polymc.api.",
            "io.github.theepicblock.polymc.impl.misc.logging.");

    private ApiSignatureGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--verify".equals(args[0])) {
            verify(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: <output> <classes-directory> <version>"
                    + " or --verify <generated> <baseline>");
        }
        Path output = Path.of(args[0]);
        byte[] signature = generate(Path.of(args[1]), args[2]);
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.write(output, signature);
    }

    public static byte[] generate(Path classesDirectory, String version) throws Exception {
        Path root = classesDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("API classes directory does not exist: " + root);
        }
        List<String> classNames;
        try (var files = Files.walk(root)) {
            classNames = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .map(root::relativize)
                    .map(Path::toString)
                    .map(name -> name.substring(0, name.length() - ".class".length())
                            .replace('\\', '.').replace('/', '.'))
                    .filter(ApiSignatureGenerator::isPublicPackage)
                    .filter(name -> !name.endsWith("package-info") && !name.endsWith("module-info"))
                    .sorted()
                    .toList();
        }

        List<String> signatures = new ArrayList<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (String className : classNames) {
            Class<?> type = Class.forName(className, false, loader);
            if (isVisible(type.getModifiers()) && !isInternal(type)) {
                appendType(type, signatures);
            }
        }
        signatures.sort(String::compareTo);
        StringBuilder output = new StringBuilder()
                .append("# PolyMc Reborn public API signature\n")
                .append("schema=1\n")
                .append("version=").append(version).append('\n')
                .append("minecraft=26.1.2\n")
                .append("namespace=official\n\n");
        signatures.forEach(line -> output.append(line).append('\n'));
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static void verify(Path generated, Path baseline) throws IOException {
        byte[] actual = Files.readAllBytes(generated);
        byte[] expected = Files.readAllBytes(baseline);
        if (!java.security.MessageDigest.isEqual(actual, expected)) {
            throw new IllegalStateException("Public API signature differs from accepted baseline: generated="
                    + sha256(actual) + ", baseline=" + sha256(expected));
        }
    }

    private static void appendType(Class<?> type, List<String> lines) {
        String stability = stability(type);
        String kind = type.isAnnotation() ? "annotation"
                : type.isEnum() ? "enum"
                : type.isRecord() ? "record"
                : type.isInterface() ? "interface" : "class";
        StringBuilder header = new StringBuilder("TYPE ").append(stability).append(' ')
                .append(kind).append(' ').append(typeName(type));
        appendTypeParameters(header, type.getTypeParameters());
        Type superclass = type.getGenericSuperclass();
        if (superclass != null && superclass != Object.class && !type.isEnum()
                && !type.isRecord()) {
            header.append(" extends ").append(typeName(superclass));
        }
        List<String> interfaces = Arrays.stream(type.getGenericInterfaces())
                .map(ApiSignatureGenerator::typeName).sorted().toList();
        if (!interfaces.isEmpty()) {
            header.append(type.isInterface() ? " extends " : " implements ")
                    .append(String.join(",", interfaces));
        }
        lines.add(header.toString());

        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                lines.add("RECORD_COMPONENT " + typeName(type) + ' ' + component.getName()
                        + ':' + typeName(component.getGenericType()));
            }
        }
        Arrays.stream(type.getDeclaredFields())
                .filter(field -> isVisible(field.getModifiers()) && !field.isSynthetic())
                .filter(field -> !isInternal(field))
                .map(ApiSignatureGenerator::fieldSignature).sorted().forEach(lines::add);
        Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> isVisible(constructor.getModifiers()) && !constructor.isSynthetic())
                .filter(constructor -> !isInternal(constructor))
                .map(ApiSignatureGenerator::constructorSignature).sorted().forEach(lines::add);
        Arrays.stream(type.getDeclaredMethods())
                .filter(method -> isVisible(method.getModifiers()) && !method.isSynthetic()
                        && !method.isBridge())
                .filter(method -> !isInternal(method))
                .map(ApiSignatureGenerator::methodSignature).sorted().forEach(lines::add);
    }

    private static String fieldSignature(Field field) {
        return "FIELD " + stability(field) + ' ' + visibility(field.getModifiers()) + ' '
                + typeName(field.getDeclaringClass()) + '#' + field.getName() + ':'
                + typeName(field.getGenericType());
    }

    private static String constructorSignature(Constructor<?> constructor) {
        StringBuilder output = new StringBuilder("CONSTRUCTOR ")
                .append(stability(constructor)).append(' ')
                .append(visibility(constructor.getModifiers())).append(' ')
                .append(typeName(constructor.getDeclaringClass()));
        appendTypeParameters(output, constructor.getTypeParameters());
        appendParametersAndThrows(output, constructor.getGenericParameterTypes(),
                constructor.getGenericExceptionTypes());
        return output.toString();
    }

    private static String methodSignature(Method method) {
        StringBuilder output = new StringBuilder("METHOD ")
                .append(stability(method)).append(' ')
                .append(visibility(method.getModifiers()));
        if (method.isDefault()) {
            output.append(" default");
        }
        output.append(' ').append(typeName(method.getDeclaringClass())).append('#')
                .append(method.getName());
        appendTypeParameters(output, method.getTypeParameters());
        appendParametersAndThrows(output, method.getGenericParameterTypes(),
                method.getGenericExceptionTypes());
        output.append(':').append(typeName(method.getGenericReturnType()));
        return output.toString();
    }

    private static void appendParametersAndThrows(StringBuilder output, Type[] parameters,
            Type[] exceptions) {
        output.append('(').append(Arrays.stream(parameters).map(ApiSignatureGenerator::typeName)
                .reduce((left, right) -> left + ',' + right).orElse("")).append(')');
        List<String> thrown = Arrays.stream(exceptions).map(ApiSignatureGenerator::typeName)
                .sorted().toList();
        if (!thrown.isEmpty()) {
            output.append(" throws ").append(String.join(",", thrown));
        }
    }

    private static void appendTypeParameters(StringBuilder output, TypeVariable<?>[] parameters) {
        if (parameters.length == 0) {
            return;
        }
        output.append('<');
        for (int index = 0; index < parameters.length; index++) {
            if (index > 0) {
                output.append(',');
            }
            TypeVariable<?> parameter = parameters[index];
            output.append(parameter.getName());
            List<String> bounds = Arrays.stream(parameter.getBounds())
                    .filter(bound -> bound != Object.class)
                    .map(ApiSignatureGenerator::typeName).sorted().toList();
            if (!bounds.isEmpty()) {
                output.append(" extends ").append(String.join("&", bounds));
            }
        }
        output.append('>');
    }

    private static String stability(AnnotatedElement element) {
        if (hasAnnotation(element, Stable.class)) {
            return "STABLE";
        }
        if (hasAnnotation(element, Experimental.class)) {
            return "EXPERIMENTAL";
        }
        if (element instanceof Member member) {
            return stability(member.getDeclaringClass());
        }
        if (element instanceof Class<?> type && type.getPackage() != null) {
            Package owner = type.getPackage();
            if (owner.isAnnotationPresent(Stable.class)) {
                return "STABLE";
            }
            if (owner.isAnnotationPresent(Experimental.class)) {
                return "EXPERIMENTAL";
            }
            if (type.getName().startsWith("io.github.theepicblock.polymc.")) {
                return "LEGACY_ADAPTED_DEPRECATED";
            }
        }
        return "UNCLASSIFIED";
    }

    private static boolean isInternal(AnnotatedElement element) {
        return hasAnnotation(element, Internal.class)
                || element instanceof Class<?> type && type.getPackage() != null
                && type.getPackage().isAnnotationPresent(Internal.class);
    }

    private static boolean hasAnnotation(AnnotatedElement element,
            Class<? extends Annotation> annotation) {
        return element.isAnnotationPresent(annotation);
    }

    private static boolean isVisible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String visibility(int modifiers) {
        return Modifier.isPublic(modifiers) ? "public" : "protected";
    }

    private static boolean isPublicPackage(String name) {
        return PUBLIC_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private static String typeName(Type type) {
        String name = type instanceof Class<?> clazz ? clazz.getTypeName() : type.getTypeName();
        return name.replace('$', '.').replace(" ", "");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
