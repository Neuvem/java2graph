package com.neuvem.java2graph.passes;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.neuvem.java2graph.Java2GraphConfig;
import com.neuvem.java2graph.models.ClassNode;
import com.neuvem.java2graph.models.GraphContext;
import com.neuvem.java2graph.models.MethodNode;
import com.neuvem.java2graph.models.InheritanceEdge;
import org.objectweb.asm.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Streaming two-pass parser that minimizes heap usage.
 * 
 * Pass 1 (Lightweight Index): Parses all files WITHOUT token storage to build
 * a FQN → file path mapping. CUs are discarded immediately after indexing.
 * 
 * Pass 2 (Streaming Resolve): Re-parses each file one-at-a-time with full
 * source extraction, runs the ResolverVisitor to extract graph data, then
 * discards the CU before moving to the next file. Peak memory = O(1 CU).
 */
public class ParsePass implements Pass {
    private static final Logger logger = LogManager.getLogger(ParsePass.class);


    @Override
    public void execute(Java2GraphConfig config, GraphContext context) throws Exception {
        logger.info("Beginning Streaming Two-Pass Parsing...");
        logMemory("Before parsing");

        List<Path> javaFiles;
        try (Stream<Path> paths = Files.walk(config.getSrcDir())) {
            javaFiles = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return !s.contains("/build/") && !s.contains("/target/") &&
                               !s.contains("/out/") && !s.contains("/bin/") &&
                               !s.contains("/.gradle/") && !s.contains("/.git/") &&
                               !s.contains("/.idea/") && !s.contains("/.java2graph/");
                    })
                    .collect(Collectors.toList());
        }

        // ─────────────────────────────────────────────────────────────────
        // Pass 1: Lightweight index build (no tokens, no comments)
        // Build FQN → file path mapping, then discard all CUs
        // ─────────────────────────────────────────────────────────────────
        System.out.println("Pass 1: Indexing " + javaFiles.size() + " files (lightweight, no tokens)...");

        ParserConfiguration indexConfig = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setStoreTokens(false)
                .setAttributeComments(false);

        // FQN → file path (lightweight: just strings, no AST objects retained)
        Map<String, Path> fqnToPath = new ConcurrentHashMap<>();

        javaFiles.parallelStream().forEach((Path path) -> {
            try {
                com.github.javaparser.JavaParser javaParser = new com.github.javaparser.JavaParser(indexConfig);
                ParseResult<CompilationUnit> result = javaParser.parse(path);
                result.getResult().ifPresent(cu -> {
                    cu.findAll(TypeDeclaration.class).forEach(td -> {
                        Optional<String> fqnOpt = ((TypeDeclaration<?>) td).getFullyQualifiedName();
                        fqnOpt.ifPresent(fqn -> fqnToPath.put(fqn, path));
                    });
                    // CU is NOT stored — it falls out of scope and is GC'd
                });
            } catch (Throwable e) {
                System.err.println("Failed to index: " + path + " - " + e.getMessage());
            }
        });

        logger.info("Pass 1 complete. Indexed {} types from {} files.", fqnToPath.size(), javaFiles.size());
        logMemory("After Pass 1 (index built, CUs discarded)");

        // ─────────────────────────────────────────────────────────────────
        // Configure Symbol Resolver using lazy file-based type solver
        // ─────────────────────────────────────────────────────────────────
        logger.info("Pass 2: Configuring Symbol Resolver with lazy type solver...");

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();

        // 1. SourceMemoryTypeSolver MUST BE FIRST — now uses FQN→Path with LRU cache
        typeSolver.add(new SourceMemoryTypeSolver(fqnToPath));

        // 2. ReflectionTypeSolver for standard JRE classes
        typeSolver.add(new ReflectionTypeSolver(true));

        // 3. ClassLoaderTypeSolver for the current thread classloader
        typeSolver.add(new ClassLoaderTypeSolver(Thread.currentThread().getContextClassLoader()));

        // 4. Fallback reflection (includes app classpath)
        typeSolver.add(new ReflectionTypeSolver(false));

        if (config.getJarPaths() != null) {
            java.util.List<java.net.URL> allUrls = new java.util.ArrayList<>();
            for (Path jarPath : config.getJarPaths()) {
                scanAndAddJars(allUrls, jarPath);
            }
            if (!allUrls.isEmpty()) {
                logger.info("Lazy-loading {} dependency JARs via native URLClassLoader...", allUrls.size());
                java.net.URLClassLoader urlClassLoader = new java.net.URLClassLoader(allUrls.toArray(new java.net.URL[0]));
                context.jarClassLoader = urlClassLoader;
                typeSolver.add(new ClassLoaderTypeSolver(urlClassLoader));
                
                if (config.isIndexAllJarEntries()) {
                    List<Path> jarsToScan = null;
                    boolean isIncrementalMode = (config.getIncrementalFiles() != null && !config.getIncrementalFiles().isEmpty()) ||
                                               (config.getIncrementalJars() != null && !config.getIncrementalJars().isEmpty());
                    
                    if (isIncrementalMode) {
                        // In incremental mode, only index the jars explicitly passed for re-indexing
                        if (config.getIncrementalJars() != null && !config.getIncrementalJars().isEmpty()) {
                            jarsToScan = config.getIncrementalJars();
                        } else {
                            // Incremental source-only update, skip JAR indexing (they are already in the DB)
                            jarsToScan = java.util.Collections.emptyList();
                        }
                    } else {
                        // Full run, index all jars
                        jarsToScan = config.getJarPaths();
                    }

                    if (jarsToScan != null && !jarsToScan.isEmpty()) {
                        logger.info("Pass 1b: Indexing external JAR surface area for {} jars...", jarsToScan.size());
                        for (Path jarPath : jarsToScan) {
                            indexJarSurfaceArea(context, jarPath);
                        }
                    }
                }
            }
        }

        context.typeSolver = typeSolver;
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        com.neuvem.java2graph.util.BudgetedTypeSolver budgetedTypeSolver = new com.neuvem.java2graph.util.BudgetedTypeSolver(typeSolver, 500);
        context.typeSolver = budgetedTypeSolver;
        symbolSolver = new JavaSymbolSolver(budgetedTypeSolver);

        // ─────────────────────────────────────────────────────────────────
        // Pass 2: Streaming parse + resolve (one file at a time)
        // Parse with token storage for full source code, resolve immediately,
        // then discard the CU before moving to the next file.
        // ─────────────────────────────────────────────────────────────────

        ParserConfiguration resolveConfig = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setStoreTokens(true)
                .setAttributeComments(true);

        // Build classAstIndex lazily from fqnToPath for the ResolverVisitor
        Map<String, TypeDeclaration<?>> classAstIndex = new ConcurrentHashMap<>();

        List<Path> filesToResolve;
        if (config.getIncrementalFiles() != null) {
            // Resolve relative incremental paths against srcDir to make them absolute,
            // matching the absolute paths produced by Files.walk(srcDir).
            // This prevents "'other' is different type of Path" from relativize().
            filesToResolve = config.getIncrementalFiles().stream()
                    .map(p -> p.isAbsolute() ? p : config.getSrcDir().resolve(p))
                    .map(Path::normalize)
                    .collect(Collectors.toList());
        } else {
            filesToResolve = javaFiles;
        }
        int totalFiles = filesToResolve.size();
        int resolvedCount = 0;

        // ─────────────────────────────────────────────────────────────────
        // Pass 1c: Parallel Decompilation Warm-up
        // ─────────────────────────────────────────────────────────────────
        if (config.isDecompile()) {
            logger.info("Pass 1c: Warming up decompile cache for suspected external dependencies...");
            java.util.Set<String> suspectedFqns = java.util.concurrent.ConcurrentHashMap.newKeySet();
            filesToResolve.parallelStream().forEach(path -> {
                try {
                    String content = java.nio.file.Files.readString(path);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("import\\s+(?:static\\s+)?([a-zA-Z0-9_\\.]+)(?:\\.[a-zA-Z0-9_\\$]+)?\\s*;").matcher(content);
                    while (m.find()) {
                        String imp = m.group(1);
                        suspectedFqns.add(imp);
                    }
                } catch (Exception ignored) {}
            });
            ResolvePass.warmup(context, config, suspectedFqns);
        }

        java.util.concurrent.ExecutorService watchdogExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        long batchMinTimeNs = Long.MAX_VALUE;
        long batchMaxTimeNs = 0;
        long batchTotalTimeNs = 0;

        logger.info("Pass 2: Streaming parse+resolve of {} files...", filesToResolve.size());
        for (Path path : filesToResolve) {
            if (!java.nio.file.Files.exists(path)) {
                logger.info("Skipping non-existent file: {} (will be treated as deleted)", path);
                continue;
            }
            budgetedTypeSolver.reset();
            com.neuvem.java2graph.util.ResolutionTracer.reset();
            long startNs = System.nanoTime();
            try {
                com.github.javaparser.JavaParser javaParser = new com.github.javaparser.JavaParser(resolveConfig);
                ParseResult<CompilationUnit> result = javaParser.parse(path);

                if (result.getResult().isPresent()) {
                    CompilationUnit cu = result.getResult().get();

                    // Attach the symbol resolver to this CU
                    cu.setData(com.github.javaparser.ast.Node.SYMBOL_RESOLVER_KEY, symbolSolver);

                    // Build the classAstIndex entry for this CU (used for field lookups)
                    cu.findAll(TypeDeclaration.class).forEach(td -> {
                        ((TypeDeclaration<?>) td).getFullyQualifiedName().ifPresent(fqn ->
                                classAstIndex.put(fqn, (TypeDeclaration<?>) td));
                    });

                    // Resolve immediately using the ResolverVisitor, guarded by a 5-sec watchdog
                    String relativePath = config.getSrcDir().relativize(path).toString();
                    String absolutePath = path.toAbsolutePath().toString();
                    java.util.concurrent.Future<?> future = watchdogExecutor.submit(() -> {
                        cu.accept(new ResolvePass.ResolverVisitor(context, config, classAstIndex, cu, absolutePath), null);
                        return null;
                    });
                    try {
                        future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException te) {
                        future.cancel(true);
                        // CRITICAL FIX: The previous file locked the worker thread into an uninterruptible infinite loop.
                        // We MUST abandon this thread pool and create a new one, or ALL subsequent files will timeout with 0 lookups!
                        watchdogExecutor.shutdownNow();
                        watchdogExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
                        
                        String lastSym = com.neuvem.java2graph.util.ResolutionTracer.getLastSymbol();
                        String lastNode = com.neuvem.java2graph.util.ResolutionTracer.getLastNode();
                        logger.warn("Warning: File {} took > 5s to resolve (lookups: {})!", relativePath, budgetedTypeSolver.getCurrentCount());
                        if (lastSym != null) logger.warn("  Last Symbol: {}", lastSym);
                        if (lastNode != null) logger.warn("  Last Node: {}", lastNode);
                        logger.warn("  Falling back to fast heuristic...");
                        fallbackToFast(context, config, classAstIndex, cu, absolutePath);
                    } catch (java.util.concurrent.ExecutionException ee) {
                        Throwable cause = ee.getCause();
                        if (cause instanceof com.neuvem.java2graph.util.QuotaExceededException) {
                            String lastSym = com.neuvem.java2graph.util.ResolutionTracer.getLastSymbol();
                            logger.warn("Warning: File {} exceeded lookup quota!", relativePath);
                            if (lastSym != null) logger.warn("  Last Symbol: {}", lastSym);
                            logger.warn("  Falling back to fast heuristic...");
                            fallbackToFast(context, config, classAstIndex, cu, absolutePath);
                        } else if (cause instanceof com.neuvem.java2graph.util.ComplexityExceededException) {
                            String lastNode = com.neuvem.java2graph.util.ResolutionTracer.getLastNode();
                            logger.warn("Warning: File {} exceeded AST complexity limit!", relativePath);
                            if (lastNode != null) logger.warn("  Last Node: {}", lastNode);
                            logger.warn("  Falling back to fast heuristic...");
                            fallbackToFast(context, config, classAstIndex, cu, absolutePath);
                        } else {
                            logger.error("Error resolving {}: {}", relativePath, cause.getMessage());
                        }
                    } catch (Exception e) {
                        logger.error("Error resolving {}: {}", relativePath, e.getMessage());
                    }

                    // CRITICAL: Clear JavaParserFacade cache after each file
                    com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.clearInstances();

                    // Remove classAstIndex entries for THIS file to avoid holding AST references
                    // (The classAstIndex entries point into the CU we're about to discard)
                    cu.findAll(TypeDeclaration.class).forEach(td -> {
                        ((TypeDeclaration<?>) td).getFullyQualifiedName().ifPresent(classAstIndex::remove);
                    });

                    // CU goes out of scope here and is eligible for GC
                }
            } catch (Throwable e) {
                logger.error("Failed to parse: {} - {}", path, e.getMessage());
            }

            long elapsedNs = System.nanoTime() - startNs;
            batchMinTimeNs = Math.min(batchMinTimeNs, elapsedNs);
            batchMaxTimeNs = Math.max(batchMaxTimeNs, elapsedNs);
            batchTotalTimeNs += elapsedNs;

            resolvedCount++;
            if (resolvedCount % 500 == 0 || resolvedCount == totalFiles) {
                int batchSize = resolvedCount % 500 == 0 ? 500 : (resolvedCount % 500);
                if (batchSize == 0) batchSize = 1; // safety fallback if processing cleanly chunks
                double avgMs = (batchTotalTimeNs / (double) batchSize) / 1_000_000.0;
                double minMs = batchMinTimeNs / 1_000_000.0;
                double maxMs = batchMaxTimeNs / 1_000_000.0;
                
                logger.info("  Progress: {} / {} files processed ({}.1f%%) | Batch ms/file: [Min: {}.1f, Avg: {}.1f, Max: {}.1f]",
                        resolvedCount, totalFiles, (resolvedCount * 100.0) / totalFiles, minMs, avgMs, maxMs);
                
                batchMinTimeNs = Long.MAX_VALUE;
                batchMaxTimeNs = 0;
                batchTotalTimeNs = 0;
            }
        }
        watchdogExecutor.shutdownNow();

        logMemory("After Pass 2 (all files processed)");

        // ─────────────────────────────────────────────────────────────────
        // Add stub nodes for external references
        // ─────────────────────────────────────────────────────────────────
        System.out.println("Adding stub nodes for external references...");
        ResolvePass.addStubNodes(context);

        System.out.println("Finished streaming parse+resolve. Classes: " + context.classes.size() +
                ", Methods: " + context.methods.size() +
                ", Inheritances: " + context.inheritanceEdges.size() +
                ", Calls: " + context.callEdges.size());
        logMemory("Final");
    }

    private void indexJarSurfaceArea(GraphContext context, Path path) {
        if (Files.isDirectory(path)) {
            try (Stream<Path> paths = Files.walk(path)) {
                paths.filter(p -> p.toString().endsWith(".jar")).forEach(p -> scanJar(context, p));
            } catch (IOException e) {
                System.err.println("Warning: Failed to walk jar directory: " + path);
            }
        } else if (Files.exists(path) && path.toString().endsWith(".jar")) {
            scanJar(context, path);
        }
    }

    private void scanJar(GraphContext context, Path jarPath) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && !entry.getName().contains("$")) {
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        ClassReader reader = new ClassReader(is);
                        reader.accept(new SurfaceAreaClassVisitor(context, jarPath.toAbsolutePath().toString()), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    } catch (Exception e) {
                        // Skip problematic classes
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to scan JAR: " + jarPath + " - " + e.getMessage());
        }
    }

    private void scanAndAddJars(java.util.List<java.net.URL> urls, Path path) {
        if (Files.isDirectory(path)) {
            try (Stream<Path> paths = Files.walk(path)) {
                paths.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
                    try {
                        urls.add(p.toUri().toURL());
                    } catch (Exception e) {
                        System.err.println("Failed to resolve URL for jar: " + p);
                    }
                });
            } catch (Exception e) {
                System.err.println("Failed to read jar directory: " + path);
            }
        } else if (Files.exists(path) && path.toString().endsWith(".jar")) {
            try {
                urls.add(path.toUri().toURL());
            } catch (Exception e) {
                System.err.println("Failed to resolve URL for jar: " + path);
            }
        }
    }

    private void fallbackToFast(GraphContext context, Java2GraphConfig config, Map<String, com.github.javaparser.ast.body.TypeDeclaration<?>> classAstIndex, com.github.javaparser.ast.CompilationUnit cu, String absolutePath) {
        Java2GraphConfig fastConfig = Java2GraphConfig.builder()
                .srcDir(config.getSrcDir())
                .jarPaths(config.getJarPaths())
                .outCsvDir(config.getOutCsvDir())
                .outDbPath(config.getOutDbPath())
                .enableLombok(config.isEnableLombok())
                .fastResolve(true)
                .threads(config.getThreads())
                .indexAllJarEntries(config.isIndexAllJarEntries())
                .decompile(config.isDecompile())
                .cacheDir(config.getCacheDir())
                .build();
        cu.accept(new ResolvePass.ResolverVisitor(context, fastConfig, classAstIndex, cu, absolutePath), null);
    }

    private static void logMemory(String label) {
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        logger.info("  [Memory] {}: {}MB used / {}MB max", label, usedMB, maxMB);
    }

    private static class SurfaceAreaClassVisitor extends ClassVisitor {
        private final GraphContext context;
        private final String jarPath;
        private String classFqn;
        private boolean isInterface;
        private List<String> annotations = new ArrayList<>();

        public SurfaceAreaClassVisitor(GraphContext context, String jarPath) {
            super(Opcodes.ASM9);
            this.context = context;
            this.jarPath = jarPath;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.classFqn = name.replace('/', '.');
            this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
            
            context.classes.computeIfAbsent(classFqn, fqn -> ClassNode.builder()
                    .id(fqn).fqn(fqn).name(fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn)
                    .isInterface(isInterface).isExternal(true).declarationCode("// external class from " + jarPath)
                    .filePath(jarPath)
                    .build());

            if (superName != null && !superName.equals("java/lang/Object")) {
                String parentFqn = superName.replace('/', '.');
                ensureClassStub(parentFqn);
                context.inheritanceEdges.add(InheritanceEdge.builder().childFqn(classFqn).parentFqn(parentFqn).type("EXTENDS").build());
            }

            if (interfaces != null) {
                for (String intf : interfaces) {
                    String parentFqn = intf.replace('/', '.');
                    ensureClassStub(parentFqn);
                    String relType = isInterface ? "EXTENDS" : "IMPLEMENTS";
                    context.inheritanceEdges.add(InheritanceEdge.builder().childFqn(classFqn).parentFqn(parentFqn).type(relType).build());
                }
            }
        }

        private void ensureClassStub(String fqn) {
            context.classes.computeIfAbsent(fqn, k -> ClassNode.builder()
                    .id(k).fqn(k).name(k.contains(".") ? k.substring(k.lastIndexOf('.') + 1) : k)
                    .isInterface(false).isExternal(true).declarationCode("// external stub")
                    .build());
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String fqn = Type.getType(descriptor).getClassName();
            annotations.add(fqn);
            return null;
        }

        @Override
        public void visitEnd() {
            ClassNode node = context.classes.get(classFqn);
            if (node != null) {
                node.getAnnotations().addAll(annotations);
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((access & Opcodes.ACC_PUBLIC) != 0) {
                Type[] argTypes = Type.getArgumentTypes(descriptor);
                StringBuilder sb = new StringBuilder("(");
                for (int i = 0; i < argTypes.length; i++) {
                    sb.append(argTypes[i].getClassName());
                    if (i < argTypes.length - 1) sb.append(",");
                }
                sb.append(")");
                String prettySig = sb.toString();
                String methodFqn = classFqn + "." + name + prettySig;
                
                context.methods.computeIfAbsent(methodFqn, fqn -> MethodNode.builder()
                        .id(fqn).fqn(fqn).name(name).signature(prettySig)
                        .containingClassFqn(classFqn).isExternal(true).sourceCode("// external method")
                        .filePath(jarPath)
                        .build());
                
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        String annFqn = Type.getType(descriptor).getClassName();
                        MethodNode mn = context.methods.get(methodFqn);
                        if (mn != null) {
                            mn.getAnnotations().add(annFqn);
                        }
                        return null;
                    }
                };
            }
            return null;
        }
    }
}
