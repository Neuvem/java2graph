package com.neuvem.java2graph.passes;

import com.ladybugdb.Connection;
import com.ladybugdb.Database;
import com.neuvem.java2graph.Java2GraphConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.neuvem.java2graph.models.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class ExportPass implements Pass {
    private static final Logger logger = LogManager.getLogger(ExportPass.class);
    private Database db;
    private Path resolvedDbPath;
    private boolean isIncremental;

    @Override
    public void prepare(Java2GraphConfig config, GraphContext context) throws Exception {
        this.resolvedDbPath = config.getOutDbPath();
        if (!resolvedDbPath.toString().endsWith(".db") && !resolvedDbPath.toString().endsWith(".lbug")) {
            this.resolvedDbPath = resolvedDbPath.resolve("decypher.db");
        }

        this.isIncremental = config.getIncrementalFiles() != null || config.getIncrementalJars() != null;

        if (!isIncremental && Files.exists(resolvedDbPath)) {
            logger.info("Fresh run detected. Clearing existing database at {}", resolvedDbPath);
            try {
                Files.walk(resolvedDbPath)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            } catch (IOException e) {
                logger.warn("Warning: Could not clear existing database directory: {}", e.getMessage());
            }
        }

        // INITIALIZE DATABASE EARLY: This ensures native memory is claimed while JVM
        // heap is small.
        // We use the advanced constructor to:
        // 1. Set a larger buffer (512 MB) – enough for incremental work.
        // 2. Limit the total DB size to 256 MB to force paging/compact behaviour.
        // 3. Keep compression enabled and read‑write mode.
        long freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024;
        long totalMem = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        logger.info(
                "JVM Memory: {}MB free / {}MB total. Initializing LadybugDB native library at {} with 32MB buffer and 128MB max DB size...",
                freeMem, totalMem, resolvedDbPath);
        try {
            // Ensure parent directory exists
            if (resolvedDbPath.getParent() != null) {
                Files.createDirectories(resolvedDbPath.getParent());
            }

            this.db = new Database(resolvedDbPath.toString());
        } catch (Exception e) {
            logger.error("CRITICAL: Failed to initialize LadybugDB native library: {}", e.getMessage());
            throw e;
        }
    }

    @Override
    public void execute(Java2GraphConfig config, GraphContext context) throws Exception {
        logger.info("Exporting to CSV...");

        if (config.getOutCsvDir() != null) {
            Files.createDirectories(config.getOutCsvDir());
            exportClassesCsv(config.getOutCsvDir(), context);
            exportMethodsCsv(config.getOutCsvDir(), context);
            exportInheritanceCsv(config.getOutCsvDir(), context);
            exportMethodCallsCsv(config.getOutCsvDir(), context);
            exportMethodDefinitionsCsv(config.getOutCsvDir(), context);
        }

        if (resolvedDbPath != null) {
            logger.info("Exporting to Ladybug DB...");
            try {
                exportLadybug(config, context);
                // Compaction not available in current LadybugDB API; relying on maxDbSize for
                // paging.

            } finally {
                if (db != null) {
                    logger.info("Closing Ladybug DB...");
                    db.close();
                }
            }
        }
    }

    private void exportClassesCsv(Path dir, GraphContext context) throws IOException {
        try (FileWriter out = new FileWriter(dir.resolve("classes.csv").toFile());
                CSVPrinter printer = new CSVPrinter(out, CSVFormat.Builder.create().setHeader("id", "fqn", "name",
                        "isInterface", "isExternal", "annotations", "declarationCode", "filePath").build())) {
            for (ClassNode node : context.classes.values()) {
                if (node.getId() != null && !node.getId().isBlank()) {
                    String annotations = String.join(";", node.getAnnotations());
                    printer.printRecord(node.getId(), node.getFqn(), node.getName(), node.isInterface(),
                            node.isExternal(), annotations, node.getDeclarationCode(), node.getFilePath());
                }
            }
        }
    }

    private void exportMethodsCsv(Path dir, GraphContext context) throws IOException {
        try (FileWriter out = new FileWriter(dir.resolve("methods.csv").toFile());
                CSVPrinter printer = new CSVPrinter(out,
                        CSVFormat.Builder.create().setHeader("id", "fqn", "name", "signature", "isExternal",
                                "annotations", "sourceCode", "containingClassFqn", "isLambda", "filePath").build())) {
            for (MethodNode node : context.methods.values()) {
                if (node.getId() != null && !node.getId().isBlank()) {
                    String annotations = String.join(";", node.getAnnotations());
                    printer.printRecord(node.getId(), node.getFqn(), node.getName(), node.getSignature(),
                            node.isExternal(), annotations, node.getSourceCode(), node.getContainingClassFqn(),
                            node.isLambda(), node.getFilePath());
                }
            }
        }
    }

    private void exportInheritanceCsv(Path dir, GraphContext context) throws IOException {
        try (FileWriter out = new FileWriter(dir.resolve("inheritance.csv").toFile());
                CSVPrinter printer = new CSVPrinter(out,
                        CSVFormat.Builder.create().setHeader("childFqn", "parentFqn", "type").build())) {
            for (InheritanceEdge edge : context.inheritanceEdges) {
                if (edge.getChildFqn() != null && !edge.getChildFqn().isBlank() &&
                        edge.getParentFqn() != null && !edge.getParentFqn().isBlank()) {
                    printer.printRecord(edge.getChildFqn(), edge.getParentFqn(), edge.getType());
                }
            }
        }
    }

    private void exportMethodCallsCsv(Path dir, GraphContext context) throws IOException {
        try (FileWriter out = new FileWriter(dir.resolve("method_calls.csv").toFile());
                CSVPrinter printer = new CSVPrinter(out,
                        CSVFormat.Builder.create().setHeader("caller", "called").build())) {
            for (MethodCallEdge edge : context.callEdges) {
                if (edge.getCallerMethodFqn() != null && !edge.getCallerMethodFqn().isBlank() &&
                        edge.getCalledMethodFqn() != null && !edge.getCalledMethodFqn().isBlank()) {
                    printer.printRecord(edge.getCallerMethodFqn(), edge.getCalledMethodFqn());
                }
            }
        }
    }

    private void exportMethodDefinitionsCsv(Path dir, GraphContext context) throws IOException {
        try (FileWriter out = new FileWriter(dir.resolve("method_definitions.csv").toFile());
                CSVPrinter printer = new CSVPrinter(out,
                        CSVFormat.Builder.create().setHeader("classFqn", "methodFqn").build())) {
            for (MethodNode node : context.methods.values()) {
                if (node.getContainingClassFqn() != null && !node.getContainingClassFqn().isBlank() &&
                        node.getFqn() != null && !node.getFqn().isBlank()) {
                    printer.printRecord(node.getContainingClassFqn(), node.getFqn());
                }
            }
        }
    }

    private void exportLadybug(Java2GraphConfig config, GraphContext context) {
        Set<String> affectedPaths = new HashSet<>();
        if (isIncremental) {
            logger.info("Incremental mode detected. Preparing memory-efficient export...");
            if (config.getIncrementalFiles() != null) {
                for (Path path : config.getIncrementalFiles()) {
                    Path absPath = path.isAbsolute() ? path : config.getSrcDir().resolve(path);
                    absPath = absPath.normalize().toAbsolutePath();
                    String p = absPath.toString();
                    affectedPaths.add(p);
                }
            }
            if (config.getIncrementalJars() != null) {
                for (Path path : config.getIncrementalJars()) {
                    affectedPaths.add(path.toString());
                }
            }

            // Aggressively purge context to free up memory
            int startingClasses = context.classes.size();
            int startingMethods = context.methods.size();

            context.classes.entrySet().removeIf(entry -> {
                String filePath = entry.getValue().getFilePath();
                return filePath == null || !affectedPaths.contains(filePath);
            });
            context.methods.entrySet().removeIf(entry -> {
                String filePath = entry.getValue().getFilePath();
                return filePath == null || !affectedPaths.contains(filePath);
            });

            logger.info("Purged GraphContext for incremental export: {} -> {} classes, {} -> {} methods.",
                    startingClasses, context.classes.size(), startingMethods, context.methods.size());

            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }

        try (Connection conn = new Connection(db)) {

            if (!isIncremental) {
                executeOrThrow(conn,
                        "CREATE NODE TABLE Class(id STRING, fqn STRING, name STRING, isInterface BOOLEAN, isExternal BOOLEAN, annotations STRING, declarationCode STRING, filePath STRING, PRIMARY KEY (id))");
                executeOrThrow(conn,
                        "CREATE NODE TABLE Method(id STRING, fqn STRING, name STRING, signature STRING, isExternal BOOLEAN, annotations STRING, sourceCode STRING, isLambda BOOLEAN, filePath STRING, PRIMARY KEY (id))");
                executeOrThrow(conn, "CREATE REL TABLE Extends(FROM Class TO Class)");
                executeOrThrow(conn, "CREATE REL TABLE Implements(FROM Class TO Class)");
                executeOrThrow(conn, "CREATE REL TABLE Defines(FROM Class TO Method)");
                executeOrThrow(conn, "CREATE REL TABLE Calls(FROM Method TO Method)");

                Path tempDir = Files.createTempDirectory("ladybug_import");
                Path classesCsv = tempDir.resolve("classes.csv");
                try (FileWriter out = new FileWriter(classesCsv.toFile());
                        CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
                    for (ClassNode node : context.classes.values()) {
                        if (node.getId() != null && !node.getId().isBlank()) {
                            String annotations = String.join(";", node.getAnnotations());
                            printer.printRecord(node.getId(), node.getFqn(), node.getName(), node.isInterface(),
                                    node.isExternal(), annotations, node.getDeclarationCode(), node.getFilePath());
                        }
                    }
                }

                Path methodsCsv = tempDir.resolve("methods.csv");
                try (FileWriter out = new FileWriter(methodsCsv.toFile());
                        CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
                    for (MethodNode node : context.methods.values()) {
                        if (node.getId() != null && !node.getId().isBlank()) {
                            String annotations = String.join(";", node.getAnnotations());
                            printer.printRecord(node.getId(), node.getFqn(), node.getName(), node.getSignature(),
                                    node.isExternal(), annotations, node.getSourceCode(), node.isLambda(),
                                    node.getFilePath());
                        }
                    }
                }

                Path extendsCsv = tempDir.resolve("extends.csv");
                Path implementsCsv = tempDir.resolve("implements.csv");
                try (FileWriter extOut = new FileWriter(extendsCsv.toFile());
                        FileWriter implOut = new FileWriter(implementsCsv.toFile());
                        CSVPrinter extPrinter = new CSVPrinter(extOut, CSVFormat.DEFAULT);
                        CSVPrinter implPrinter = new CSVPrinter(implOut, CSVFormat.DEFAULT)) {
                    for (InheritanceEdge edge : context.inheritanceEdges) {
                        if (edge.getChildFqn() != null && !edge.getChildFqn().isBlank() &&
                                edge.getParentFqn() != null && !edge.getParentFqn().isBlank()) {
                            if ("EXTENDS".equals(edge.getType())) {
                                extPrinter.printRecord(edge.getChildFqn(), edge.getParentFqn());
                            } else {
                                implPrinter.printRecord(edge.getChildFqn(), edge.getParentFqn());
                            }
                        }
                    }
                }

                Path callsCsv = tempDir.resolve("calls.csv");
                try (FileWriter out = new FileWriter(callsCsv.toFile());
                        CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
                    for (MethodCallEdge edge : context.callEdges) {
                        if (edge.getCallerMethodFqn() != null && !edge.getCallerMethodFqn().isBlank() &&
                                edge.getCalledMethodFqn() != null && !edge.getCalledMethodFqn().isBlank()) {
                            printer.printRecord(edge.getCallerMethodFqn(), edge.getCalledMethodFqn());
                        }
                    }
                }

                Path definesCsv = tempDir.resolve("defines.csv");
                try (FileWriter out = new FileWriter(definesCsv.toFile());
                        CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
                    for (MethodNode node : context.methods.values()) {
                        if (node.getContainingClassFqn() != null && !node.getContainingClassFqn().isBlank() &&
                                node.getFqn() != null && !node.getFqn().isBlank()) {
                            printer.printRecord(node.getContainingClassFqn(), node.getFqn());
                        }
                    }
                }

                logger.info("Executing bulk COPY commands...");
                executeOrThrow(conn,
                        "COPY Class FROM '" + classesCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");
                executeOrThrow(conn,
                        "COPY Method FROM '" + methodsCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");
                executeOrThrow(conn,
                        "COPY Extends FROM '" + extendsCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");
                executeOrThrow(conn,
                        "COPY Implements FROM '" + implementsCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");
                executeOrThrow(conn,
                        "COPY Defines FROM '" + definesCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");
                executeOrThrow(conn, "COPY Calls FROM '" + callsCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");
            } else {
                logger.info("Performing high-performance incremental updates (Purge-then-Create)...");
                
                final int BATCH_SIZE = 2000; // Increased batch size for faster bulk creation
                StringBuilder batch = new StringBuilder();
                int[] batchCount = {0};

                java.util.function.Consumer<String> addBatch = (query) -> {
                    batch.append(query).append(";\n");
                    batchCount[0]++;
                    if (batchCount[0] >= BATCH_SIZE) {
                        try {
                            executeOrThrow(conn, batch.toString());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        batch.setLength(0);
                        batchCount[0] = 0;
                    }
                };

                java.lang.Runnable flushBatch = () -> {
                    if (batchCount[0] > 0) {
                        try {
                            executeOrThrow(conn, batch.toString());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        batch.setLength(0);
                        batchCount[0] = 0;
                    }
                };

                // Track which paths were actually purged to know when to use CREATE vs MERGE
                java.util.Set<String> purgedPaths = new java.util.HashSet<>();
                
                // 1. PURGE: Delete all existing nodes (and their edges) for the affected file paths.
                // We purge by relative path (stable) and by ID (extra safety for renames/moves).
                if (config.getIncrementalFiles() != null) {
                    for (Path path : config.getIncrementalFiles()) {
                        // Ensure we have the relative path as stored in the DB
                        String relPath = path.isAbsolute() ? config.getSrcDir().relativize(path).toString() : path.toString();
                        purgedPaths.add(relPath);
                        logger.info("Purging existing data for file (relative): {}", relPath);
                        addBatch.accept("MATCH (n:Class {filePath: '" + escape(relPath) + "'}) DETACH DELETE n");
                        addBatch.accept("MATCH (m:Method {filePath: '" + escape(relPath) + "'}) DETACH DELETE m");
                    }
                }
                
                // Extra safety: Purge all IDs (FQNs) that we are about to create. 
                // This prevents PK violations if the filePath purge failed due to path mismatch in previous runs.
                for (ClassNode node : context.classes.values()) {
                    if (node.getId() != null) {
                        addBatch.accept("MATCH (n:Class {id: '" + escape(node.getId()) + "'}) DETACH DELETE n");
                    }
                }
                for (MethodNode node : context.methods.values()) {
                    if (node.getId() != null) {
                        addBatch.accept("MATCH (m:Method {id: '" + escape(node.getId()) + "'}) DETACH DELETE m");
                    }
                }

                if (config.getIncrementalJars() != null) {
                    for (Path path : config.getIncrementalJars()) {
                        String p = path.toString();
                        purgedPaths.add(p);
                        logger.info("Purging existing data for JAR: {}", p);
                        addBatch.accept("MATCH (n:Class {filePath: '" + escape(p) + "'}) DETACH DELETE n");
                        addBatch.accept("MATCH (m:Method {filePath: '" + escape(p) + "'}) DETACH DELETE m");
                    }
                }

                // Flush purge before starting creates
                flushBatch.run();

                // 2. CREATE/MERGE: Use fast CREATE for purged nodes, MERGE for others.
                int classCount = 0, methodCount = 0, edgeCount = 0;
                for (ClassNode node : context.classes.values()) {
                    if (node.getId() != null && !node.getId().isBlank()) {
                        String annotations = String.join(";", node.getAnnotations());
                        boolean wasPurged = purgedPaths.contains(node.getFilePath());
                        if (wasPurged) {
                            addBatch.accept(String.format(
                                "CREATE (n:Class {id: '%s', fqn: '%s', name: '%s', isInterface: %b, isExternal: %b, annotations: '%s', declarationCode: '%s', filePath: '%s'})",
                                escape(node.getId()), escape(node.getFqn()), escape(node.getName()), node.isInterface(),
                                node.isExternal(), escape(annotations), escape(node.getDeclarationCode()),
                                escape(node.getFilePath())));
                        } else {
                            // Use MERGE for nodes that might already exist (e.g. external classes from non-purged JARs)
                            addBatch.accept(String.format(
                                "MERGE (n:Class {id: '%s'}) ON CREATE SET n.fqn = '%s', n.name = '%s', n.isInterface = %b, n.isExternal = %b, n.annotations = '%s', n.declarationCode = '%s', n.filePath = '%s'",
                                escape(node.getId()), escape(node.getFqn()), escape(node.getName()), node.isInterface(),
                                node.isExternal(), escape(annotations), escape(node.getDeclarationCode()),
                                escape(node.getFilePath())));
                        }
                        classCount++;
                    }
                }
                for (MethodNode node : context.methods.values()) {
                    if (node.getId() != null && !node.getId().isBlank()) {
                        String annotations = String.join(";", node.getAnnotations());
                        boolean wasPurged = purgedPaths.contains(node.getFilePath());
                        if (wasPurged) {
                            addBatch.accept(String.format(
                                "CREATE (n:Method {id: '%s', fqn: '%s', name: '%s', signature: '%s', isExternal: %b, annotations: '%s', sourceCode: '%s', isLambda: %b, filePath: '%s'})",
                                escape(node.getId()), escape(node.getFqn()), escape(node.getName()),
                                escape(node.getSignature()), node.isExternal(), escape(annotations),
                                escape(node.getSourceCode()), node.isLambda(), escape(node.getFilePath())));
                        } else {
                            addBatch.accept(String.format(
                                "MERGE (n:Method {id: '%s'}) ON CREATE SET n.fqn = '%s', n.name = '%s', n.signature = '%s', n.isExternal = %b, n.annotations = '%s', n.sourceCode = '%s', n.isLambda = %b, n.filePath = '%s'",
                                escape(node.getId()), escape(node.getFqn()), escape(node.getName()),
                                escape(node.getSignature()), node.isExternal(), escape(annotations),
                                escape(node.getSourceCode()), node.isLambda(), escape(node.getFilePath())));
                        }
                        methodCount++;
                    }
                }
                // Flush nodes so they exist before edges
                flushBatch.run();

                // 3. Edges: Use MATCH ... MERGE to be safe against duplicates in incremental mode.
                for (InheritanceEdge edge : context.inheritanceEdges) {
                    ClassNode child = context.classes.get(edge.getChildFqn());
                    if (child != null) {
                        String rel = "EXTENDS".equals(edge.getType()) ? "Extends" : "Implements";
                        addBatch.accept(String.format("MATCH (a:Class {id: '%s'}), (b:Class {id: '%s'}) MERGE (a)-[r:%s]->(b)",
                                        escape(edge.getChildFqn()), escape(edge.getParentFqn()), rel));
                        edgeCount++;
                    }
                }
                for (MethodCallEdge edge : context.callEdges) {
                    MethodNode caller = context.methods.get(edge.getCallerMethodFqn());
                    if (caller != null) {
                        addBatch.accept(String.format(
                                        "MATCH (a:Method {id: '%s'}), (b:Method {id: '%s'}) MERGE (a)-[r:Calls]->(b)",
                                        escape(edge.getCallerMethodFqn()), escape(edge.getCalledMethodFqn())));
                        edgeCount++;
                    }
                }
                for (MethodNode node : context.methods.values()) {
                    if (node.getContainingClassFqn() != null) {
                        addBatch.accept(String.format(
                                        "MATCH (a:Class {id: '%s'}), (b:Method {id: '%s'}) MERGE (a)-[r:Defines]->(b)",
                                        escape(node.getContainingClassFqn()), escape(node.getFqn())));
                        edgeCount++;
                    }
                }
                
                flushBatch.run();
                logger.info("Incremental update complete: {} classes, {} methods, {} edges created.", classCount,
                        methodCount, edgeCount);
            }
            logger.info("Inserted nodes and edges into Ladybug.");
        } catch (Exception e) {
            logger.error("Failed to export to LadybugDB: {}", e.getMessage(), e);
        }
    }

    private String escape(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private void executeOrThrow(Connection conn, String query) throws Exception {
        try (com.ladybugdb.QueryResult res = conn.query(query)) {
            if (!res.isSuccess()) {
                throw new Exception("Cypher query failed: " + res.getErrorMessage() + " | Query: " + query);
            }
        }
    }
}
