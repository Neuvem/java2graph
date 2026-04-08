package com.neuvem.java2graph.models;

public class MethodCallEdge {
    private String callerMethodFqn;
    private String calledMethodFqn;

    public MethodCallEdge() {}

    public String getCallerMethodFqn() { return callerMethodFqn; }
    public void setCallerMethodFqn(String callerMethodFqn) { this.callerMethodFqn = callerMethodFqn; }

    public String getCalledMethodFqn() { return calledMethodFqn; }
    public void setCalledMethodFqn(String calledMethodFqn) { this.calledMethodFqn = calledMethodFqn; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private MethodCallEdge edge = new MethodCallEdge();
        public Builder callerMethodFqn(String callerMethodFqn) { edge.callerMethodFqn = callerMethodFqn; return this; }
        public Builder calledMethodFqn(String calledMethodFqn) { edge.calledMethodFqn = calledMethodFqn; return this; }
        public MethodCallEdge build() { return edge; }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodCallEdge that = (MethodCallEdge) o;
        return java.util.Objects.equals(callerMethodFqn, that.callerMethodFqn) &&
               java.util.Objects.equals(calledMethodFqn, that.calledMethodFqn);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(callerMethodFqn, calledMethodFqn);
    }
}
