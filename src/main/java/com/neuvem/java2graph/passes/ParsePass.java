package com.neuvem.java2graph.passes;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.neuvem.java2graph.Java2GraphConfig;
import com.neuvem.java2graph.models.GraphContext;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Task 1: Robust SymbolSolver Configuration
 */
public class ParsePass implements Pass {

    @Override
    public void execute(Java2GraphConfig config, GraphContext context) throws Exception {
        System.out.println("Beginning Two-Pass Parsing...");
        
        List<Path> javaFiles;
        try (Stream<Path> paths = Files.walk(config.getSrcDir())) {
            javaFiles = paths.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }

        // Pass 1: Parse all files WITHOUT the symbol solver to build the index
        // This avoids "cold start" resolution failures during parallel parsing.
        System.out.println("Pass 1: Parsing " + javaFiles.size() + " files...");

        ParserConfiguration initialConfig = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setStoreTokens(true) // Restore token storage for AST completeness
                .setAttributeComments(true);

        Map<String, CompilationUnit> cuIndex = new ConcurrentHashMap<>();

        javaFiles.parallelStream().forEach((Path path) -> {
            try {
                com.github.javaparser.JavaParser javaParser = new com.github.javaparser.JavaParser(initialConfig);
                // Standard parser call (handles encoding and storage metadata)
                ParseResult<CompilationUnit> result = javaParser.parse(path);
                result.getResult().ifPresent(cu -> {
                    context.compilationUnits.put(path.toString(), cu);
                    
                    cu.findAll(TypeDeclaration.class).forEach(td -> {
                        Optional<String> fqnOpt = ((TypeDeclaration<?>)td).getFullyQualifiedName();
                        if (fqnOpt.isPresent()) {
                            cuIndex.put(fqnOpt.get(), cu);
                        }
                    });
                });
            } catch (Throwable e) {
                System.err.println("Failed to parse: " + path + " - " + e.getMessage());
            }
        });

        System.out.println("Pass 2: Configuring Symbol Resolver with " + cuIndex.size() + " indexed types...");
        
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        
        // 1. SourceMemoryTypeSolver MUST BE FIRST to avoid shadowing by JARs or JRE
        typeSolver.add(new SourceMemoryTypeSolver(cuIndex));
        
        // 2. ReflectionTypeSolver for standard JRE classes
        typeSolver.add(new ReflectionTypeSolver(true));
        
        // 3. ClassLoaderTypeSolver for the current thread classloader
        typeSolver.add(new ClassLoaderTypeSolver(Thread.currentThread().getContextClassLoader()));
        
        // 4. Fallback reflection (includes app classpath)
        typeSolver.add(new ReflectionTypeSolver(false));

        if (config.getJarPaths() != null) {
            for (Path jarPath : config.getJarPaths()) {
                scanAndAddJars(typeSolver, jarPath);
            }
        }

        context.typeSolver = typeSolver;
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        
        // Attach the resolver to all CompilationUnits for the resolution pass
        context.compilationUnits.values().forEach(cu -> {
            cu.setData(com.github.javaparser.ast.Node.SYMBOL_RESOLVER_KEY, symbolSolver);
        });

        System.out.println("Finished two-pass parsing and resolver configuration.");
    }

    private void scanAndAddJars(CombinedTypeSolver typeSolver, Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> paths = Files.walk(path)) {
                paths.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
                    try {
                        typeSolver.add(new JarTypeSolver(p));
                        System.out.println("Loaded dependency JAR: " + p.getFileName());
                    } catch (Exception e) {
                        System.err.println("Failed to load jar: " + p + " - " + e.getMessage());
                    }
                });
            }
        } else if (Files.exists(path) && path.toString().endsWith(".jar")) {
            try {
                typeSolver.add(new JarTypeSolver(path));
                System.out.println("Loaded dependency JAR: " + path.getFileName());
            } catch (Exception e) {
                System.err.println("Failed to load jar: " + path + " - " + e.getMessage());
            }
        }
    }
}
