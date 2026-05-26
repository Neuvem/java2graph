package com.neuvem.java2graph.models;

import com.neuvem.java2graph.util.DecompileCache;
import java.util.concurrent.ConcurrentHashMap;

public class GraphContext {
    public final ConcurrentHashMap<String, ClassNode> classes = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, MethodNode> methods = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, FieldNode> fields = new ConcurrentHashMap<>();
    
    public final java.util.Set<InheritanceEdge> inheritanceEdges = ConcurrentHashMap.newKeySet();
    public final java.util.Set<MethodCallEdge> callEdges = ConcurrentHashMap.newKeySet();
    public final java.util.Set<DependencyEdge> dependencyEdges = ConcurrentHashMap.newKeySet();
    public final ConcurrentHashMap<String, ParameterNode> parameters = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, VariableNode> variables = new ConcurrentHashMap<>();
    public final java.util.Set<DataFlowEdge> dataFlowEdges = ConcurrentHashMap.newKeySet();
    
    // Store the type solver for use across passes (symbol-solver resolution)
    public Object typeSolver;
    public DecompileCache decompileCache;
    public ClassLoader jarClassLoader;
}
