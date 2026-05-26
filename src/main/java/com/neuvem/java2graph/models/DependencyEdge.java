package com.neuvem.java2graph.models;

import java.util.Objects;

public class DependencyEdge {
    private String sourceFqn;
    private String targetFqn;
    private String injectionType;

    public DependencyEdge() {}

    public String getSourceFqn() { return sourceFqn; }
    public void setSourceFqn(String sourceFqn) { this.sourceFqn = sourceFqn; }

    public String getTargetFqn() { return targetFqn; }
    public void setTargetFqn(String targetFqn) { this.targetFqn = targetFqn; }

    public String getInjectionType() { return injectionType; }
    public void setInjectionType(String injectionType) { this.injectionType = injectionType; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DependencyEdge that = (DependencyEdge) o;
        return Objects.equals(sourceFqn, that.sourceFqn) &&
               Objects.equals(targetFqn, that.targetFqn) &&
               Objects.equals(injectionType, that.injectionType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceFqn, targetFqn, injectionType);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private DependencyEdge edge = new DependencyEdge();
        public Builder sourceFqn(String sourceFqn) { edge.sourceFqn = sourceFqn; return this; }
        public Builder targetFqn(String targetFqn) { edge.targetFqn = targetFqn; return this; }
        public Builder injectionType(String injectionType) { edge.injectionType = injectionType; return this; }
        public DependencyEdge build() { return edge; }
    }
}
