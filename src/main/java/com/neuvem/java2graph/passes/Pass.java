package com.neuvem.java2graph.passes;

import com.neuvem.java2graph.Java2GraphConfig;
import com.neuvem.java2graph.models.GraphContext;

public interface Pass {
    default void prepare(Java2GraphConfig config, GraphContext context) throws Exception {}
    void execute(Java2GraphConfig config, GraphContext context) throws Exception;
}
