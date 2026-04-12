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
import com.neuvem.java2graph.models.GraphContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

    @Override
    public void execute(Java2GraphConfig config, GraphContext context) throws Exception {
        System.out.println("Beginning Streaming Two-Pass Parsing...");
        logMemory("Before parsing");

        List<Path> javaFiles;
        try (Stream<Path> paths = Files.walk(config.getSrcDir())) {
            javaFiles = paths.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
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

        System.out.println("Pass 1 complete. Indexed " + fqnToPath.size() + " types from " + javaFiles.size() + " files.");
        logMemory("After Pass 1 (index built, CUs discarded)");

        // ─────────────────────────────────────────────────────────────────
        // Configure Symbol Resolver using lazy file-based type solver
        // ─────────────────────────────────────────────────────────────────
        System.out.println("Pass 2: Configuring Symbol Resolver with lazy type solver...");

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
                System.out.println("Lazy-loading " + allUrls.size() + " dependency JARs via native URLClassLoader...");
                java.net.URLClassLoader urlClassLoader = new java.net.URLClassLoader(allUrls.toArray(new java.net.URL[0]));
                typeSolver.add(new ClassLoaderTypeSolver(urlClassLoader));
            }
        }

        context.typeSolver = typeSolver;
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);

        // ─────────────────────────────────────────────────────────────────
        // Pass 2: Streaming parse + resolve (one file at a time)
        // Parse with token storage for full source code, resolve immediately,
        // then discard the CU before moving to the next file.
        // ─────────────────────────────────────────────────────────────────
        System.out.println("Pass 2: Streaming parse+resolve of " + javaFiles.size() + " files...");

        ParserConfiguration resolveConfig = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setStoreTokens(true)
                .setAttributeComments(true);

        // Build classAstIndex lazily from fqnToPath for the ResolverVisitor
        Map<String, TypeDeclaration<?>> classAstIndex = new ConcurrentHashMap<>();

        int totalFiles = javaFiles.size();
        int resolvedCount = 0;

        for (Path path : javaFiles) {
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

                    // Resolve immediately using the ResolverVisitor
                    String relativePath = config.getSrcDir().relativize(path).toString();
                    cu.accept(new ResolvePass.ResolverVisitor(context, config, classAstIndex, cu, relativePath), null);

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
                System.err.println("Failed to parse: " + path + " - " + e.getMessage());
            }

            resolvedCount++;
            if (resolvedCount % 500 == 0 || resolvedCount == totalFiles) {
                System.out.println(String.format("  Progress: %d / %d files processed (%.1f%%)",
                        resolvedCount, totalFiles, (resolvedCount * 100.0) / totalFiles));
            }
        }

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

    private static void logMemory(String label) {
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        System.out.println(String.format("  [Memory] %s: %dMB used / %dMB max", label, usedMB, maxMB));
    }
}
