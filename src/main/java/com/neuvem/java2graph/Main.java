package com.neuvem.java2graph;

import com.neuvem.java2graph.models.GraphContext;
import com.neuvem.java2graph.passes.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "java2graph", mixinStandardHelpOptions = true, version = "1.0",
        description = "Parses Java source code and its dependency jars to create a CSV and Ladybug DB graph.")
public class Main implements Callable<Integer> {
    private static final Logger logger = LogManager.getLogger(Main.class);

    @Option(names = {"-s", "--src"}, required = true, description = "Path to the Java source code directory")
    private Path srcDir;

    @Option(names = {"-j", "--jars"}, split = ",", description = "Paths to dependency jars or directories containing jars (comma-separated or repeatable)")
    private List<Path> jarPaths;

    @Option(names = {"-c", "--out-csv"}, description = "Directory to output the CSV files", defaultValue = ".")
    private Path outCsvDir;

    @Option(names = {"-d", "--out-db"}, description = "Directory or path to output the Ladybug DB files", defaultValue = ".")
    private Path outDbPath;

    @Option(names = {"-l", "--lombok"}, description = "Enable Lombok processing", defaultValue = "false")
    private boolean enableLombok;

    @Option(names = {"-f", "--fast"}, description = "Use fast AST heuristic resolution instead of exact symbol solver", defaultValue = "false")
    private boolean fastResolve;

    @Option(names = {"-t", "--threads"}, description = "Number of threads for parallel processing", defaultValue = "4")
    private int threads;

    @Option(names = {"--index-jars"}, description = "Index all public classes and methods in dependency JARs (surface area)", fallbackValue = "true", defaultValue = "true")
    private boolean indexAllJarEntries;

    @Option(names = {"--no-decompile"}, description = "Disable high-fidelity decompilation of external methods", fallbackValue = "true", defaultValue = "false")
    private boolean noDecompile;

    @Option(names = {"--cache-dir"}, description = "Directory to cache decompiled source files")
    private Path cacheDir;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        logger.info("Starting Java2Graph...");

        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(threads));

        // Default cache dir to .java2graph/cache in the current directory if not specified
        if (cacheDir == null) {
            cacheDir = Paths.get(".java2graph", "cache");
        }

        Java2GraphConfig config = Java2GraphConfig.builder()
                .srcDir(srcDir)
                .jarPaths(jarPaths)
                .outCsvDir(outCsvDir)
                .outDbPath(outDbPath)
                .enableLombok(enableLombok)
                .fastResolve(fastResolve)
                .threads(threads)
                .indexAllJarEntries(indexAllJarEntries)
                .decompile(!noDecompile)
                .cacheDir(cacheDir)
                .build();

        GraphContext context = new GraphContext();
        context.decompileCache = new com.neuvem.java2graph.util.DecompileCache(config.getCacheDir());

        Pass[] passes = {
                new DelombokPass(),
                new ParsePass(),
                // ResolvePass is now fused into ParsePass's streaming loop
                new ExportPass()
        };

        for (Pass pass : passes) {
            pass.execute(config, context);
        }

        logger.info("Java2Graph processing completed successfully.");
        return 0;
    }
}
