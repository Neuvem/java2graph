package com.neuvem.java2graph.models;

import java.util.ArrayList;
import java.util.List;

public class ClassNode {
    private String id;
    private String fqn;
    private String name;
    private boolean isInterface;
    private String declarationCode;
    private String filePath;
    private List<String> annotations;
    private boolean isExternal;

    public ClassNode() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFqn() { return fqn; }
    public void setFqn(String fqn) { this.fqn = fqn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isInterface() { return isInterface; }
    public void setInterface(boolean isInterface) { this.isInterface = isInterface; }

    public String getDeclarationCode() { return declarationCode; }
    public void setDeclarationCode(String declarationCode) { this.declarationCode = declarationCode; }

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
        private ClassNode node = new ClassNode();
        public Builder id(String id) { node.id = id; return this; }
        public Builder fqn(String fqn) { node.fqn = fqn; return this; }
        public Builder name(String name) { node.name = name; return this; }
        public Builder isInterface(boolean isInterface) { node.isInterface = isInterface; return this; }
        public Builder declarationCode(String code) { node.declarationCode = code; return this; }
        public Builder filePath(String filePath) { node.filePath = filePath; return this; }
        public Builder annotations(List<String> annotations) { node.annotations = annotations; return this; }
        public Builder isExternal(boolean isExternal) { node.isExternal = isExternal; return this; }
        public ClassNode build() { return node; }
    }
}
