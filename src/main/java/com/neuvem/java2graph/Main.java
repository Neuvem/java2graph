package com.neuvem.java2graph;

import com.neuvem.java2graph.models.GraphContext;
import com.neuvem.java2graph.passes.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "java2graph", mixinStandardHelpOptions = true, version = "1.0",
        description = "Parses Java source code and its dependency jars to create a CSV and Ladybug DB graph.")
public class Main implements Callable<Integer> {

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

    @Option(names = {"-t", "--threads"}, description = "Number of threads for parallel processing", defaultValue = "4")
    private int threads;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        System.out.println("Starting Java2Graph...");

        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(threads));

        Java2GraphConfig config = Java2GraphConfig.builder()
                .srcDir(srcDir)
                .jarPaths(jarPaths)
                .outCsvDir(outCsvDir)
                .outDbPath(outDbPath)
                .enableLombok(enableLombok)
                .threads(threads)
                .build();

        GraphContext context = new GraphContext();

        Pass[] passes = {
                new DelombokPass(),
                new ParsePass(),
                new ResolvePass(),
                new ExportPass()
        };

        for (Pass pass : passes) {
            pass.execute(config, context);
        }

        System.out.println("Java2Graph processing completed successfully.");
        return 0;
    }
}
