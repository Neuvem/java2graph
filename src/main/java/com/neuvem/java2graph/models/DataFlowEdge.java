package com.neuvem.java2graph.models;

public class DataFlowEdge {
    private String sourceId;
    private String targetId;
    private String flowType;

    public DataFlowEdge(String sourceId, String targetId, String flowType) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.flowType = flowType;
    }

    public String getSourceId() { return sourceId; }
    public String getTargetId() { return targetId; }
    public String getFlowType() { return flowType; }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataFlowEdge that = (DataFlowEdge) o;
        return sourceId.equals(that.sourceId) && targetId.equals(that.targetId) && flowType.equals(that.flowType);
    }
    
    @Override
    public int hashCode() {
        int result = sourceId.hashCode();
        result = 31 * result + targetId.hashCode();
        result = 31 * result + flowType.hashCode();
        return result;
    }
}
