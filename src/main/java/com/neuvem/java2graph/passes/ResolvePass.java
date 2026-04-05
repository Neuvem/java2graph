package com.neuvem.java2graph.passes;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;

import com.github.javaparser.resolution.types.ResolvedType;
import com.neuvem.java2graph.Java2GraphConfig;
import com.neuvem.java2graph.models.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ResolvePass implements Pass {

    @Override
    public void execute(Java2GraphConfig config, GraphContext context) throws Exception {
        System.out.println("Resolving ASTs with Advanced Symbol Resolution...");

        context.compilationUnits.values().parallelStream().forEach(cu -> {
            try {
                cu.accept(new ResolverVisitor(context, cu), null);
            } catch (Throwable e) {
                System.err.println("Failed to resolve compilation unit: " + e.getMessage());
            }
        });

        System.out.println("Adding stub nodes for external references...");
        
        context.inheritanceEdges.forEach(edge -> {
            context.classes.computeIfAbsent(edge.getChildFqn(), fqn -> ClassNode.builder().id(fqn).fqn(fqn).name(fqn).isInterface(false).declarationCode("").build());
            context.classes.computeIfAbsent(edge.getParentFqn(), fqn -> ClassNode.builder().id(fqn).fqn(fqn).name(fqn).isInterface(false).declarationCode("").build());
        });

        context.callEdges.forEach(edge -> {
            context.methods.computeIfAbsent(edge.getCallerMethodFqn(), fqn -> MethodNode.builder().id(fqn).fqn(fqn).name(fqn).signature(fqn).sourceCode("").isLambda(false).build());
            context.methods.computeIfAbsent(edge.getCalledMethodFqn(), fqn -> MethodNode.builder().id(fqn).fqn(fqn).name(fqn).signature(fqn).sourceCode("").isLambda(false).build());
        });

        System.out.println("Finished resolving. Classes: " + context.classes.size() +
                ", Methods: " + context.methods.size() +
                ", Inheritances: " + context.inheritanceEdges.size() +
                ", Calls: " + context.callEdges.size());
    }

    private static class ResolverVisitor extends VoidVisitorAdapter<Void> {
        private final GraphContext context;
        /** Simple-name → FQN map built from the CU's import statements. */
        private final Map<String, String> importMap;

        private String currentClassFqn = null;
        private String currentMethodFqn = null;
        /** Dedup set to avoid adding the same caller→callee edge multiple times. */
        private final Set<String> seenEdges = ConcurrentHashMap.newKeySet();
        /** Counter for lambda numbering within each class. */
        private int lambdaCounter = 0;

        public ResolverVisitor(GraphContext context, CompilationUnit cu) {
            this.context = context;
            this.importMap = buildImportMap(cu);
        }

        /**
         * Build a simple-name → FQN map from the compilation unit's import declarations.
         * Only single-type imports (not star imports) are used.
         */
        private static Map<String, String> buildImportMap(CompilationUnit cu) {
            Map<String, String> map = new HashMap<>();
            for (ImportDeclaration imp : cu.getImports()) {
                if (!imp.isAsterisk() && !imp.isStatic()) {
                    String fqn = imp.getNameAsString();
                    String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                    map.put(simpleName, fqn);
                }
            }
            return map;
        }

        private void addCall(String calledFqn) {
            if (currentMethodFqn != null && calledFqn != null) {
                String edgeKey = currentMethodFqn + "→" + calledFqn;
                if (seenEdges.add(edgeKey)) {
                    context.callEdges.add(MethodCallEdge.builder()
                            .callerMethodFqn(currentMethodFqn)
                            .calledMethodFqn(calledFqn)
                            .build());
                }
            }
        }

        // ──────────────────────────────────────────────────────────────────────
        //  Type declarations
        // ──────────────────────────────────────────────────────────────────────

        private void handleTypeDeclaration(TypeDeclaration<?> n, boolean isInterface) {
            String fqn = "UNKNOWN";
            try {
                ResolvedReferenceTypeDeclaration resolved = n.resolve();
                fqn = resolved.getQualifiedName();
            } catch (Exception e) {
                fqn = n.getFullyQualifiedName().orElse(
                        (currentClassFqn != null ? currentClassFqn + "." : "") + n.getNameAsString());
            }

            final String finalFqn = fqn;
            context.classes.put(finalFqn, ClassNode.builder()
                    .id(finalFqn).fqn(finalFqn).name(n.getNameAsString())
                    .isInterface(isInterface).declarationCode(n.toString()).build());

            String prevClassFqn = currentClassFqn;
            currentClassFqn = finalFqn;

            if (n instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) n;
                cid.getExtendedTypes().forEach(ext -> addInheritance(finalFqn, ext, "EXTENDS"));
                cid.getImplementedTypes().forEach(impl -> addInheritance(finalFqn, impl, "IMPLEMENTS"));
            } else if (n instanceof EnumDeclaration) {
                context.inheritanceEdges.add(InheritanceEdge.builder().childFqn(finalFqn).parentFqn("java.lang.Enum").type("EXTENDS").build());
            } else if (n instanceof RecordDeclaration) {
                context.inheritanceEdges.add(InheritanceEdge.builder().childFqn(finalFqn).parentFqn("java.lang.Record").type("EXTENDS").build());
            }

            // Synthesize a default constructor if no explicit one exists
            // (Joern generates these from bytecode; we need to match)
            if (!isInterface && n instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) n;
                boolean hasExplicitCtor = cid.getConstructors().size() > 0;
                if (!hasExplicitCtor) {
                    String ctorFqn = finalFqn + "." + n.getNameAsString() + "()";
                    context.methods.computeIfAbsent(ctorFqn, k -> MethodNode.builder()
                            .id(ctorFqn).fqn(ctorFqn).name(n.getNameAsString())
                            .signature("()").sourceCode("// synthetic default constructor")
                            .containingClassFqn(finalFqn).isLambda(false).build());
                }
            }

            for (var member : n.getMembers()) {
                member.accept(this, null);
            }
            currentClassFqn = prevClassFqn;
        }

        private void addInheritance(String childFqn, ClassOrInterfaceType type, String relType) {
            String parentFqn = null;
            try {
                ResolvedType resolved = type.resolve();
                parentFqn = resolved.asReferenceType().getQualifiedName();
            } catch (Exception e) {
                // Fallback: qualify the type name via import map
                parentFqn = qualify(type.getNameAsString());
            }
            if (parentFqn != null) {
                context.inheritanceEdges.add(InheritanceEdge.builder()
                        .childFqn(childFqn)
                        .parentFqn(parentFqn)
                        .type(relType).build());
            }
        }

        @Override public void visit(ClassOrInterfaceDeclaration n, Void arg) { handleTypeDeclaration(n, n.isInterface()); }
        @Override public void visit(EnumDeclaration n, Void arg) { handleTypeDeclaration(n, false); }
        @Override public void visit(AnnotationDeclaration n, Void arg) { handleTypeDeclaration(n, true); }
        @Override public void visit(RecordDeclaration n, Void arg) { handleTypeDeclaration(n, false); }

        // ──────────────────────────────────────────────────────────────────────
        //  Method / constructor / initializer declarations
        // ──────────────────────────────────────────────────────────────────────

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            String fqn;
            try {
                fqn = n.resolve().getQualifiedSignature();
            } catch (Exception e) {
                fqn = (currentClassFqn != null ? currentClassFqn : "") + "." + n.getSignature().asString();
            }
            context.methods.put(fqn, MethodNode.builder()
                    .id(fqn).fqn(fqn).name(n.getNameAsString()).signature(n.getSignature().asString())
                    .sourceCode(n.toString()).containingClassFqn(currentClassFqn).isLambda(false).build());
            String prev = currentMethodFqn;
            currentMethodFqn = fqn;
            super.visit(n, arg);
            currentMethodFqn = prev;
        }

        @Override
        public void visit(ConstructorDeclaration n, Void arg) {
            String fqn;
            try {
                fqn = n.resolve().getQualifiedSignature();
            } catch (Exception e) {
                fqn = (currentClassFqn != null ? currentClassFqn : "") + "." + n.getSignature().asString();
            }
            context.methods.put(fqn, MethodNode.builder()
                    .id(fqn).fqn(fqn).name(n.getNameAsString()).signature(n.getSignature().asString())
                    .sourceCode(n.toString()).containingClassFqn(currentClassFqn).isLambda(false).build());
            String prev = currentMethodFqn;
            currentMethodFqn = fqn;

            boolean hasExplicit = n.getBody().getStatements().stream()
                    .anyMatch(s -> s instanceof ExplicitConstructorInvocationStmt);
            if (!hasExplicit && currentClassFqn != null && !"java.lang.Object".equals(currentClassFqn)) {
                addCall("UNRESOLVED.super()");
            }
            super.visit(n, arg);
            currentMethodFqn = prev;
        }

        @Override
        public void visit(InitializerDeclaration n, Void arg) {
            String fqn = (currentClassFqn != null ? currentClassFqn : "UNKNOWN")
                    + (n.isStatic() ? ".<clinit>" : ".<init>")
                    + "$" + n.getBegin().map(p -> p.line).orElse(0);
            context.methods.put(fqn, MethodNode.builder().id(fqn).fqn(fqn)
                    .name(n.isStatic() ? "<clinit>" : "<init>").signature("()")
                    .sourceCode(n.toString()).containingClassFqn(currentClassFqn).isLambda(false).build());
            String prev = currentMethodFqn;
            currentMethodFqn = fqn;
            super.visit(n, arg);
            currentMethodFqn = prev;
        }

        @Override
        public void visit(FieldDeclaration n, Void arg) {
            // Static fields → <clinit>(), instance fields → <init>()
            // Matches Joern's naming convention (no "-fields" suffix)
            String fqn = (currentClassFqn != null ? currentClassFqn : "UNKNOWN")
                    + (n.isStatic() ? ".<clinit>()" : ".<init>()");
            // Register the clinit/init node if it doesn't exist yet
            context.methods.computeIfAbsent(fqn, k -> MethodNode.builder()
                    .id(fqn).fqn(fqn)
                    .name(n.isStatic() ? "<clinit>" : "<init>")
                    .signature("()")
                    .sourceCode("// field initializers")
                    .containingClassFqn(currentClassFqn)
                    .isLambda(false).build());
            String prev = currentMethodFqn;
            currentMethodFqn = fqn;
            super.visit(n, arg);
            currentMethodFqn = prev;
        }

        @Override
        public void visit(EnumConstantDeclaration n, Void arg) {
            if (currentClassFqn != null) {
                String prev = currentMethodFqn;
                currentMethodFqn = currentClassFqn + ".<clinit>";
                n.getArguments().forEach(a -> a.accept(this, null));
                currentMethodFqn = prev;
            }
            if (n.getClassBody().size() > 0) {
                String anonFqn = (currentClassFqn != null ? currentClassFqn : "UNKNOWN") + "$" + n.getNameAsString();
                String prevCls = currentClassFqn;
                currentClassFqn = anonFqn;
                context.classes.put(anonFqn, ClassNode.builder().id(anonFqn).fqn(anonFqn)
                        .name(n.getNameAsString()).isInterface(false).declarationCode(n.toString()).build());
                n.getClassBody().forEach(member -> member.accept(this, null));
                currentClassFqn = prevCls;
            }
        }

        // ──────────────────────────────────────────────────────────────────────
        //  Lambda expressions
        // ──────────────────────────────────────────────────────────────────────

        @Override
        public void visit(LambdaExpr n, Void arg) {
            if (currentClassFqn == null) {
                super.visit(n, arg);
                return;
            }

            int idx = lambdaCounter++;

            // Try to resolve the functional interface this lambda implements
            String functionalInterface = null;
            try {
                ResolvedType rt = n.calculateResolvedType();
                String desc = rt.describe();
                if (!desc.startsWith("?")) {
                    functionalInterface = desc;
                }
            } catch (Exception ignored) { /* fall through */ }

            // Build a Joern-style lambda FQN: enclosingClass.<lambda>N
            String lambdaFqn = currentClassFqn + ".<lambda>" + idx;

            // Register the lambda as a method node
            context.methods.put(lambdaFqn, MethodNode.builder()
                    .id(lambdaFqn).fqn(lambdaFqn)
                    .name("<lambda>" + idx)
                    .signature("()")
                    .sourceCode(n.toString())
                    .containingClassFqn(currentClassFqn)
                    .isLambda(true)
                    .build());

            // Add inheritance edge: lambda → functional interface
            if (functionalInterface != null) {
                context.inheritanceEdges.add(InheritanceEdge.builder()
                        .childFqn(lambdaFqn)
                        .parentFqn(functionalInterface)
                        .type("IMPLEMENTS").build());
            }

            // Add inbound call edge: enclosing method → lambda
            // (the enclosing method "invokes" or passes the lambda)
            addCall(lambdaFqn);

            // Visit the lambda body with the lambda as the current method
            String prevMethod = currentMethodFqn;
            currentMethodFqn = lambdaFqn;
            super.visit(n, arg);
            currentMethodFqn = prevMethod;
        }

        // ──────────────────────────────────────────────────────────────────────
        //  Method calls
        // ──────────────────────────────────────────────────────────────────────

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            if (currentMethodFqn != null) {
                String calledFqn = null;
                try {
                    // Primary: full symbol resolution
                    ResolvedMethodDeclaration resolved = n.resolve();
                    calledFqn = resolved.getQualifiedSignature();
                } catch (RuntimeException e) {
                    // Secondary: try to resolve just the scope type, or fall back
                    // to <unresolvedNamespace>.methodName(N) matching Joern's convention
                    calledFqn = deduceFqnManually(n);
                }
                addCall(calledFqn);
            }
            super.visit(n, arg);
        }

        /**
         * Build a fallback FQN for an unresolved method call.
         * <ul>
         *   <li>If the scope's type can be determined (cast, symbol solver, import map)
         *       → {@code scopeType.methodName(N)}</li>
         *   <li>Otherwise → {@code <unresolvedNamespace>.methodName(N)} matching Joern</li>
         * </ul>
         */
        private String deduceFqnManually(MethodCallExpr n) {
            String methodName = n.getNameAsString();
            int argCount = n.getArguments().size();

            if (n.getScope().isPresent()) {
                String scopeType = resolveScopeType(n.getScope().get());
                if (scopeType != null) {
                    return scopeType + "." + methodName + "(" + argCount + ")";
                }
            }
            // Scope type is unknowable → Joern-style unresolved
            return "<unresolvedNamespace>." + methodName + "(" + argCount + ")";
        }

        /**
         * Try to determine the concrete type of a scope expression.
         * Returns the FQN of the type, or {@code null} if it cannot be determined.
         * <p>
         * This method is intentionally <b>non-recursive</b>: it does NOT walk into
         * method-call chains (builder patterns). For unresolvable scopes the caller
         * falls back to {@code <unresolvedNamespace>}.
         */
        private String resolveScopeType(Expression scope) {
            // Unwrap parentheses
            Expression unwrapped = scope;
            while (unwrapped.isEnclosedExpr()) {
                unwrapped = unwrapped.asEnclosedExpr().getInner();
            }

            // Cast → use the target type
            if (unwrapped.isCastExpr()) {
                return qualify(unwrapped.asCastExpr().getType().asString());
            }

            // Symbol-solver type resolution (handles resolved names, fields, literals, …)
            try {
                ResolvedType rt = unwrapped.calculateResolvedType();
                String desc = rt.describe();
                // Wildcard types (?, ? extends T, ? super T) → unknowable
                if (!desc.startsWith("?")) {
                    return desc;
                }
            } catch (Exception ignored) { /* fall through */ }

            // Array index access → try to resolve the element type
            if (unwrapped.isArrayAccessExpr()) {
                try {
                    ResolvedType arrType = unwrapped.asArrayAccessExpr()
                            .getName().calculateResolvedType();
                    if (arrType.isArray()) {
                        String comp = arrType.asArrayType().getComponentType().describe();
                        if (!comp.startsWith("?")) {
                            return comp;
                        }
                    }
                } catch (Exception ignored) { /* fall through */ }
            }

            // NameExpr → try import-map qualification first, then AST var-decl lookup
            if (unwrapped.isNameExpr()) {
                String name = unwrapped.asNameExpr().getNameAsString();

                // 1. Check if the name itself is an imported class (static method call)
                String qualified = qualify(name);
                if (!qualified.equals(name)) {
                    return qualified;
                }

                // 2. Walk up the AST to find the variable/parameter declaration
                //    and use its declared type (then qualify via import map)
                String declaredType = findDeclaredType(unwrapped.asNameExpr());
                if (declaredType != null) {
                    return qualify(declaredType);
                }
            }

            // FieldAccessExpr (e.g. ClassName.FIELD) → qualify the scope part
            if (unwrapped.isFieldAccessExpr()) {
                FieldAccessExpr fae = unwrapped.asFieldAccessExpr();
                if (fae.getScope().isNameExpr()) {
                    String q = qualify(fae.getScope().asNameExpr().getNameAsString());
                    return q + "." + fae.getNameAsString();
                }
            }

            // MethodCallExpr scope (e.g. getContext().getConfiguration())
            // → try to resolve the scope method and get its return type
            if (unwrapped.isMethodCallExpr()) {
                try {
                    ResolvedMethodDeclaration scopeMethod = unwrapped.asMethodCallExpr().resolve();
                    String returnType = scopeMethod.getReturnType().describe();
                    if (!returnType.startsWith("?") && !"void".equals(returnType)) {
                        return returnType;
                    }
                } catch (Exception ignored) { /* fall through */ }
            }

            // ObjectCreationExpr (e.g. new Foo().method()) → type is Foo
            if (unwrapped.isObjectCreationExpr()) {
                return qualify(unwrapped.asObjectCreationExpr().getTypeAsString());
            }

            // Cannot determine type → null  (caller will use <unresolvedNamespace>)
            return null;
        }

        /**
         * If {@code simpleName} matches an import in this CU, return its FQN.
         * Otherwise return it unchanged.
         */
        private String qualify(String simpleName) {
            return importMap.getOrDefault(simpleName, simpleName);
        }

        /**
         * Walk up the AST from a {@link NameExpr} to find its variable or parameter
         * declaration, and return the declared type's simple name.
         * Returns {@code null} if the declaration can't be found.
         */
        private String findDeclaredType(NameExpr nameExpr) {
            String varName = nameExpr.getNameAsString();

            // Search for a VariableDeclarator or Parameter in the enclosing callable
            // (MethodDeclaration, ConstructorDeclaration, or InitializerDeclaration)
            com.github.javaparser.ast.Node node = nameExpr;
            while (node.getParentNode().isPresent()) {
                node = node.getParentNode().get();

                // Check if this is a method/constructor — search its body and params
                if (node instanceof MethodDeclaration) {
                    MethodDeclaration md = (MethodDeclaration) node;
                    // Check parameters first
                    for (Parameter p : md.getParameters()) {
                        if (p.getNameAsString().equals(varName)) {
                            return stripGenerics(p.getTypeAsString());
                        }
                    }
                    // Search local variable declarations in the body
                    String found = searchBodyForVar(md, varName);
                    if (found != null) return found;
                    break; // don't look beyond the enclosing method
                }
                if (node instanceof ConstructorDeclaration) {
                    ConstructorDeclaration cd = (ConstructorDeclaration) node;
                    for (Parameter p : cd.getParameters()) {
                        if (p.getNameAsString().equals(varName)) {
                            return stripGenerics(p.getTypeAsString());
                        }
                    }
                    String found = searchBodyForVar(cd, varName);
                    if (found != null) return found;
                    break;
                }

                // For-each variable: for (Type x : ...) { x.method() }
                if (node instanceof ForEachStmt) {
                    ForEachStmt fes = (ForEachStmt) node;
                    if (fes.getVariable().getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals(varName))) {
                        return stripGenerics(fes.getVariable().getCommonType().asString());
                    }
                }
            }

            // Try enclosing class fields as a last resort
            if (currentClassFqn != null) {
                ClassNode classNode = context.classes.get(currentClassFqn);
                if (classNode != null && classNode.getDeclarationCode() != null) {
                    // Lightweight approach: search the compilationUnits for field declarations
                    for (CompilationUnit cu : context.compilationUnits.values()) {
                        for (TypeDeclaration<?> td : cu.getTypes()) {
                            String tdFqn = td.getFullyQualifiedName().orElse("");
                            if (tdFqn.equals(currentClassFqn)) {
                                for (FieldDeclaration fd : td.getFields()) {
                                    for (VariableDeclarator vd : fd.getVariables()) {
                                        if (vd.getNameAsString().equals(varName)) {
                                            return stripGenerics(vd.getTypeAsString());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return null;
        }

        /**
         * Search a node's descendants for a {@link VariableDeclarator} matching
         * the given name and return its declared type.
         */
        private String searchBodyForVar(com.github.javaparser.ast.Node bodyNode, String varName) {
            for (VariableDeclarator vd : bodyNode.findAll(VariableDeclarator.class)) {
                if (vd.getNameAsString().equals(varName)) {
                    return stripGenerics(vd.getTypeAsString());
                }
            }
            return null;
        }

        /**
         * Strip generic type parameters from a type string.
         * "Map&lt;String, Integer&gt;" → "Map", "Logger" → "Logger"
         */
        private static String stripGenerics(String typeStr) {
            int idx = typeStr.indexOf('<');
            return idx >= 0 ? typeStr.substring(0, idx).trim() : typeStr.trim();
        }

        // ──────────────────────────────────────────────────────────────────────
        //  Object creation
        // ──────────────────────────────────────────────────────────────────────

        @Override
        public void visit(ObjectCreationExpr n, Void arg) {
            if (currentMethodFqn != null) {
                String calledFqn;
                try {
                    ResolvedConstructorDeclaration resolved = n.resolve();
                    calledFqn = resolved.getQualifiedSignature();
                } catch (Exception e) {
                    calledFqn = "UNRESOLVED.new." + qualify(n.getTypeAsString())
                            + "(" + n.getArguments().size() + ")";
                }
                addCall(calledFqn);
            }
            if (n.getAnonymousClassBody().isPresent()) {
                String anonFqn = (currentClassFqn != null ? currentClassFqn : "UNKNOWN")
                        + "$anon$" + n.getBegin().map(p -> p.line).orElse(0);
                String prevCls = currentClassFqn;
                currentClassFqn = anonFqn;
                context.classes.put(anonFqn, ClassNode.builder().id(anonFqn).fqn(anonFqn)
                        .name("$anon").isInterface(false).declarationCode(n.toString()).build());
                n.getAnonymousClassBody().get().forEach(member -> member.accept(this, null));
                currentClassFqn = prevCls;
            } else {
                super.visit(n, arg);
            }
        }

        @Override
        public void visit(ExplicitConstructorInvocationStmt n, Void arg) {
            if (currentMethodFqn != null) {
                String calledFqn;
                try {
                    calledFqn = n.resolve().getQualifiedSignature();
                } catch (Exception e) {
                    calledFqn = "UNRESOLVED." + (n.isThis() ? "this" : "super")
                            + "(" + n.getArguments().size() + ")";
                }
                addCall(calledFqn);
            }
            super.visit(n, arg);
        }

        // ──────────────────────────────────────────────────────────────────────
        //  Implicit method calls from language constructs
        // ──────────────────────────────────────────────────────────────────────

        @Override
        public void visit(ForEachStmt n, Void arg) {
            if (currentMethodFqn != null) {
                addCall("<unresolvedNamespace>.iterator()");
                addCall("<unresolvedNamespace>.hasNext()");
                addCall("<unresolvedNamespace>.next()");
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(TryStmt n, Void arg) {
            if (currentMethodFqn != null && n.getResources().size() > 0) {
                n.getResources().forEach(r -> addCall("<unresolvedNamespace>.close()"));
            }
            super.visit(n, arg);
        }
    }
}
