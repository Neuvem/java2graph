package com.neuvem.java2graph.passes;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.neuvem.java2graph.Java2GraphConfig;
import com.neuvem.java2graph.models.*;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.benf.cfr.reader.api.CfrDriver;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.InputStream;
import java.util.jar.Manifest;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IBytecodeProvider;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;

public class ResolvePass implements Pass {
    private static final Logger logger = LogManager.getLogger(ResolvePass.class);

    @Override
    public void execute(Java2GraphConfig config, GraphContext context) throws Exception {
        // In the streaming architecture, parse+resolve is fused inside ParsePass.
    }

    /**
     * Add stub/placeholder nodes for any FQNs referenced in edges but not yet
     * registered as explicit ClassNode or MethodNode entries.
     */
    public static void addStubNodes(GraphContext context) {
        context.inheritanceEdges.forEach(edge -> {
            if (edge.getChildFqn() != null && !edge.getChildFqn().isBlank()) {
                context.classes.computeIfAbsent(edge.getChildFqn(), fqn -> ClassNode.builder().id(fqn).fqn(fqn)
                        .name(fqn).isInterface(false).isExternal(true).declarationCode("").build());
            }
            if (edge.getParentFqn() != null && !edge.getParentFqn().isBlank()) {
                context.classes.computeIfAbsent(edge.getParentFqn(), fqn -> ClassNode.builder().id(fqn).fqn(fqn)
                        .name(fqn).isInterface(false).isExternal(true).declarationCode("").build());
            }
        });

        context.callEdges.forEach(edge -> {
            if (edge.getCallerMethodFqn() != null && !edge.getCallerMethodFqn().isBlank()) {
                context.methods.computeIfAbsent(edge.getCallerMethodFqn(), fqn -> MethodNode.builder().id(fqn).fqn(fqn)
                        .name(fqn).signature(fqn).sourceCode("").isExternal(true).isLambda(false).build());
            }
            if (edge.getCalledMethodFqn() != null && !edge.getCalledMethodFqn().isBlank()) {
                context.methods.computeIfAbsent(edge.getCalledMethodFqn(), fqn -> MethodNode.builder().id(fqn).fqn(fqn)
                        .name(fqn).signature(fqn).sourceCode("").isExternal(true).isLambda(false).build());
            }
        });
    }

    public static void warmup(GraphContext context, Java2GraphConfig config, Collection<String> classFqns) {
        if (!config.isDecompile() || context.decompileCache == null)
            return;
        logger.info("  Parallel warm-up of {} suspected external classes...", classFqns.size());
        classFqns.parallelStream().forEach(classFqn -> {
            if (classFqn == null || classFqn.isEmpty() || classFqn.contains("*") || classFqn.startsWith("java.")
                    || classFqn.startsWith("javax."))
                return;

            if (context.decompileCache.get(classFqn).isEmpty()) {
                String source;
                if (config.getDecompilerType() == Java2GraphConfig.DecompilerType.VINEFLOWER) {
                    source = decompileWithVineflower(classFqn, context, config);
                } else {
                    source = decompileWithCFR(classFqn, config);
                }
                if (source != null) {
                    context.decompileCache.put(classFqn, source);
                }
            }
        });
    }

    public static void decompileAndFleshOut(GraphContext context, Java2GraphConfig config, String classFqn, int depth) {
        if (!config.isDecompile() || classFqn == null || classFqn.startsWith("<unresolvedNamespace>")
                || classFqn.equals("UNKNOWN") || classFqn.equals("java.lang.Object")) {
            return;
        }

        if (depth >= config.getMaxDecompileDepth()) {
            return;
        }

        if (classFqn.startsWith("java.") || classFqn.startsWith("javax.") ||
            classFqn.startsWith("sun.") || classFqn.startsWith("jdk.")) {
            return;
        }

        ClassNode classNode = context.classes.get(classFqn);
        // Only decompile if it's external and we haven't already fleshed it out
        if (classNode != null && (!classNode.isExternal() || (classNode.getDeclarationCode() != null
                && !classNode.getDeclarationCode().isEmpty() && !classNode.getDeclarationCode().startsWith("//")))) {
            return;
        }

        if (context.decompileCache == null) {
            return;
        }

        Optional<String> cachedSource = context.decompileCache.get(classFqn);
        String source;
        if (cachedSource.isPresent()) {
            source = cachedSource.get();
        } else {
            if (config.getDecompilerType() == Java2GraphConfig.DecompilerType.VINEFLOWER) {
                source = decompileWithVineflower(classFqn, context, config);
            } else {
                source = decompileWithCFR(classFqn, config);
            }

            if (source != null) {
                context.decompileCache.put(classFqn, source);
                logger.info("Decompiled external class: {} ({} chars)", classFqn, source.length());
            } else {
                logger.debug("Decompilation returned null for: {}", classFqn);
            }
        }

        if (source != null) {
            fleshOutFromSource(classFqn, source, context, config, depth);
        }
    }

    private static String decompileWithCFR(String classFqn, Java2GraphConfig config) {
        final StringBuilder sb = new StringBuilder();
        OutputSinkFactory mySink = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> collection) {
                return Collections.singletonList(SinkClass.STRING);
            }

            @Override
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                if (sinkType == SinkType.JAVA && sinkClass == SinkClass.STRING) {
                    return (T s) -> sb.append((String) s);
                }
                return (T o) -> {
                };
            }
        };

        CfrDriver driver = new CfrDriver.Builder()
                .withOutputSink(mySink)
                .build();

        try {
            driver.analyse(Collections.singletonList(classFqn));
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            logger.warn("Warning: CFR failed to decompile {}: {}", classFqn, e.getMessage());
            return null;
        }
    }

    private static String decompileWithVineflower(String classFqn, GraphContext context, Java2GraphConfig config) {
        final Map<String, String> results = new HashMap<>();

        IBytecodeProvider provider = (externalPath, internalPath) -> {
            String path = (internalPath != null) ? internalPath : externalPath;
            if (path == null)
                return null;

            String resourcePath;
            if (path.contains("virtual.class")) {
                resourcePath = classFqn.replace('.', '/') + ".class";
            } else {
                String cleanPath = path;
                if (cleanPath.endsWith(".class")) {
                    cleanPath = cleanPath.substring(0, cleanPath.length() - 6);
                }
                resourcePath = cleanPath.replace('.', '/') + ".class";
            }

            ClassLoader loader = context.jarClassLoader != null ? context.jarClassLoader
                    : Thread.currentThread().getContextClassLoader();
            try (InputStream is = loader.getResourceAsStream(resourcePath)) {
                if (is != null) {
                    return is.readAllBytes();
                } else {
                    logger.debug("Vineflower provider: resource NOT found: {}", resourcePath);
                }
            } catch (Exception e) {
                logger.warn("Vineflower provider error for {}: {}", resourcePath, e.getMessage());
            }
            return null;
        };

        IResultSaver saver = new IResultSaver() {
            @Override
            public void saveClassFile(String path, String qname, String entryName, String content, int[] mapping) {
                if (qname != null)
                    results.put(qname, content);
            }

            @Override
            public void saveFolder(String path) {
            }

            @Override
            public void copyFile(String source, String path, String entryName) {
            }

            @Override
            public void saveDirEntry(String path, String archiveName, String entryName) {
            }

            @Override
            public void copyEntry(String source, String path, String archiveName, String entryName) {
            }

            @Override
            public void saveClassEntry(String path, String archiveName, String entryName, String content,
                    String mapping) {
                if (entryName != null) {
                    results.put(entryName.replace(".class", ""), content);
                }
            }

            @Override
            public void createArchive(String path, String archiveName, Manifest manifest) {
            }

            @Override
            public void closeArchive(String path, String archiveName) {
            }
        };

        Map<String, Object> options = new HashMap<>();
        options.put("hdc", "0"); // Hide default constructor
        options.put("ind", "    "); // Indentation

        Fernflower decompiler = new Fernflower(provider, saver, options, new IFernflowerLogger() {
            @Override
            public void writeMessage(String message, Severity severity) {
                if (severity == Severity.ERROR) {
                    logger.error("Vineflower [ERROR]: {}", message);
                } else if (severity == Severity.WARN) {
                    logger.debug("Vineflower [WARN]: {}", message);
                }
            }

            @Override
            public void writeMessage(String message, Severity severity, Throwable t) {
                logger.error("Vineflower [{}]: {} - {}", severity, message, t.getMessage());
            }
        });

        try {
            decompiler.addSource(new java.io.File("virtual.class"));
            decompiler.decompileContext();

            if (results.containsKey("virtual"))
                return results.get("virtual");
            // Fallback: Return the first result if any (useful if Vineflower used the
            // internal class name as key)
            return results.isEmpty() ? null : results.values().iterator().next();
        } catch (Exception e) {
            logger.warn("Warning: Vineflower failed to decompile {}: {}", classFqn, e.getMessage());
            return null;
        }
    }

    private static void fleshOutFromSource(String classFqn, String source, GraphContext context,
            Java2GraphConfig config, int depth) {
        try {
            com.github.javaparser.JavaParser parser = new com.github.javaparser.JavaParser();
            CompilationUnit cu = parser.parse(source).getResult().orElse(null);
            if (cu == null)
                return;

            if (context.typeSolver != null) {
                com.github.javaparser.symbolsolver.JavaSymbolSolver symbolSolver = new com.github.javaparser.symbolsolver.JavaSymbolSolver((com.github.javaparser.resolution.TypeSolver) context.typeSolver);
                cu.setData(com.github.javaparser.ast.Node.SYMBOL_RESOLVER_KEY, symbolSolver);
            }

            Map<String, TypeDeclaration<?>> classAstIndex = new ConcurrentHashMap<>();
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                td.getFullyQualifiedName().ifPresent(fqn -> classAstIndex.put(fqn, (TypeDeclaration<?>) td));

                Optional<String> optionalFqn = td.getFullyQualifiedName();
                if (optionalFqn.isPresent() && optionalFqn.get().equals(classFqn)) {
                    ClassNode cn = context.classes.get(classFqn);
                    if (cn != null) {
                        cn.setDeclarationCode(getSourceCode(td));
                        cn.setAnnotations(extractAnnotations(td));
                        if (td instanceof ClassOrInterfaceDeclaration) {
                            cn.setInterface(((ClassOrInterfaceDeclaration) td).isInterface());
                        }
                    }

                    for (MethodDeclaration m : td.findAll(MethodDeclaration.class)) {
                        String simpleName = m.getNameAsString();
                        String baseFqn = classFqn + "." + simpleName;
                        for (Map.Entry<String, MethodNode> entry : context.methods.entrySet()) {
                            if (entry.getKey().startsWith(baseFqn) && entry.getValue().isExternal()) {
                                entry.getValue().setSourceCode(getSourceCode(m));
                                entry.getValue().setAnnotations(extractAnnotations(m));
                            }
                        }
                    }
                }
            }

            // Traverse the decompiled code to find further calls
            cu.accept(new ResolverVisitor(context, config, classAstIndex, cu, "virtual:" + classFqn, depth + 1), null);

        } catch (Exception e) {
            logger.warn("Warning: Failed to flesh out from source: {}", classFqn);
        }
    }

    public static List<AnnotationInfo> extractAnnotations(NodeWithAnnotations<?> n) {
        List<AnnotationInfo> annotations = new ArrayList<>();
        n.getAnnotations().forEach(ann -> {
            AnnotationInfo info = new AnnotationInfo();
            info.name = ann.getNameAsString();
            if (ann.isNormalAnnotationExpr()) {
                ann.asNormalAnnotationExpr().getPairs().forEach(pair -> {
                    info.attributes.put(pair.getNameAsString(), pair.getValue().toString());
                });
            } else if (ann.isSingleMemberAnnotationExpr()) {
                info.attributes.put("value", ann.asSingleMemberAnnotationExpr().getMemberValue().toString());
            }
            annotations.add(info);
        });
        return annotations;
    }

    private static String getSourceCode(com.github.javaparser.ast.Node n) {
        return n.getTokenRange().map(Object::toString).orElse("");
    }

    public static class ResolverVisitor extends VoidVisitorAdapter<Void> {
        private final GraphContext context;
        private final Java2GraphConfig config;
        private final Map<String, TypeDeclaration<?>> classAstIndex;
        private final Map<String, String> importMap;
        private final Map<String, String> staticImportMap;
        private final List<String> starImports;
        private final List<String> staticStarImports;

        private final CompilationUnit cu;
        private final String filePath;
        private final int depth;
        private String currentClassFqn = null;
        private String currentMethodFqn = null;
        private final Set<String> seenEdges = ConcurrentHashMap.newKeySet();
        private int lambdaCounter = 0;

        public ResolverVisitor(GraphContext context, Java2GraphConfig config,
                Map<String, TypeDeclaration<?>> classAstIndex, CompilationUnit cu, String filePath) {
            this(context, config, classAstIndex, cu, filePath, 0);
        }

        public ResolverVisitor(GraphContext context, Java2GraphConfig config,
                Map<String, TypeDeclaration<?>> classAstIndex, CompilationUnit cu, String filePath, int depth) {
            this.context = context;
            this.config = config;
            this.classAstIndex = classAstIndex;
            this.cu = cu;
            this.filePath = filePath;
            this.depth = depth;
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            Map<String, Map<String, String>> imports = buildImportMaps(cu, pkg);
            this.importMap = imports.get("types");
            this.staticImportMap = imports.get("static");
            this.starImports = new ArrayList<>(imports.get("stars").keySet());
            this.staticStarImports = new ArrayList<>(imports.get("staticStars").keySet());
        }

        private void checkComplexity(com.github.javaparser.ast.Node n) {
            com.neuvem.java2graph.util.ResolutionTracer
                    .setLastNode(n.toString().length() > 200 ? n.toString().substring(0, 200) + "..." : n.toString());
            int depth = 0;
            com.github.javaparser.ast.Node p = n;
            while (p != null) {
                depth++;
                if (depth > 40) {
                    throw new com.neuvem.java2graph.util.ComplexityExceededException(
                            "AST depth limit (40) reached in " + filePath);
                }
                p = p.getParentNode().orElse(null);
            }
        }

        private static Map<String, Map<String, String>> buildImportMaps(CompilationUnit cu, String currentPkg) {
            Map<String, String> types = new HashMap<>();
            Map<String, String> statics = new HashMap<>();
            Map<String, String> stars = new HashMap<>();
            Map<String, String> staticStars = new HashMap<>();
            stars.put("java.lang", "java.lang");
            if (!currentPkg.isEmpty()) {
                stars.put(currentPkg, currentPkg);
            }
            for (ImportDeclaration imp : cu.getImports()) {
                String fqn = imp.getNameAsString();
                if (imp.isAsterisk()) {
                    if (imp.isStatic())
                        staticStars.put(fqn, fqn);
                    else
                        stars.put(fqn, fqn);
                } else {
                    String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                    if (imp.isStatic())
                        statics.put(simpleName, fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : fqn);
                    else
                        types.put(simpleName, fqn);
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
                            classFqn = "<unresolvedNamespace>";
                            name = baseFqn.substring(1);
                            workingFqn = classFqn + "." + name + signature;
                        } else if (baseFqn.contains(".")) {
                            classFqn = baseFqn.substring(0, baseFqn.lastIndexOf('.'));
                            name = baseFqn.substring(baseFqn.lastIndexOf('.') + 1);
                        }
                        final String finalClassFqn = classFqn;
                        context.classes.computeIfAbsent(finalClassFqn, cfqn -> ClassNode.builder()
                                .id(cfqn).fqn(cfqn)
                                .name(cfqn.contains(".") ? cfqn.substring(cfqn.lastIndexOf('.') + 1) : cfqn)
                                .isInterface(false).isExternal(true).declarationCode("// referenced external class")
                                .build());
                        return MethodNode.builder()
                                .id(workingFqn).fqn(workingFqn)
                                .name(name)
                                .signature(signature)
                                .sourceCode("// referenced external method")
                                .containingClassFqn(finalClassFqn)
                                .isExternal(true)
                                .isLambda(workingFqn.contains("<lambda>"))
                                .build();
                    });

                    if (calledFqn.contains(".") && !calledFqn.startsWith("<unresolvedNamespace>")) {
                        String base = calledFqn.contains("(") ? calledFqn.substring(0, calledFqn.indexOf('(')) : calledFqn;
                        int lastDot = base.lastIndexOf('.');
                        if (lastDot > 0) {
                            String classFqn = base.substring(0, lastDot);
                            decompileAndFleshOut(context, config, classFqn, depth);
                        }
                    }

                    String edgeKey = currentMethodFqn + "\u2192" + calledFqn;
                    if (seenEdges.add(edgeKey)) {
                        context.callEdges.add(MethodCallEdge.builder()
                                .callerMethodFqn(currentMethodFqn)
                                .calledMethodFqn(calledFqn)
                                .build());
                    }
                } catch (Exception e) {
                    logger.warn("Warning: failed to add call edge from {} to {}: {}", currentMethodFqn, calledFqn,
                            e.getMessage());
                }
            }
        }

        private void handleTypeDeclaration(TypeDeclaration<?> n, boolean isInterface) {
            String fqn = "UNKNOWN";
            try {
                checkComplexity(n);
                ResolvedReferenceTypeDeclaration resolved = n.resolve();
                fqn = resolved.getQualifiedName();
            } catch (Exception e) {
                fqn = n.getFullyQualifiedName()
                        .orElse((currentClassFqn != null ? currentClassFqn + "." : "") + n.getNameAsString());
            }
            final String finalFqn = fqn;
            context.classes.put(finalFqn, ClassNode.builder()
                    .id(finalFqn).fqn(finalFqn).name(n.getNameAsString())
                    .isInterface(isInterface).declarationCode(getSourceCode(n))
                    .annotations(extractAnnotations(n))
                    .filePath(filePath).build());
            String prevClassFqn = currentClassFqn;
            currentClassFqn = finalFqn;
            if (n instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) n;
                cid.getExtendedTypes().forEach(ext -> addInheritance(finalFqn, ext, "EXTENDS"));
                cid.getImplementedTypes().forEach(impl -> addInheritance(finalFqn, impl, "IMPLEMENTS"));
            } else if (n instanceof EnumDeclaration) {
                context.inheritanceEdges.add(InheritanceEdge.builder().childFqn(finalFqn).parentFqn("java.lang.Enum")
                        .type("EXTENDS").build());
            } else if (n instanceof RecordDeclaration) {
                context.inheritanceEdges.add(InheritanceEdge.builder().childFqn(finalFqn).parentFqn("java.lang.Record")
                        .type("EXTENDS").build());
            }

            if (!isInterface && n instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) n;
                if (cid.getConstructors().isEmpty()) {
                    String ctorFqn = finalFqn + "." + n.getNameAsString() + "()";
                    context.methods.computeIfAbsent(ctorFqn, k -> MethodNode.builder()
                            .id(ctorFqn).fqn(ctorFqn).name(n.getNameAsString())
                            .signature("()").sourceCode("// synthetic default constructor")
                            .containingClassFqn(finalFqn).isLambda(false).filePath(filePath).build());
                }
            } else if (n instanceof EnumDeclaration) {
                String ctorFqn = finalFqn + "." + n.getNameAsString() + "()";
                context.methods.computeIfAbsent(ctorFqn, k -> MethodNode.builder()
                        .id(ctorFqn).fqn(ctorFqn).name(n.getNameAsString())
                        .signature("()").sourceCode("// synthetic enum constructor")
                        .containingClassFqn(finalFqn).isLambda(false).filePath(filePath).build());
            } else if (n instanceof RecordDeclaration) {
                RecordDeclaration rd = (RecordDeclaration) n;
                if (rd.getConstructors().isEmpty()) {
                    int paramCount = rd.getParameters().size();
                    String ctorFqn = finalFqn + "." + n.getNameAsString() + "(" + paramCount + ")";
                    context.methods.computeIfAbsent(ctorFqn, k -> MethodNode.builder()
                            .id(ctorFqn).fqn(ctorFqn).name(n.getNameAsString())
                            .signature("(" + paramCount + ")").sourceCode("// synthetic record constructor")
                            .containingClassFqn(finalFqn).isLambda(false).filePath(filePath).build());
                }
            }
            for (var member : n.getMembers())
                member.accept(this, null);
            currentClassFqn = prevClassFqn;
        }

        private void addInheritance(String childFqn, ClassOrInterfaceType type, String relType) {
            String parentFqn = null;
            try {
                checkComplexity(type);
                ResolvedType resolved = type.resolve();
                parentFqn = resolved.asReferenceType().getQualifiedName();
            } catch (Exception e) {
                parentFqn = qualify(type.getNameAsString());
            }
            if (parentFqn != null) {
                context.inheritanceEdges
                        .add(InheritanceEdge.builder().childFqn(childFqn).parentFqn(parentFqn).type(relType).build());
                decompileAndFleshOut(context, config, parentFqn, depth);
            }
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            handleTypeDeclaration(n, n.isInterface());
        }

        @Override
        public void visit(EnumDeclaration n, Void arg) {
            handleTypeDeclaration(n, false);
        }

        @Override
        public void visit(AnnotationDeclaration n, Void arg) {
            handleTypeDeclaration(n, true);
        }

        @Override
        public void visit(RecordDeclaration n, Void arg) {
            handleTypeDeclaration(n, false);
        }

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            String fqn;
            try {
                checkComplexity(n);
                fqn = n.resolve().getQualifiedSignature();
            } catch (Exception e) {
                fqn = (currentClassFqn != null ? currentClassFqn : "") + "." + n.getSignature().asString();
            }
            List<AnnotationInfo> annotations = extractAnnotations(n);
            context.methods.put(fqn, MethodNode.builder()
                    .id(fqn).fqn(fqn).name(n.getNameAsString()).signature(n.getSignature().asString())
                    .sourceCode(getSourceCode(n)).containingClassFqn(currentClassFqn)
                    .annotations(annotations)
                    .isLambda(false).filePath(filePath).build());
            
            int pIndex = 0;
            for (com.github.javaparser.ast.body.Parameter p : n.getParameters()) {
                String paramName = p.getNameAsString();
                String paramType = stripGenerics(p.getTypeAsString());
                String paramId = fqn + ".param" + pIndex;
                context.parameters.put(paramId, ParameterNode.builder()
                        .id(paramId).methodFqn(fqn)
                        .name(paramName).typeFqn(paramType).index(pIndex).build());
                pIndex++;
            }
            
            if (currentClassFqn != null) {
                boolean hasAutowired = annotations.stream().anyMatch(a -> a.name.equals("Autowired") || a.name.equals("Inject"));
                if (hasAutowired && n.getParameters().isNonEmpty()) {
                    com.github.javaparser.ast.body.Parameter param = n.getParameter(0);
                    String paramType = null;
                    try {
                        checkComplexity(param.getType());
                        paramType = param.getType().resolve().describe();
                    } catch (Exception e) {
                        paramType = qualify(param.getType().asString());
                    }
                    if (paramType != null) {
                        context.dependencyEdges.add(DependencyEdge.builder()
                            .sourceFqn(currentClassFqn).targetFqn(stripGenerics(paramType)).injectionType("Setter").build());
                    }
                }
            }
            String prev = currentMethodFqn;
            currentMethodFqn = fqn;
            super.visit(n, arg);
            currentMethodFqn = prev;
        }

        @Override
        public void visit(ConstructorDeclaration n, Void arg) {
            String fqn;
            try {
                checkComplexity(n);
                fqn = n.resolve().getQualifiedSignature();
            } catch (Exception e) {
                fqn = (currentClassFqn != null ? currentClassFqn : "") + "." + n.getSignature().asString();
            }
            List<AnnotationInfo> annotations = extractAnnotations(n);
            context.methods.put(fqn, MethodNode.builder()
                    .id(fqn).fqn(fqn).name(n.getNameAsString()).signature(n.getSignature().asString())
                    .sourceCode(getSourceCode(n)).containingClassFqn(currentClassFqn)
                    .annotations(annotations)
                    .isLambda(false).filePath(filePath).build());
                    
            int pIndex = 0;
            for (com.github.javaparser.ast.body.Parameter p : n.getParameters()) {
                String paramName = p.getNameAsString();
                String paramType = stripGenerics(p.getTypeAsString());
                String paramId = fqn + ".param" + pIndex;
                context.parameters.put(paramId, ParameterNode.builder()
                        .id(paramId).methodFqn(fqn)
                        .name(paramName).typeFqn(paramType).index(pIndex).build());
                pIndex++;
            }
                    
            if (currentClassFqn != null) {
                boolean hasAutowired = annotations.stream().anyMatch(a -> a.name.equals("Autowired") || a.name.equals("Inject"));
                boolean isImplicit = false;
                if (!hasAutowired && n.getParentNode().isPresent() && n.getParentNode().get() instanceof ClassOrInterfaceDeclaration) {
                    ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) n.getParentNode().get();
                    if (cid.getConstructors().size() == 1) {
                        isImplicit = true;
                    }
                }
                
                if (hasAutowired || isImplicit) {
                    n.getParameters().forEach(param -> {
                        String paramType = null;
                        try {
                            checkComplexity(param.getType());
                            paramType = param.getType().resolve().describe();
                        } catch (Exception e) {
                            paramType = qualify(param.getType().asString());
                        }
                        if (paramType != null) {
                            context.dependencyEdges.add(DependencyEdge.builder()
                                .sourceFqn(currentClassFqn).targetFqn(stripGenerics(paramType)).injectionType("Constructor").build());
                        }
                    });
                }
            }
            String prev = currentMethodFqn;
            currentMethodFqn = fqn;
            boolean hasExp = n.getBody().getStatements().stream()
                    .anyMatch(s -> s instanceof ExplicitConstructorInvocationStmt);
            if (!hasExp && currentClassFqn != null && !"java.lang.Object".equals(currentClassFqn))
                addCall("UNRESOLVED.super()");
            super.visit(n, arg);
            currentMethodFqn = prev;
        }

        @Override
        public void visit(InitializerDeclaration n, Void arg) {
            String fqn = (currentClassFqn != null ? currentClassFqn : "UNKNOWN")
                    + (n.isStatic() ? ".<clinit>" : ".<init>") + "$" + n.getBegin().map(p -> p.line).orElse(0);
            context.methods.put(fqn,
                    MethodNode.builder().id(fqn).fqn(fqn).name(n.isStatic() ? "<clinit>" : "<init>").signature("()")
                            .sourceCode(getSourceCode(n)).containingClassFqn(currentClassFqn).isLambda(false)
                            .filePath(filePath).build());
            String prev = currentMethodFqn;
            currentMethodFqn = fqn;
            super.visit(n, arg);
            currentMethodFqn = prev;
        }

        @Override
        public void visit(VariableDeclarator n, Void arg) {
            if (currentMethodFqn != null) {
                String varName = n.getNameAsString();
                String varType = stripGenerics(n.getTypeAsString());
                String varId = currentMethodFqn + "." + varName;
                context.variables.put(varId, VariableNode.builder()
                        .id(varId).methodFqn(currentMethodFqn)
                        .name(varName).typeFqn(varType).build());
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(AssignExpr n, Void arg) {
            if (currentMethodFqn != null) {
                String targetId = null;
                if (n.getTarget().isFieldAccessExpr()) {
                    targetId = currentClassFqn + "." + n.getTarget().asFieldAccessExpr().getNameAsString();
                } else if (n.getTarget().isNameExpr()) {
                    String name = n.getTarget().asNameExpr().getNameAsString();
                    if (searchClassForField(currentClassFqn, name) != null) {
                        targetId = currentClassFqn + "." + name;
                    }
                }
                
                if (targetId != null) {
                    String sourceName = null;
                    if (n.getValue().isNameExpr()) {
                        sourceName = currentMethodFqn + "." + n.getValue().asNameExpr().getNameAsString();
                    } else if (n.getValue().isFieldAccessExpr()) {
                        sourceName = currentClassFqn + "." + n.getValue().asFieldAccessExpr().getNameAsString();
                    }
                    if (sourceName != null) {
                        context.dataFlowEdges.add(new DataFlowEdge(sourceName, targetId, "MUTATES"));
                    }
                }
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(FieldDeclaration n, Void arg) {
            if (currentClassFqn != null) {
                String fieldTypeFqn = null;
                try {
                    checkComplexity(n);
                    fieldTypeFqn = n.getCommonType().resolve().describe();
                } catch (Exception e) {
                    fieldTypeFqn = qualify(n.getCommonType().asString());
                }
                final String finalFieldTypeFqn = stripGenerics(fieldTypeFqn);
                List<AnnotationInfo> fieldAnnotations = extractAnnotations(n);
                
                n.getVariables().forEach(v -> {
                    String fieldName = v.getNameAsString();
                    String fieldFqn = currentClassFqn + "." + fieldName;
                    context.fields.put(fieldFqn, FieldNode.builder()
                        .id(fieldFqn).fqn(fieldFqn).name(fieldName)
                        .typeFqn(finalFieldTypeFqn).containingClassFqn(currentClassFqn)
                        .annotations(fieldAnnotations).filePath(filePath)
                        .build());
                        
                    if (fieldAnnotations.stream().anyMatch(a -> a.name.equals("Autowired") || a.name.equals("Inject") || a.name.equals("Resource"))) {
                        if (finalFieldTypeFqn != null) {
                            context.dependencyEdges.add(DependencyEdge.builder()
                                .sourceFqn(currentClassFqn)
                                .targetFqn(finalFieldTypeFqn)
                                .injectionType("Field")
                                .build());
                        }
                    }
                });
            }
            String fqn = (currentClassFqn != null ? currentClassFqn : "UNKNOWN")
                    + (n.isStatic() ? ".<clinit>()" : ".<init>()");
            context.methods.computeIfAbsent(fqn,
                    k -> MethodNode.builder().id(fqn).fqn(fqn).name(n.isStatic() ? "<clinit>" : "<init>")
                            .signature("()").sourceCode("// field initializers").containingClassFqn(currentClassFqn)
                            .isLambda(false).filePath(filePath).build());
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
            if (!n.getClassBody().isEmpty()) {
                String anonFqn = (currentClassFqn != null ? currentClassFqn : "UNKNOWN") + "$" + n.getNameAsString();
                String prevCls = currentClassFqn;
                currentClassFqn = anonFqn;
                context.classes.put(anonFqn, ClassNode.builder().id(anonFqn).fqn(anonFqn).name(n.getNameAsString())
                        .isInterface(false).declarationCode(getSourceCode(n)).filePath(filePath).build());
                n.getClassBody().forEach(member -> member.accept(this, null));
                currentClassFqn = prevCls;
            }
        }

        @Override
        public void visit(LambdaExpr n, Void arg) {
            if (currentClassFqn == null) {
                super.visit(n, arg);
                return;
            }
            int idx = lambdaCounter++;
            String functionalInterface = null;
            if (!config.isFastResolve()) {
                try {
                    checkComplexity(n);
                    ResolvedType rt = n.calculateResolvedType();
                    String desc = stripGenerics(rt.describe());
                    if (!desc.startsWith("?"))
                        functionalInterface = desc;
                } catch (Throwable ignored) {
                }
            }
            
            String lambdaClassFqn = currentClassFqn + "$<lambda-class>" + idx;
            context.classes.put(lambdaClassFqn, ClassNode.builder()
                    .id(lambdaClassFqn).fqn(lambdaClassFqn).name("<lambda-class>" + idx)
                    .isInterface(false).declarationCode("// Synthetic lambda class").filePath(filePath).build());
            
            if (functionalInterface != null) {
                context.inheritanceEdges.add(InheritanceEdge.builder().childFqn(lambdaClassFqn)
                        .parentFqn(functionalInterface).type("IMPLEMENTS").build());
                decompileAndFleshOut(context, config, functionalInterface, depth);
            }

            String lambdaFqn = lambdaClassFqn + ".<lambda>()";
            context.methods.put(lambdaFqn,
                    MethodNode.builder().id(lambdaFqn).fqn(lambdaFqn).name("<lambda>").signature("()")
                            .sourceCode(getSourceCode(n)).containingClassFqn(lambdaClassFqn).isLambda(true).filePath(filePath).build());
            
            addCall(lambdaFqn);
            String prevClass = currentClassFqn;
            String prevMethod = currentMethodFqn;
            currentClassFqn = lambdaClassFqn;
            currentMethodFqn = lambdaFqn;
            super.visit(n, arg);
            currentClassFqn = prevClass;
            currentMethodFqn = prevMethod;
        }

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            String calledFqn = null;
            if (currentMethodFqn != null) {
                boolean resolvedViaSolver = false;
                if (!config.isFastResolve()) {
                    try {
                        checkComplexity(n);
                        ResolvedMethodDeclaration resolved = n.resolve();
                        calledFqn = resolved.getQualifiedSignature();
                        if (n.getScope().isPresent()) {
                            String scopeType = resolveScopeType(n.getScope().get());
                            if (scopeType != null && !scopeType.isBlank() && !scopeType.equals("UNKNOWN")) {
                                String signature = calledFqn.contains("(") ? calledFqn.substring(calledFqn.indexOf('('))
                                        : "()";
                                calledFqn = scopeType + "." + resolved.getName() + signature;
                            }
                        }
                        resolvedViaSolver = true;
                    } catch (Throwable ignored) {
                    }
                }
                if (!resolvedViaSolver)
                    calledFqn = deduceFqnManually(n);
                addCall(calledFqn);
                
                int argIndex = 0;
                for (Expression argExpr : n.getArguments()) {
                    if (argExpr.isNameExpr()) {
                        String argName = argExpr.asNameExpr().getNameAsString();
                        String sourceId = currentMethodFqn + "." + argName;
                        String targetId = calledFqn + ".param" + argIndex;
                        context.dataFlowEdges.add(new DataFlowEdge(sourceId, targetId, "PASSED_TO"));
                    } else if (argExpr.isFieldAccessExpr()) {
                        String argName = argExpr.asFieldAccessExpr().getNameAsString();
                        String sourceId = currentClassFqn + "." + argName;
                        String targetId = calledFqn + ".param" + argIndex;
                        context.dataFlowEdges.add(new DataFlowEdge(sourceId, targetId, "PASSED_TO"));
                    }
                    argIndex++;
                }
            }
            super.visit(n, arg);
        }

        private String deduceFqnManually(MethodCallExpr n) {
            String methodName = n.getNameAsString();
            int argCount = n.getArguments().size();
            String suffix = "." + methodName + "(" + argCount + ")";
            if (n.getScope().isPresent()) {
                String scopeType = resolveScopeType(n.getScope().get());
                if (scopeType != null && !scopeType.isBlank())
                    return scopeType + suffix;
            } else {
                if (staticImportMap.containsKey(methodName))
                    return staticImportMap.get(methodName) + suffix;
                for (String staticStar : staticStarImports)
                    return staticStar + suffix;
                if (currentClassFqn != null)
                    return currentClassFqn + suffix;
            }
            return "<unresolvedNamespace>" + suffix;
        }

        private String resolveScopeType(Expression scope) {
            return resolveScopeTypeRecursive(scope, 0);
        }

        private String resolveScopeTypeRecursive(Expression scope, int depth) {
            if (depth > 5)
                return null;
            Expression unwrapped = scope;
            while (unwrapped.isEnclosedExpr())
                unwrapped = unwrapped.asEnclosedExpr().getInner();
            if (unwrapped.isCastExpr())
                return qualify(stripGenerics(unwrapped.asCastExpr().getType().asString()));
            if (unwrapped.isNameExpr()) {
                String name = unwrapped.asNameExpr().getNameAsString();
                String qualified = qualify(name);
                if (!qualified.equals(name))
                    return qualified;
                String declaredType = findDeclaredType(unwrapped.asNameExpr());
                if (declaredType != null)
                    return qualify(declaredType);
            }
            if (unwrapped.isObjectCreationExpr())
                return qualify(stripGenerics(unwrapped.asObjectCreationExpr().getTypeAsString()));
            if (unwrapped.isThisExpr())
                return currentClassFqn;
            if (unwrapped.isSuperExpr() && currentClassFqn != null) {
                return context.inheritanceEdges.stream()
                        .filter(e -> e.getChildFqn().equals(currentClassFqn) && "EXTENDS".equals(e.getType()))
                        .map(InheritanceEdge::getParentFqn).findFirst().orElse(null);
            }
            if (unwrapped.isFieldAccessExpr()) {
                FieldAccessExpr fae = unwrapped.asFieldAccessExpr();
                String scopeType = resolveScopeTypeRecursive(fae.getScope(), depth + 1);
                if (scopeType != null)
                    return scopeType + "." + fae.getNameAsString();
            }
            if (config.isFastResolve()) {
                if (unwrapped.isMethodCallExpr()) {
                    MethodCallExpr mce = unwrapped.asMethodCallExpr();
                    String guessed = guessReturnType(mce.getNameAsString());
                    if (guessed != null)
                        return guessed;
                    String deduced = deduceFqnManually(mce);
                    MethodNode mn = context.methods.get(deduced);
                    if (mn != null && mn.getSignature() != null && !mn.getSignature().contains("void")) {
                    }
                }
                return null;
            }
            try {
                checkComplexity(unwrapped);
                ResolvedType rt = unwrapped.calculateResolvedType();
                String desc = stripGenerics(rt.describe());
                if (!desc.startsWith("?"))
                    return desc;
            } catch (Throwable ignored) {
            }
            if (unwrapped.isArrayAccessExpr()) {
                try {
                    checkComplexity(unwrapped);
                    ResolvedType arrType = unwrapped.asArrayAccessExpr().getName().calculateResolvedType();
                    if (arrType.isArray()) {
                        String comp = stripGenerics(arrType.asArrayType().getComponentType().describe());
                        if (!comp.startsWith("?"))
                            return comp;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (unwrapped.isMethodCallExpr()) {
                MethodCallExpr mce = unwrapped.asMethodCallExpr();
                try {
                    checkComplexity(mce);
                    ResolvedMethodDeclaration m = mce.resolve();
                    String returnType = stripGenerics(m.getReturnType().describe());
                    if (!returnType.startsWith("?") && !"void".equals(returnType))
                        return returnType;
                } catch (Throwable e) {
                    String guessed = guessReturnType(mce.getNameAsString());
                    if (guessed != null)
                        return guessed;
                }
            }
            return null;
        }

        private String guessReturnType(String mName) {
            String n = mName;
            if (n.equals("iterator") || n.equals("listIterator"))
                return "java.util.Iterator";
            if (n.equals("stream") || n.equals("parallelStream"))
                return "java.util.stream.Stream";
            if (n.equals("toString") || n.equals("getName") || n.equals("getSimpleName")
                    || n.equals("getLocalizedMessage") || n.equals("getMessage"))
                return "java.lang.String";
            if (n.equals("getClass"))
                return "java.lang.Class";
            if (n.equals("hasNext") || n.equals("isEmpty") || n.equals("contains") || n.equals("equals")
                    || n.equals("add") || n.equals("remove"))
                return "boolean";
            if (n.equals("entrySet") || n.equals("keySet") || n.equals("values"))
                return "java.util.Collection";
            if (n.equals("hashCode"))
                return "int";
            if (n.equals("addFilter"))
                return "jakarta.servlet.FilterRegistration.Dynamic";
            if (n.equals("computeIfAbsent") || n.equals("get") || n.equals("put") || n.equals("putIfAbsent")
                    || n.equals("remove"))
                return "java.lang.Object";
            if (n.equals("catching") || n.equals("throwing") || n.equals("info") || n.equals("debug")
                    || n.equals("warn") || n.equals("error") || n.equals("atLevel"))
                return "org.apache.logging.log4j.Logger";
            if (n.equals("checkState") || n.equals("checkArgument") || n.equals("checkNotNull")
                    || n.equals("addSuppressed") || n.equals("printStackTrace") || n.equals("setAsyncSupported"))
                return "void";
            if (n.equals("encode"))
                return "java.lang.Object";
            return null;
        }

        private String qualify(String simpleName) {
            if (simpleName == null || simpleName.isEmpty())
                return simpleName;
            if (importMap.containsKey(simpleName))
                return importMap.get(simpleName);
            if (simpleName.contains(".")) {
                String prefix = simpleName.substring(0, simpleName.indexOf('.'));
                String suffix = simpleName.substring(simpleName.indexOf('.'));
                String qualifiedPrefix = qualify(prefix);
                if (!qualifiedPrefix.equals(prefix))
                    return qualifiedPrefix + suffix;
            }
            for (String star : starImports) {
                String fqn = star + "." + simpleName;
                if (context.classes.containsKey(fqn))
                    return fqn;
            }
            return simpleName;
        }

        private String findDeclaredType(NameExpr nameExpr) {
            String varName = nameExpr.getNameAsString();
            com.github.javaparser.ast.Node node = nameExpr;
            while (node.getParentNode().isPresent()) {
                node = node.getParentNode().get();
                if (node instanceof MethodDeclaration) {
                    MethodDeclaration md = (MethodDeclaration) node;
                    for (Parameter p : md.getParameters())
                        if (p.getNameAsString().equals(varName))
                            return stripGenerics(p.getTypeAsString());
                    String found = searchBodyForVar(md, varName);
                    if (found != null)
                        return found;
                    break;
                }
                if (node instanceof ConstructorDeclaration) {
                    ConstructorDeclaration cd = (ConstructorDeclaration) node;
                    for (Parameter p : cd.getParameters())
                        if (p.getNameAsString().equals(varName))
                            return stripGenerics(p.getTypeAsString());
                    String found = searchBodyForVar(cd, varName);
                    if (found != null)
                        return found;
                    break;
                }
                if (node instanceof ForEachStmt) {
                    ForEachStmt fes = (ForEachStmt) node;
                    if (fes.getVariable().getVariables().stream().anyMatch(v -> v.getNameAsString().equals(varName)))
                        return stripGenerics(fes.getVariable().getCommonType().asString());
                }
                if (node instanceof CatchClause) {
                    CatchClause cc = (CatchClause) node;
                    Parameter p = cc.getParameter();
                    if (p.getNameAsString().equals(varName))
                        return stripGenerics(p.getTypeAsString());
                }
                if (node instanceof LambdaExpr) {
                    LambdaExpr le = (LambdaExpr) node;
                    for (Parameter p : le.getParameters())
                        if (p.getNameAsString().equals(varName)) {
                            String type = p.getTypeAsString();
                            if (!type.equals("?"))
                                return stripGenerics(type);
                        }
                }
            }
            if (currentClassFqn != null) {
                String parentFqn = context.inheritanceEdges.stream()
                        .filter(e -> e.getChildFqn().equals(currentClassFqn) && "EXTENDS".equals(e.getType()))
                        .map(InheritanceEdge::getParentFqn).findFirst().orElse(null);
                if (parentFqn != null) {
                    String found = searchClassForField(parentFqn, varName);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        private String searchClassForField(String classFqn, String varName) {
            TypeDeclaration<?> td = classAstIndex.get(classFqn);
            if (td != null) {
                for (FieldDeclaration fd : td.getFields())
                    for (VariableDeclarator vd : fd.getVariables())
                        if (vd.getNameAsString().equals(varName))
                            return stripGenerics(vd.getTypeAsString());
            }
            if (context.typeSolver instanceof com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver) {
                try {
                    var ts = (com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver) context.typeSolver;
                    var ref = ts.solveType(classFqn);
                    return ref.getAllFields().stream().filter(f -> f.getName().equals(varName))
                            .map(f -> stripGenerics(f.getType().describe())).findFirst().orElse(null);
                } catch (Exception ignored) {
                }
            }
            return null;
        }

        private String searchBodyForVar(com.github.javaparser.ast.Node bodyNode, String varName) {
            return bodyNode.findFirst(VariableDeclarator.class, vd -> vd.getNameAsString().equals(varName))
                    .map(vd -> stripGenerics(vd.getTypeAsString())).orElse(null);
        }

        private static String stripGenerics(String typeStr) {
            if (typeStr == null)
                return null;
            int pipeIdx = typeStr.indexOf('|');
            if (pipeIdx >= 0)
                typeStr = typeStr.substring(0, pipeIdx).trim();
            int idx = typeStr.indexOf('<');
            String stripped = idx >= 0 ? typeStr.substring(0, idx).trim() : typeStr.trim();
            return stripped.isEmpty() ? "java.lang.Object" : stripped;
        }

        @Override
        public void visit(ObjectCreationExpr n, Void arg) {
            if (currentMethodFqn != null) {
                String calledFqn;
                try {
                    checkComplexity(n);
                    calledFqn = n.resolve().getQualifiedSignature();
                } catch (Exception e) {
                    String typeName = n.getTypeAsString();
                    String baseType = stripGenerics(typeName);
                    String qualifiedType = qualify(baseType);
                    if (qualifiedType.equals(baseType) && !baseType.contains(".") && currentClassFqn != null)
                        qualifiedType = currentClassFqn + "." + baseType;
                    String simpleMethodName = baseType.contains(".") ? baseType.substring(baseType.lastIndexOf('.') + 1)
                            : baseType;
                    calledFqn = qualifiedType + "." + simpleMethodName + "(" + n.getArguments().size() + ")";
                }
                addCall(calledFqn);
            }
            if (n.getAnonymousClassBody().isPresent()) {
                String anonFqn = (currentClassFqn != null ? currentClassFqn : "UNKNOWN") + "$anon$"
                        + n.getBegin().map(p -> p.line).orElse(0);
                String prevCls = currentClassFqn;
                currentClassFqn = anonFqn;
                context.classes.put(anonFqn, ClassNode.builder().id(anonFqn).fqn(anonFqn).name("$anon")
                        .isInterface(false).declarationCode(getSourceCode(n)).filePath(filePath).build());
                n.getAnonymousClassBody().get().forEach(member -> member.accept(this, null));
                currentClassFqn = prevCls;
            } else
                super.visit(n, arg);
        }

        @Override
        public void visit(ExplicitConstructorInvocationStmt n, Void arg) {
            if (currentMethodFqn != null) {
                String calledFqn;
                try {
                    checkComplexity(n);
                    calledFqn = n.resolve().getQualifiedSignature();
                } catch (Exception e) {
                    String targetClass = currentClassFqn;
                    if (!n.isThis() && currentClassFqn != null) {
                        targetClass = context.inheritanceEdges.stream()
                                .filter(edge -> edge.getChildFqn().equals(currentClassFqn)
                                        && "EXTENDS".equals(edge.getType()))
                                .map(InheritanceEdge::getParentFqn).findFirst().orElse("UNRESOLVED.super");
                    }
                    String simpleName = targetClass.contains(".")
                            ? targetClass.substring(targetClass.lastIndexOf('.') + 1)
                            : targetClass;
                    calledFqn = targetClass + "." + simpleName + "(" + n.getArguments().size() + ")";
                }
                addCall(calledFqn);
            }
            super.visit(n, arg);
        }

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
            if (currentMethodFqn != null && !n.getResources().isEmpty())
                n.getResources().forEach(r -> addCall("<unresolvedNamespace>.close()"));
            super.visit(n, arg);
        }
    }
}
