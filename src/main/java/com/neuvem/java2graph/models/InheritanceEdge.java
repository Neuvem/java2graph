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
}
