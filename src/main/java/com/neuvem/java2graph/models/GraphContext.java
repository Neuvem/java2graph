package com.neuvem.java2graph.models;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.github.javaparser.ast.CompilationUnit;

public class GraphContext {
    public final ConcurrentHashMap<String, CompilationUnit> compilationUnits = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, ClassNode> classes = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, MethodNode> methods = new ConcurrentHashMap<>();
    
    public final ConcurrentLinkedQueue<InheritanceEdge> inheritanceEdges = new ConcurrentLinkedQueue<>();
    public final ConcurrentLinkedQueue<MethodCallEdge> callEdges = new ConcurrentLinkedQueue<>();
    
    // Store the type solver for use across passes (symbol-solver resolution)
    public Object typeSolver;
}
