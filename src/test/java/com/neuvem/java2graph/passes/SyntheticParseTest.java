package com.neuvem.java2graph.passes;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.neuvem.java2graph.Java2GraphConfig;
import com.neuvem.java2graph.models.ClassNode;
import com.neuvem.java2graph.models.GraphContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticParseTest {

    @Test
    void testSyntheticASTResolutionExtractsCorrectEdges() throws Exception {
        // Setup raw synthetic file
        String javaCode = "package com.example;\n" +
                "import java.util.List;\n" +
                "public class Processor implements Runnable {\n" +
                "    private List<String> items;\n" +
                "    public void run() {\n" +
                "        items.add(\"test\");\n" +
                "        this.start();\n" +
                "    }\n" +
                "    public void start() {}\n" +
                "}\n";

        Java2GraphConfig config = Java2GraphConfig.builder()
                .srcDir(Path.of("."))
                .outDbPath(Path.of("dummy.db"))
                .fastResolve(true) // Fast heuristic bypasses heavy JVM Symbol Solvers
                .build();

        GraphContext context = new GraphContext();

        // Parse the synthetic code
        ParserConfiguration parserConfig = new ParserConfiguration();
        parserConfig.setStoreTokens(true);
        JavaParser javaParser = new JavaParser(parserConfig);

        ParseResult<CompilationUnit> result = javaParser.parse(javaCode);
        CompilationUnit cu = result.getResult().orElseThrow();

        // Build classAstIndex for the ResolverVisitor
        Map<String, TypeDeclaration<?>> classAstIndex = new HashMap<>();
        cu.findAll(TypeDeclaration.class).forEach(td -> {
            ((TypeDeclaration<?>) td).getFullyQualifiedName()
                    .ifPresent(fqn -> classAstIndex.put(fqn, (TypeDeclaration<?>) td));
        });

        // Directly invoke the ResolverVisitor (streaming-style, like ParsePass does)
        cu.accept(new ResolvePass.ResolverVisitor(context, config, classAstIndex, cu, "com/example/Processor.java"), null);

        // Add stub nodes (like ParsePass does after streaming)
        ResolvePass.addStubNodes(context);

        // Validations
        assertThat(context.classes).containsKey("com.example.Processor");

        ClassNode processorNode = context.classes.get("com.example.Processor");
        assertThat(processorNode.isInterface()).isFalse();

        assertThat(context.methods).containsKey("com.example.Processor.run()");
        assertThat(context.methods).containsKey("com.example.Processor.start()");

        // Inheritance Verification
        System.out.println("Inheritances:");
        context.inheritanceEdges.forEach(e -> System.out.println(e.getChildFqn() + " -> " + e.getParentFqn()));
        System.out.println("Calls:");
        context.callEdges.forEach(e -> System.out.println(e.getCallerMethodFqn() + " -> " + e.getCalledMethodFqn()));

        boolean implementsRunnable = context.inheritanceEdges.stream()
                .anyMatch(e -> e.getChildFqn().equals("com.example.Processor") &&
                               e.getParentFqn().equals("Runnable") &&
                               e.getType().equals("IMPLEMENTS"));
        assertThat(implementsRunnable).isTrue();

        // Native AST Heuristic deduction
        assertThat(context.callEdges)
                .extracting(com.neuvem.java2graph.models.MethodCallEdge::getCalledMethodFqn)
                .anyMatch(called -> called != null && called.contains("add"));
    }
}
