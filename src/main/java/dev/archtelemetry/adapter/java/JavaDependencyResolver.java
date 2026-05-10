package dev.archtelemetry.adapter.java;

import dev.archtelemetry.application.port.DependencyResolver;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaDependencyResolver implements DependencyResolver {

    private static final Pattern PACKAGE_DECL = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT_DECL = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;", Pattern.MULTILINE);

    private final Set<Module> modules;

    public JavaDependencyResolver(Set<Module> modules) {
        this.modules = Set.copyOf(modules);
    }

    @Override
    public Set<Dependency> resolve(Set<Path> sourceFiles) {
        Set<Dependency> dependencies = new HashSet<>();
        for (Path file : sourceFiles) {
            String source = readFile(file);
            Optional<Module> sourceModule = extractPackage(source).flatMap(this::resolveModuleByPackage);
            if (sourceModule.isEmpty()) continue;

            extractImports(source).stream()
                    .filter(imp -> !imp.startsWith("java.") && !imp.startsWith("javax."))
                    .map(this::resolveModuleByImport)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(target -> !target.equals(sourceModule.get()))
                    .map(target -> new Dependency(sourceModule.get(), target))
                    .forEach(dependencies::add);
        }
        return Set.copyOf(dependencies);
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
