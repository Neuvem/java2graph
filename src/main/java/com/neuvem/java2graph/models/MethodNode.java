package com.neuvem.java2graph.models;

import java.util.ArrayList;
import java.util.List;

public class MethodNode {
    private String id;
    private String fqn;
    private String signature;
    private String name;
    private String sourceCode;
    private String containingClassFqn;
    private boolean isLambda;
    private String filePath;
    private List<String> annotations;
    private boolean isExternal;

    public MethodNode() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFqn() { return fqn; }
    public void setFqn(String fqn) { this.fqn = fqn; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

    public String getContainingClassFqn() { return containingClassFqn; }
    public void setContainingClassFqn(String containingClassFqn) { this.containingClassFqn = containingClassFqn; }

    public boolean isLambda() { return isLambda; }
    public void setLambda(boolean isLambda) { this.isLambda = isLambda; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public List<String> getAnnotations() {
        if (annotations == null) annotations = new ArrayList<>();
        return annotations;
    }
    public void setAnnotations(List<String> annotations) { this.annotations = annotations; }

    public boolean isExternal() { return isExternal; }
    public void setExternal(boolean isExternal) { this.isExternal = isExternal; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private MethodNode node = new MethodNode();
        public Builder id(String id) { node.id = id; return this; }
        public Builder fqn(String fqn) { node.fqn = fqn; return this; }
        public Builder signature(String signature) { node.signature = signature; return this; }
        public Builder name(String name) { node.name = name; return this; }
        public Builder sourceCode(String sourceCode) { node.sourceCode = sourceCode; return this; }
        public Builder containingClassFqn(String containingClassFqn) { node.containingClassFqn = containingClassFqn; return this; }
        public Builder isLambda(boolean isLambda) { node.isLambda = isLambda; return this; }
        public Builder filePath(String filePath) { node.filePath = filePath; return this; }
        public Builder annotations(List<String> annotations) { node.annotations = annotations; return this; }
        public Builder isExternal(boolean isExternal) { node.isExternal = isExternal; return this; }
        public MethodNode build() { return node; }
    }
}
