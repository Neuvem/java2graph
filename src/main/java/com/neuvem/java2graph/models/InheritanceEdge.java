package com.neuvem.java2graph.models;

public class InheritanceEdge {
    private String childFqn;
    private String parentFqn;
    private String type;

    public InheritanceEdge() {}

    public String getChildFqn() { return childFqn; }
    public void setChildFqn(String childFqn) { this.childFqn = childFqn; }

    public String getParentFqn() { return parentFqn; }
    public void setParentFqn(String parentFqn) { this.parentFqn = parentFqn; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private InheritanceEdge edge = new InheritanceEdge();
        public Builder childFqn(String childFqn) { edge.childFqn = childFqn; return this; }
        public Builder parentFqn(String parentFqn) { edge.parentFqn = parentFqn; return this; }
        public Builder type(String type) { edge.type = type; return this; }
        public InheritanceEdge build() { return edge; }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InheritanceEdge that = (InheritanceEdge) o;
        return java.util.Objects.equals(childFqn, that.childFqn) &&
               java.util.Objects.equals(parentFqn, that.parentFqn) &&
               java.util.Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(childFqn, parentFqn, type);
    }
}
