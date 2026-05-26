package com.neuvem.java2graph.models;

import java.util.ArrayList;
import java.util.List;

public class FieldNode {
    private String id;
    private String fqn;
    private String name;
    private String typeFqn;
    private String containingClassFqn;
    private List<AnnotationInfo> annotations;
    private String filePath;

    public FieldNode() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFqn() { return fqn; }
    public void setFqn(String fqn) { this.fqn = fqn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTypeFqn() { return typeFqn; }
    public void setTypeFqn(String typeFqn) { this.typeFqn = typeFqn; }

    public String getContainingClassFqn() { return containingClassFqn; }
    public void setContainingClassFqn(String containingClassFqn) { this.containingClassFqn = containingClassFqn; }

    public List<AnnotationInfo> getAnnotations() {
        if (annotations == null) annotations = new ArrayList<>();
        return annotations;
    }
    public void setAnnotations(List<AnnotationInfo> annotations) { this.annotations = annotations; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private FieldNode node = new FieldNode();
        public Builder id(String id) { node.id = id; return this; }
        public Builder fqn(String fqn) { node.fqn = fqn; return this; }
        public Builder name(String name) { node.name = name; return this; }
        public Builder typeFqn(String typeFqn) { node.typeFqn = typeFqn; return this; }
        public Builder containingClassFqn(String containingClassFqn) { node.containingClassFqn = containingClassFqn; return this; }
        public Builder annotations(List<AnnotationInfo> annotations) { node.annotations = annotations; return this; }
        public Builder filePath(String filePath) { node.filePath = filePath; return this; }
        public FieldNode build() { return node; }
    }
}
