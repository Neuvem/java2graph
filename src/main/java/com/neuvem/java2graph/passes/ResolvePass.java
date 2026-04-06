package com.neuvem.java2graph.passes;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.stmt.CatchClause;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ResolvePass implements Pass {

    @Override
    public void execute(Java2GraphConfig config, GraphContext context) throws Exception {
        System.out.println("Resolving ASTs with Advanced Symbol Resolution...");

        context.compilationUnits.values().parallelStream().forEach(cu -> {
            try {
                cu.accept(new ResolverVisitor(context, cu), null);
            } catch (Throwable e) {
                System.err.println("Failed to resolve compilation unit: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (e.getMessage() == null) {
                    e.printStackTrace();
                }
            }
        });

        System.out.println("Adding stub nodes for external references...");
        
        context.inheritanceEdges.forEach(edge -> {
            if (edge.getChildFqn() != null && !edge.getChildFqn().isBlank()) {
                context.classes.computeIfAbsent(edge.getChildFqn(), fqn -> ClassNode.builder().id(fqn).fqn(fqn).name(fqn).isInterface(false).declarationCode("").build());
            }
            if (edge.getParentFqn() != null && !edge.getParentFqn().isBlank()) {
                context.classes.computeIfAbsent(edge.getParentFqn(), fqn -> ClassNode.builder().id(fqn).fqn(fqn).name(fqn).isInterface(false).declarationCode("").build());
            }
        });

        context.callEdges.forEach(edge -> {
            if (edge.getCallerMethodFqn() != null && !edge.getCallerMethodFqn().isBlank()) {
                context.methods.computeIfAbsent(edge.getCallerMethodFqn(), fqn -> MethodNode.builder().id(fqn).fqn(fqn).name(fqn).signature(fqn).sourceCode("").isLambda(false).build());
            }
            if (edge.getCalledMethodFqn() != null && !edge.getCalledMethodFqn().isBlank()) {
                context.methods.computeIfAbsent(edge.getCalledMethodFqn(), fqn -> MethodNode.builder().id(fqn).fqn(fqn).name(fqn).signature(fqn).sourceCode("").isLambda(false).build());
            }
        });

        System.out.println("Finished resolving. Classes: " + context.classes.size() +
                ", Methods: " + context.methods.size() +
                ", Inheritances: " + context.inheritanceEdges.size() +
                ", Calls: " + context.callEdges.size());
    }

    private static class ResolverVisitor extends VoidVisitorAdapter<Void> {
        private final GraphContext context;
        /** Simple-name → FQN map for types. */
        private final Map<String, String> importMap;
        /** Method-name → Class FQN map for static method imports. */
        private final Map<String, String> staticImportMap;
        /** List of star-import packages (e.g. "java.util"). */
        private final List<String> starImports;
        /** List of static star-import class FQNs (e.g. "org.junit.Assert"). */
        private final List<String> staticStarImports;

        private String currentClassFqn = null;
        private String currentMethodFqn = null;
        /** Dedup set to avoid adding the same caller→callee edge multiple times. */
        private final Set<String> seenEdges = ConcurrentHashMap.newKeySet();
        /** Counter for lambda numbering within each class. */
        private int lambdaCounter = 0;

        public ResolverVisitor(GraphContext context, CompilationUnit cu) {
            this.context = context;
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            Map<String, Map<String, String>> imports = buildImportMaps(cu, pkg);
            this.importMap = imports.get("types");
            this.staticImportMap = imports.get("static");
            this.starImports = new ArrayList<>(imports.get("stars").keySet());
            this.staticStarImports = new ArrayList<>(imports.get("staticStars").keySet());
        }

        private static Map<String, Map<String, String>> buildImportMaps(CompilationUnit cu, String currentPkg) {
            Map<String, String> types = new HashMap<>();
            Map<String, String> statics = new HashMap<>();
            Map<String, String> stars = new HashMap<>();
            Map<String, String> staticStars = new HashMap<>();
            
            // Implicitly include java.lang and the current package
            stars.put("java.lang", "java.lang");
            if (!currentPkg.isEmpty()) {
                stars.put(currentPkg, currentPkg);
            }

            for (ImportDeclaration imp : cu.getImports()) {
                String fqn = imp.getNameAsString();
                if (imp.isAsterisk()) {
                    if (imp.isStatic()) {
                        staticStars.put(fqn, fqn);
                    } else {
                        stars.put(fqn, fqn);
                    }
                } else {
                    String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                    if (imp.isStatic()) {
                        statics.put(simpleName, fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : fqn);
                    } else {
                        types.put(simpleName, fqn);
                    }
                }
            }
            Map<String, Map<String, String>> result = new HashMap<>();
            result.put("types", types);
            result.put("static", statics);
            result.put("stars", stars);
            result.put("staticStars", staticStars);
            return result;
        }

        private void addCall(String calledFqn) {
            if (currentMethodFqn != null && calledFqn != null) {
                try {
                    // Ensure every call target has a Method node in our registry (parity with Joern)
                    context.methods.computeIfAbsent(calledFqn, k -> {
                        String workingFqn = k;
                        String baseFqn = workingFqn;
                        String signature = "()";
                        if (workingFqn.contains("(")) {
                            baseFqn = workingFqn.substring(0, workingFqn.indexOf('('));
                            signature = workingFqn.substring(workingFqn.indexOf('('));
                        }
                        
                        String classFqn = "UNKNOWN";
                        String name = baseFqn;
                        if (baseFqn.startsWith("<unresolvedNamespace>.")) {
                            classFqn = "<unresolvedNamespace>";
                            name = baseFqn.substring("<unresolvedNamespace>.".length());
                        } else if (baseFqn.startsWith(".")) {
                            // Fix for dot-prefixed methods: redirect to unresolvedNamespace
                            classFqn = "<unresolvedNamespace>";
                            name = baseFqn.substring(1);
                            workingFqn = classFqn + "." + name + signature;
                        } else if (baseFqn.contains(".")) {
                            classFqn = baseFqn.substring(0, baseFqn.lastIndexOf('.'));
                            name = baseFqn.substring(baseFqn.lastIndexOf('.') + 1);
                        }
                        
                        // Register a placeholder ClassNode for the containing class if it doesn't exist
                        final String finalClassFqn = classFqn;
                        context.classes.computeIfAbsent(finalClassFqn, cfqn -> ClassNode.builder()
                                .id(cfqn).fqn(cfqn).name(cfqn.contains(".") ? cfqn.substring(cfqn.lastIndexOf('.') + 1) : cfqn)
                                .isInterface(false).declarationCode("// referenced external/synthetic class")
                                .build());

                        return MethodNode.builder()
                                .id(workingFqn).fqn(workingFqn)
                                .name(name)
                                .signature(signature)
                                .sourceCode("// referenced external/synthetic method")
                                .containingClassFqn(finalClassFqn)
                                .isLambda(workingFqn.contains("<lambda>"))
                                .build();
                    });

                    String edgeKey = currentMethodFqn + "→" + calledFqn;
                    if (seenEdges.add(edgeKey)) {
                        context.callEdges.add(MethodCallEdge.builder()
                                .callerMethodFqn(currentMethodFqn)
                                .calledMethodFqn(calledFqn)
                                .build());
                    }
                } catch (Exception e) {
                    System.err.println("Warning: failed to add call edge from " + currentMethodFqn + " to " + calledFqn + ": " + e.getMessage());
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
            } else if (n instanceof EnumDeclaration) {
                // Enum always has a synthetic constructor
                String ctorFqn = finalFqn + "." + n.getNameAsString() + "()";
                context.methods.computeIfAbsent(ctorFqn, k -> MethodNode.builder()
                        .id(ctorFqn).fqn(ctorFqn).name(n.getNameAsString())
                        .signature("()").sourceCode("// synthetic enum constructor")
                        .containingClassFqn(finalFqn).isLambda(false).build());
            } else if (n instanceof RecordDeclaration) {
                // Record has a canonical constructor with its components as params
                RecordDeclaration rd = (RecordDeclaration) n;
                boolean hasExplicitCtor = rd.getConstructors().size() > 0;
                if (!hasExplicitCtor) {
                    int paramCount = rd.getParameters().size();
                    String ctorFqn = finalFqn + "." + n.getNameAsString() + "(" + paramCount + ")";
                    context.methods.computeIfAbsent(ctorFqn, k -> MethodNode.builder()
                            .id(ctorFqn).fqn(ctorFqn).name(n.getNameAsString())
                            .signature("(" + paramCount + ")").sourceCode("// synthetic record constructor")
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

            // Build a Joern-style lambda FQN: enclosingClass.<lambda>N()
            String lambdaFqn = currentClassFqn + ".<lambda>" + idx + "()";

            // Register the lambda as a method node
            context.methods.put(lambdaFqn, MethodNode.builder()
                    .id(lambdaFqn).fqn(lambdaFqn)
                    .name("<lambda>" + idx + "()")
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

                    // IMPROVEMENT: Prefer receiver type for inherited methods (parity with Joern)
                    if (n.getScope().isPresent()) {
                        String scopeType = resolveScopeType(n.getScope().get());
                        if (scopeType != null && !scopeType.isBlank() && !scopeType.equals("UNKNOWN")) {
                            // Extract signature and method name, then re-prefix with scope type
                            String signature = calledFqn.contains("(") ? calledFqn.substring(calledFqn.indexOf('(')) : "()";
                            String methodName = resolved.getName();
                            calledFqn = scopeType + "." + methodName + signature;
                        }
                    }
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
            String suffix = "." + methodName + "(" + argCount + ")";

            if (n.getScope().isPresent()) {
                String scopeType = resolveScopeType(n.getScope().get());
                if (scopeType != null && !scopeType.isBlank()) {
                    return scopeType + suffix;
                }
            } else {
                // No explicit scope → check static imports, then implicit "this"
                if (staticImportMap.containsKey(methodName)) {
                    return staticImportMap.get(methodName) + suffix;
                }
                // Try static star imports
                for (String staticStar : staticStarImports) {
                    return staticStar + suffix; // Heuristic: assume first star import
                }
                if (currentClassFqn != null) {
                    return currentClassFqn + suffix;
                }
            }
            return "<unresolvedNamespace>" + suffix;
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
            return resolveScopeTypeRecursive(scope, 0);
        }

        private String resolveScopeTypeRecursive(Expression scope, int depth) {
            if (depth > 5) return null; // Avoid circular/too-deep chains

            // Unwrap parentheses
            Expression unwrapped = scope;
            while (unwrapped.isEnclosedExpr()) {
                unwrapped = unwrapped.asEnclosedExpr().getInner();
            }

            // Cast → use the target type
            if (unwrapped.isCastExpr()) {
                return qualify(stripGenerics(unwrapped.asCastExpr().getType().asString()));
            }

            // Symbol-solver type resolution
            try {
                ResolvedType rt = unwrapped.calculateResolvedType();
                String desc = stripGenerics(rt.describe());
                if (!desc.startsWith("?")) return desc;
            } catch (Exception ignored) { /* fall through */ }

            // Array index access → try to resolve the element type
            if (unwrapped.isArrayAccessExpr()) {
                try {
                    ResolvedType arrType = unwrapped.asArrayAccessExpr()
                            .getName().calculateResolvedType();
                    if (arrType.isArray()) {
                        String comp = stripGenerics(arrType.asArrayType().getComponentType().describe());
                        if (!comp.startsWith("?")) return comp;
                    }
                } catch (Exception ignored) { /* fall through */ }
            }

            // NameExpr → try import-map qualification first, then AST var-decl lookup
            if (unwrapped.isNameExpr()) {
                String name = unwrapped.asNameExpr().getNameAsString();
                String qualified = qualify(name);
                if (!qualified.equals(name)) return qualified;

                String declaredType = findDeclaredType(unwrapped.asNameExpr());
                if (declaredType != null) return qualify(declaredType);
            }

            // FieldAccessExpr (e.g. obj.field or Class.FIELD)
            if (unwrapped.isFieldAccessExpr()) {
                FieldAccessExpr fae = unwrapped.asFieldAccessExpr();
                String scopeType = resolveScopeTypeRecursive(fae.getScope(), depth + 1);
                if (scopeType != null) {
                    // Guess: handle ClassName.CONSTANT
                    return scopeType + "." + fae.getNameAsString();
                }
            }

            // MethodCallExpr scope (e.g. getContext().getConfiguration())
            if (unwrapped.isMethodCallExpr()) {
                MethodCallExpr mce = unwrapped.asMethodCallExpr();
                try {
                    ResolvedMethodDeclaration scopeMethod = mce.resolve();
                    String returnType = stripGenerics(scopeMethod.getReturnType().describe());
                    if (!returnType.startsWith("?") && !"void".equals(returnType)) {
                        return returnType;
                    }
                } catch (Exception e) {
                    // Heuristic fallback for method chains
                    String mName = mce.getNameAsString();
                    String guessed = guessReturnType(mName);
                    if (guessed != null) return guessed;

                    // Recursive heuristic: deduce the method's FQN and check our graph
                    String deduced = deduceFqnManually(mce);
                    MethodNode mn = context.methods.get(deduced);
                    if (mn != null && mn.getSignature() != null && !mn.getSignature().contains("void")) {
                        // We found the method in our source! But we don't store return type in MethodNode yet.
                        // Future: store returnType in MethodNode.
                    }
                }
            }

            // ObjectCreationExpr (e.g. new Foo().method()) → type is Foo
            if (unwrapped.isObjectCreationExpr()) {
                return qualify(stripGenerics(unwrapped.asObjectCreationExpr().getTypeAsString()));
            }

            // this / super
            if (unwrapped.isThisExpr()) {
                return currentClassFqn;
            }
            if (unwrapped.isSuperExpr() && currentClassFqn != null) {
                return context.inheritanceEdges.stream()
                        .filter(e -> e.getChildFqn().equals(currentClassFqn) && e.getType().equals("EXTENDS"))
                        .map(InheritanceEdge::getParentFqn)
                        .findFirst().orElse(null);
            }


            return null;
        }

        private String guessReturnType(String mName) {
            String n = mName;
            if (n.equals("iterator") || n.equals("listIterator")) return "java.util.Iterator";
            if (n.equals("stream") || n.equals("parallelStream")) return "java.util.stream.Stream";
            if (n.equals("toString") || n.equals("getName") || n.equals("getSimpleName") || n.equals("getLocalizedMessage") || n.equals("getMessage")) return "java.lang.String";
            if (n.equals("getClass")) return "java.lang.Class";
            if (n.equals("hasNext") || n.equals("isEmpty") || n.equals("contains") || n.equals("equals") || n.equals("add") || n.equals("remove")) return "boolean";
            if (n.equals("entrySet") || n.equals("keySet") || n.equals("values")) return "java.util.Collection";
            if (n.equals("hashCode")) return "int";
            if (n.equals("addFilter")) return "jakarta.servlet.FilterRegistration.Dynamic";
            if (n.equals("computeIfAbsent") || n.equals("get") || n.equals("put") || n.equals("putIfAbsent") || n.equals("remove")) return "java.lang.Object";
            if (n.equals("catching") || n.equals("throwing") || n.equals("info") || n.equals("debug") || n.equals("warn") || n.equals("error") || n.equals("atLevel")) return "org.apache.logging.log4j.Logger";
            if (n.equals("checkState") || n.equals("checkArgument") || n.equals("checkNotNull") || n.equals("addSuppressed") || n.equals("printStackTrace") || n.equals("setAsyncSupported")) return "void";
            if (n.equals("encode")) return "java.lang.Object";
            return null;
        }

        /**
         * If {@code simpleName} matches an import in this CU, return its FQN.
         * Otherwise return it unchanged. Handles nested classes (e.g. Map.Entry).
         */
        private String qualify(String simpleName) {
            if (simpleName == null || simpleName.isEmpty()) return simpleName;
            
            if (importMap.containsKey(simpleName)) {
                return importMap.get(simpleName);
            }
            
            // Handle nested classes: Map.Entry -> jakarta.util.Map.Entry
            if (simpleName.contains(".")) {
                String prefix = simpleName.substring(0, simpleName.indexOf('.'));
                String suffix = simpleName.substring(simpleName.indexOf('.'));
                String qualifiedPrefix = qualify(prefix);
                if (!qualifiedPrefix.equals(prefix)) {
                    return qualifiedPrefix + suffix;
                }
            }

            // Check star imports against known context classes
            for (String star : starImports) {
                String fqn = star + "." + simpleName;
                if (context.classes.containsKey(fqn)) {
                    return fqn;
                }
            }
            return simpleName;
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

                // Catch-clause variable: catch (IOException e) { e.getMessage() }
                if (node instanceof CatchClause) {
                    CatchClause cc = (CatchClause) node;
                    Parameter p = cc.getParameter();
                    if (p.getNameAsString().equals(varName)) {
                        return stripGenerics(p.getTypeAsString());
                    }
                }

                // Lambda parameter: (e) -> e.getMessage()
                if (node instanceof LambdaExpr) {
                    LambdaExpr le = (LambdaExpr) node;
                    for (Parameter p : le.getParameters()) {
                        if (p.getNameAsString().equals(varName)) {
                            // If it's a simple lambda parameter without an explicit type, it's unknowable via AST.
                            // But if they provided a type (e.g. (IOException e) -> e.getMessage()), we use it.
                            String type = p.getTypeAsString();
                            if (!type.equals("?")) return stripGenerics(type);
                        }
                    }
                }

            }

            // Search in parent classes via context inheritance edges (inheritance-aware)
            if (currentClassFqn != null) {
                String parentFqn = context.inheritanceEdges.stream()
                        .filter(e -> e.getChildFqn().equals(currentClassFqn) && "EXTENDS".equals(e.getType()))
                        .map(InheritanceEdge::getParentFqn)
                        .findFirst().orElse(null);
                if (parentFqn != null) {
                    String foundInParent = searchClassForField(parentFqn, varName);
                    if (foundInParent != null) return foundInParent;
                }
            }

            return null;
        }

        private String searchClassForField(String classFqn, String varName) {
            // Check compilation units for the class FQN and its fields
            for (CompilationUnit cu : context.compilationUnits.values()) {
                for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                    if (classFqn.equals(td.getFullyQualifiedName().orElse(""))) {
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

            // Fallback: Use Symbol Solver for library parents (handles JARs and standard library)
            if (context.typeSolver instanceof com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver) {
                try {
                    com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver ts = 
                        (com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver) context.typeSolver;
                    com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration ref = ts.solveType(classFqn);
                    return ref.getAllFields().stream()
                            .filter(f -> f.getName().equals(varName))
                            .map(f -> stripGenerics(f.getType().describe()))
                            .findFirst().orElse(null);
                } catch (Exception ignored) {}
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
         * Also handles multi-catch types by picking the first one.
         * "Map<String, Integer>" -> "Map", "IOException | SQLException" -> "IOException"
         */
        private static String stripGenerics(String typeStr) {
            if (typeStr == null) return null;
            // Handle multi-catch: "IOException | SQLException" -> "IOException"
            int pipeIdx = typeStr.indexOf('|');
            if (pipeIdx >= 0) {
                typeStr = typeStr.substring(0, pipeIdx).trim();
            }
            int idx = typeStr.indexOf('<');
            String stripped = idx >= 0 ? typeStr.substring(0, idx).trim() : typeStr.trim();
            // Fallback for naked generic parameters like <T> which substring to empty
            return stripped.isEmpty() ? "java.lang.Object" : stripped;
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
                    // Better qualification: check imports and current package/class
                    String typeName = n.getTypeAsString();
                    String baseType = stripGenerics(typeName);
                    String qualifiedType = qualify(baseType);

                    // If it wasn't qualified but looks like a simple name, check inner class
                    if (qualifiedType.equals(baseType) && !baseType.contains(".") && currentClassFqn != null) {
                        qualifiedType = currentClassFqn + "." + baseType;
                    }
                    
                    String simpleMethodName = baseType.contains(".") ? baseType.substring(baseType.lastIndexOf('.') + 1) : baseType;
                    calledFqn = qualifiedType + "." + simpleMethodName + "(" + n.getArguments().size() + ")";
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
                    // Fallback using class inheritance info
                    String targetClass = currentClassFqn;
                    if (!n.isThis() && currentClassFqn != null) {
                        // Find parent class from inheritance edges
                        targetClass = context.inheritanceEdges.stream()
                                .filter(edge -> edge.getChildFqn().equals(currentClassFqn) && "EXTENDS".equals(edge.getType()))
                                .map(InheritanceEdge::getParentFqn)
                                .findFirst().orElse("UNRESOLVED.super");
                    }
                    String simpleName = targetClass.contains(".") ? targetClass.substring(targetClass.lastIndexOf('.') + 1) : targetClass;
                    calledFqn = targetClass + "." + simpleName + "(" + n.getArguments().size() + ")";
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
