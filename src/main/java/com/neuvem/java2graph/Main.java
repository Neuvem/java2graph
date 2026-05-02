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
import java.nio.file.Files;
import java.util.ArrayList;
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

    @Option(names = {"--decompiler"}, description = "Decompiler to use: CFR, VINEFLOWER", defaultValue = "CFR")
    private Java2GraphConfig.DecompilerType decompilerType;

    @Option(names = {"--incremental"}, split = ",", description = "List of files to re-index (incremental mode)")
    private List<Path> incrementalFiles;

    @Option(names = {"--incremental-jars"}, split = ",", description = "List of JARs to re-index (incremental mode)")
    private List<Path> incrementalJars;

    @Option(names = {"--incremental-list"}, description = "Path to a file containing a list of source files to re-index (one per line)")
    private Path incrementalFileList;

    @Option(names = {"--jars-list"}, description = "Path to a file containing a list of dependency JARs or directories (one per line)")
    private Path jarPathsList;

    @Option(names = {"--incremental-jars-list"}, description = "Path to a file containing a list of JARs to re-index (one per line)")
    private Path incrementalJarPathsList;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        logger.info("Starting Java2Graph...");

        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(threads));

        // Merge file-based lists into the main lists
        if (jarPathsList != null) {
            if (jarPaths == null) jarPaths = new ArrayList<>();
            mergePathsFromListFile(jarPaths, jarPathsList);
        }
        if (incrementalFileList != null) {
            if (incrementalFiles == null) incrementalFiles = new ArrayList<>();
            mergePathsFromListFile(incrementalFiles, incrementalFileList);
        }
        if (incrementalJarPathsList != null) {
            if (incrementalJars == null) incrementalJars = new ArrayList<>();
            mergePathsFromListFile(incrementalJars, incrementalJarPathsList);
        }

        // Determine if this is an incremental run based on the presence of ANY incremental flag.
        // If any incremental flag was passed (even with an empty list), we MUST ensure
        // both lists are non-null to prevent subsequent passes from defaulting to a 'full run'.
        boolean anyIncrementalFlag = incrementalFiles != null || incrementalFileList != null ||
                                     incrementalJars != null || incrementalJarPathsList != null;

        if (anyIncrementalFlag) {
            if (incrementalFiles == null) incrementalFiles = new ArrayList<>();
            if (incrementalJars == null) incrementalJars = new ArrayList<>();
        }

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
                .decompilerType(decompilerType)
                .cacheDir(cacheDir)
                .incrementalFiles(incrementalFiles)
                .incrementalJars(incrementalJars)
                .build();

        GraphContext context = new GraphContext();
        context.decompileCache = new com.neuvem.java2graph.util.DecompileCache(config.getCacheDir());

        Pass[] passes = {
                new DelombokPass(),
                new ParsePass(),
                // ResolvePass is now fused into ParsePass's streaming loop
                new ExportPass()
        };

        // Prepare phase: Initialize resources early (e.g. open database to claim native memory)
        for (Pass pass : passes) {
            pass.prepare(config, context);
        }

        for (Pass pass : passes) {
            pass.execute(config, context);
        }

        logger.info("Java2Graph processing completed successfully.");
        return 0;
    }

    private void mergePathsFromListFile(List<Path> target, Path listFile) {
        if (listFile == null || !Files.exists(listFile)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(listFile);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                target.add(Paths.get(line));
            }
        } catch (Exception e) {
            logger.error("Failed to read list file {}: {}", listFile, e.getMessage());
        }
    }
}
