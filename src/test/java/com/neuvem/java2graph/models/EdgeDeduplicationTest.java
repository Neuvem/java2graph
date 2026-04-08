package com.neuvem.java2graph.models;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeDeduplicationTest {

    @Test
    void testMethodCallEdgeDeduplication() {
        Set<MethodCallEdge> callEdges = ConcurrentHashMap.newKeySet();

        MethodCallEdge edge1 = MethodCallEdge.builder()
                .callerMethodFqn("com.example.A.foo()")
                .calledMethodFqn("com.example.B.bar()")
                .build();

        MethodCallEdge edge2 = MethodCallEdge.builder()
                .callerMethodFqn("com.example.A.foo()")
                .calledMethodFqn("com.example.B.bar()")
                .build();

        // Ensure structural equality contract is honored
        assertThat(edge1).isEqualTo(edge2);
        assertThat(edge1.hashCode()).isEqualTo(edge2.hashCode());

        callEdges.add(edge1);
        callEdges.add(edge2); // Should be ignored

        // Globally deduplicated
        assertThat(callEdges).hasSize(1);
    }

    @Test
    void testInheritanceEdgeDeduplication() {
        Set<InheritanceEdge> inheritanceEdges = ConcurrentHashMap.newKeySet();

        InheritanceEdge edge1 = InheritanceEdge.builder()
                .childFqn("com.example.SpecialList")
                .parentFqn("java.util.List")
                .type("IMPLEMENTS")
                .build();

        InheritanceEdge edge2 = InheritanceEdge.builder()
                .childFqn("com.example.SpecialList")
                .parentFqn("java.util.List")
                .type("IMPLEMENTS")
                .build();

        assertThat(edge1).isEqualTo(edge2);
        assertThat(edge1.hashCode()).isEqualTo(edge2.hashCode());

        inheritanceEdges.add(edge1);
        inheritanceEdges.add(edge2); // Should be ignored

        assertThat(inheritanceEdges).hasSize(1);
    }
}
