package dev.archtelemetry.adapter.java;

import dev.archtelemetry.application.port.DependencyResolver;
import dev.archtelemetry.application.port.ResolvedData;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaDependencyResolver implements DependencyResolver {

    private static final Pattern PACKAGE_DECL = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT_DECL = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;", Pattern.MULTILINE);
    // Method declaration proxy: access modifier(s) + return type + name + '('
    private static final Pattern METHOD_DECL = Pattern.compile(
            "^\\s+(?:(?:public|protected|private|static|final|abstract|synchronized|native)\\s+)+"
            + "[\\w<>\\[\\]]+\\s+\\w+\\s*\\([^;{]*\\)\\s*(?:throws[^{;]+)?\\{",
            Pattern.MULTILINE);

    private final Set<Module> modules;

    public JavaDependencyResolver(Set<Module> modules) {
        this.modules = Set.copyOf(modules);
    }

    @Override
    public ResolvedData resolve(Set<Path> sourceFiles) {
        Set<Dependency> dependencies = new HashSet<>();
        Map<Module, Integer> moduleWmc = new HashMap<>();

        for (Path file : sourceFiles) {
            String source = readFile(file);
            Optional<Module> sourceModule = extractPackage(source).flatMap(this::resolveModuleByPackage);
            if (sourceModule.isEmpty()) continue;

            int methodCount = countMethods(source);
            moduleWmc.merge(sourceModule.get(), methodCount, Integer::sum);

            extractImports(source).stream()
                    .filter(imp -> !imp.startsWith("java.") && !imp.startsWith("javax."))
                    .map(this::resolveModuleByImport)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(target -> !target.equals(sourceModule.get()))
                    .map(target -> new Dependency(sourceModule.get(), target))
                    .forEach(dependencies::add);
        }
        return new ResolvedData(Set.copyOf(dependencies), Map.copyOf(moduleWmc));
    }

    /**
     * Like resolve(), but also captures source file and line number for each dependency.
     * Used by --format ai-feedback and watch mode.
     */
    public ResolvedDataWithLocations resolveWithLocations(Set<Path> sourceFiles) {
        Set<Dependency> dependencies = new HashSet<>();
        Map<Module, Integer> moduleWmc = new HashMap<>();
        List<LocatedDependency> located = new ArrayList<>();

        for (Path file : sourceFiles) {
            String source = readFile(file);
            Optional<Module> sourceModule = extractPackage(source).flatMap(this::resolveModuleByPackage);
            if (sourceModule.isEmpty()) continue;

            int methodCount = countMethods(source);
            moduleWmc.merge(sourceModule.get(), methodCount, Integer::sum);

            for (Map.Entry<String, Integer> entry : extractImportsWithLines(source)) {
                String imp = entry.getKey();
                int lineNum = entry.getValue();
                if (imp.startsWith("java.") || imp.startsWith("javax.")) continue;
                resolveModuleByImport(imp).ifPresent(target -> {
                    if (!target.equals(sourceModule.get())) {
                        Dependency dep = new Dependency(sourceModule.get(), target);
                        dependencies.add(dep);
                        located.add(new LocatedDependency(dep, file, lineNum, imp));
                    }
                });
            }
        }

        var data = new dev.archtelemetry.application.port.ResolvedData(
                Set.copyOf(dependencies), Map.copyOf(moduleWmc));
        return new ResolvedDataWithLocations(data, List.copyOf(located));
    }

    private List<Map.Entry<String, Integer>> extractImportsWithLines(String source) {
        List<Map.Entry<String, Integer>> result = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = IMPORT_DECL.matcher(lines[i]);
            if (m.find()) {
                result.add(Map.entry(m.group(1), i + 1));
            }
        }
        return result;
    }

    private int countMethods(String source) {
        Matcher m = METHOD_DECL.matcher(source);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<String> extractPackage(String source) {
        Matcher m = PACKAGE_DECL.matcher(source);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private Set<String> extractImports(String source) {
        Set<String> imports = new HashSet<>();
        Matcher m = IMPORT_DECL.matcher(source);
        while (m.find()) {
            imports.add(m.group(1));
        }
        return imports;
    }

    private Optional<Module> resolveModuleByPackage(String packageName) {
        return modules.stream()
                .filter(m -> m.packagePatterns().stream()
                        .anyMatch(p -> packageMatchesPattern(packageName, p)))
                .findFirst();
    }

    private Optional<Module> resolveModuleByImport(String importFqn) {
        return modules.stream()
                .filter(m -> m.packagePatterns().stream()
                        .anyMatch(p -> importMatchesPattern(importFqn, p)))
                .findFirst();
    }

    private boolean packageMatchesPattern(String packageName, String pattern) {
        if (pattern.endsWith(".**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
        }
        return packageName.equals(pattern);
    }

    private boolean importMatchesPattern(String importFqn, String pattern) {
        if (pattern.endsWith(".**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return importFqn.startsWith(prefix + ".");
        }
        return importFqn.startsWith(pattern + ".");
    }
}
