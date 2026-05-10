package dev.archtelemetry.domain;

public record ModuleMetrics(
        Module module,
        int fanIn,
        int fanOut,
        double instability,
        double abstractness,
        double distanceFromMainSequence
) {
    public static ModuleMetrics compute(Module module, int fanIn, int fanOut) {
        double instability = (fanIn + fanOut) == 0 ? 0.0 : (double) fanOut / (fanIn + fanOut);
        double abstractness = 0.0;
        double distance = Math.abs(abstractness + instability - 1.0);
        return new ModuleMetrics(module, fanIn, fanOut, instability, abstractness, distance);
    }
}
