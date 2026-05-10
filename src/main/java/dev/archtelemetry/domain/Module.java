package dev.archtelemetry.domain;

import java.util.List;
import java.util.Objects;

public final class Module {

    private final String name;
    private final List<String> packagePatterns;

    public Module(String name) {
        this(name, List.of());
    }

    public Module(String name, List<String> packagePatterns) {
        this.name = Objects.requireNonNull(name);
        this.packagePatterns = List.copyOf(packagePatterns);
    }

    public String name() {
        return name;
    }

    public List<String> packagePatterns() {
        return packagePatterns;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Module m)) return false;
        return name.equals(m.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "Module[name=" + name + "]";
    }
}
