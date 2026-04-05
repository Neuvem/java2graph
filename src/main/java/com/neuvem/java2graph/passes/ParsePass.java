package com.neuvem.java2graph.passes;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.neuvem.java2graph.Java2GraphConfig;
import com.neuvem.java2graph.models.GraphContext;
import com.github.javaparser.ParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Task 1: Robust SymbolSolver Configuration
 */
public class ParsePass implements Pass {

    @Override
    public void execute(Java2GraphConfig config, GraphContext context) throws Exception {
        System.out.println("Configuring Symbol Solver...");
        
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        
        // 1. ReflectionTypeSolver for classes on the full classpath (not just JRE)
        typeSolver.add(new ReflectionTypeSolver(false));
        
        // 2. JavaParserTypeSolver for source code being analyzed
        typeSolver.add(new JavaParserTypeSolver(config.getSrcDir()));

        if (config.getJarPaths() != null) {
            for (Path jarPath : config.getJarPaths()) {
                scanAndAddJars(typeSolver, jarPath);
            }
        }

        context.typeSolver = typeSolver;
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        
        // Configure JavaParser with the Symbol Solver
        ParserConfiguration parserConfiguration = new ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        List<Path> javaFiles;
        try (Stream<Path> paths = Files.walk(config.getSrcDir())) {
            javaFiles = paths.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }

        System.out.println("Parsing " + javaFiles.size() + " files...");

        javaFiles.parallelStream().forEach(path -> {
            try {
                // Instantiate a new JavaParser per thread
                JavaParser javaParser = new JavaParser(parserConfiguration);
                ParseResult<CompilationUnit> result = javaParser.parse(path);
                result.getResult().ifPresent(cu -> {
                    context.compilationUnits.put(path.toString(), cu);
                });
            } catch (Throwable e) {
                System.err.println("Failed to parse: " + path + " - " + e.getMessage());
            }
        });
        
        System.out.println("Finished parsing.");
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
