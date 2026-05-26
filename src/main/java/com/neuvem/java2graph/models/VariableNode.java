package com.neuvem.java2graph.models;

public class VariableNode {
    private String id;
    private String methodFqn;
    private String name;
    private String typeFqn;

    public VariableNode() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMethodFqn() { return methodFqn; }
    public void setMethodFqn(String methodFqn) { this.methodFqn = methodFqn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTypeFqn() { return typeFqn; }
    public void setTypeFqn(String typeFqn) { this.typeFqn = typeFqn; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private VariableNode node = new VariableNode();
        public Builder id(String id) { node.id = id; return this; }
        public Builder methodFqn(String methodFqn) { node.methodFqn = methodFqn; return this; }
        public Builder name(String name) { node.name = name; return this; }
        public Builder typeFqn(String typeFqn) { node.typeFqn = typeFqn; return this; }
        public VariableNode build() { return node; }
    }
}
