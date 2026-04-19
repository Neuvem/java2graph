package com.neuvem.java2graph.util;

/**
 * Tracer to keep track of the current resolution context.
 * Note: Uses volatile static fields because we only run one resolution thread at a time
 * and need to access the last state from the main thread during timeouts.
 */
public class ResolutionTracer {
    private static volatile String lastSymbol;
    private static volatile String lastNode;

    public static void setLastSymbol(String symbol) {
        lastSymbol = symbol;
    }

    public static String getLastSymbol() {
        return lastSymbol;
    }

    public static void setLastNode(String node) {
        lastNode = node;
    }

    public static String getLastNode() {
        return lastNode;
    }

    public static void reset() {
        lastSymbol = null;
        lastNode = null;
    }
}
