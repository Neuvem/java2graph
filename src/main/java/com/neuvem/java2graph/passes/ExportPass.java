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

public class ExportPass implements Pass {
    private static final Logger logger = LogManager.getLogger(ExportPass.class);

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

        if (config.getOutDbPath() != null) {
            Path dbPath = config.getOutDbPath();
            if (!dbPath.toString().endsWith(".db") && !dbPath.toString().endsWith(".lbug")) {
                Files.createDirectories(dbPath);
                dbPath = dbPath.resolve("decypher.db");
            } else if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            
            logger.info("Exporting to Ladybug DB at: {}", dbPath);
            exportLadybug(dbPath, context);
        }
    }

    private void exportClassesCsv(Path dir, GraphContext context) throws IOException {
        try (FileWriter out = new FileWriter(dir.resolve("classes.csv").toFile());
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.Builder.create().setHeader("id", "fqn", "name", "isInterface", "isExternal", "annotations", "declarationCode", "filePath").build())) {
            for (ClassNode node : context.classes.values()) {
                if (node.getId() != null && !node.getId().isBlank()) {
                    String annotations = String.join(";", node.getAnnotations());
                    printer.printRecord(node.getId(), node.getFqn(), node.getName(), node.isInterface(), node.isExternal(), annotations, node.getDeclarationCode(), node.getFilePath());
                }
            }
        }
    }

    private void exportMethodsCsv(Path dir, GraphContext context) throws IOException {
        try (FileWriter out = new FileWriter(dir.resolve("methods.csv").toFile());
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.Builder.create().setHeader("id", "fqn", "name", "signature", "isExternal", "annotations", "sourceCode", "containingClassFqn", "isLambda", "filePath").build())) {
            for (MethodNode node : context.methods.values()) {
                if (node.getId() != null && !node.getId().isBlank()) {
                    String annotations = String.join(";", node.getAnnotations());
                    printer.printRecord(node.getId(), node.getFqn(), node.getName(), node.getSignature(), node.isExternal(), annotations, node.getSourceCode(), node.getContainingClassFqn(), node.isLambda(), node.getFilePath());
                }
            }
        }
    }

    private void exportInheritanceCsv(Path dir, GraphContext context) throws IOException {
        try (FileWriter out = new FileWriter(dir.resolve("inheritance.csv").toFile());
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.Builder.create().setHeader("childFqn", "parentFqn", "type").build())) {
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
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.Builder.create().setHeader("caller", "called").build())) {
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
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.Builder.create().setHeader("classFqn", "methodFqn").build())) {
            for (MethodNode node : context.methods.values()) {
                if (node.getContainingClassFqn() != null && !node.getContainingClassFqn().isBlank() && 
                    node.getFqn() != null && !node.getFqn().isBlank()) {
                    printer.printRecord(node.getContainingClassFqn(), node.getFqn());
                }
            }
        }
    }

    private void exportLadybug(Path dbPath, GraphContext context) {
        // Drop existing database directory to prevent appending duplicate edges 
        // to relationship tables across multiple parser runs.
        if (Files.exists(dbPath)) {
            try {
                Files.walk(dbPath)
                     .sorted(java.util.Comparator.reverseOrder())
                     .map(Path::toFile)
                     .forEach(java.io.File::delete);
            } catch (IOException e) {
                logger.warn("Warning: Could not clear existing database directory: {}", e.getMessage());
            }
        }

        try (Database db = new Database(dbPath.toString());
             Connection conn = new Connection(db)) {

            // Schema
            executeOrThrow(conn, "CREATE NODE TABLE Class(id STRING, fqn STRING, name STRING, isInterface BOOLEAN, isExternal BOOLEAN, annotations STRING, declarationCode STRING, filePath STRING, PRIMARY KEY (id))");
            executeOrThrow(conn, "CREATE NODE TABLE Method(id STRING, fqn STRING, name STRING, signature STRING, isExternal BOOLEAN, annotations STRING, sourceCode STRING, isLambda BOOLEAN, filePath STRING, PRIMARY KEY (id))");

            executeOrThrow(conn, "CREATE REL TABLE Extends(FROM Class TO Class)");
            executeOrThrow(conn, "CREATE REL TABLE Implements(FROM Class TO Class)");
            executeOrThrow(conn, "CREATE REL TABLE Defines(FROM Class TO Method)");
            executeOrThrow(conn, "CREATE REL TABLE Calls(FROM Method TO Method)");

            // Use fast bulk COPY from temporary CSVs
            Path tempDir = Files.createTempDirectory("ladybug_import");

            Path classesCsv = tempDir.resolve("classes.csv");
            try (FileWriter out = new FileWriter(classesCsv.toFile());
                 CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
                for (ClassNode node : context.classes.values()) {
                    if (node.getId() != null && !node.getId().isBlank()) {
                        String annotations = String.join(";", node.getAnnotations());
                        printer.printRecord(node.getId(), node.getFqn(), node.getName(), node.isInterface(), node.isExternal(), annotations, node.getDeclarationCode(), node.getFilePath());
                    }
                }
            }

            Path methodsCsv = tempDir.resolve("methods.csv");
            try (FileWriter out = new FileWriter(methodsCsv.toFile());
                 CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
                for (MethodNode node : context.methods.values()) {
                    if (node.getId() != null && !node.getId().isBlank()) {
                        String annotations = String.join(";", node.getAnnotations());
                        printer.printRecord(node.getId(), node.getFqn(), node.getName(), node.getSignature(), node.isExternal(), annotations, node.getSourceCode(), node.isLambda(), node.getFilePath());
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
            executeOrThrow(conn, "COPY Class FROM '" + classesCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");
            executeOrThrow(conn, "COPY Method FROM '" + methodsCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");
            executeOrThrow(conn, "COPY Extends FROM '" + extendsCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");
            executeOrThrow(conn, "COPY Implements FROM '" + implementsCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");
            executeOrThrow(conn, "COPY Defines FROM '" + definesCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");
            executeOrThrow(conn, "COPY Calls FROM '" + callsCsv.toAbsolutePath().toString() + "' (PARALLEL=FALSE)");

            logger.info("Inserted nodes and edges into Ladybug.");
        } catch (Exception e) {
            logger.error("Failed to export to LadybugDB: {}", e.getMessage(), e);
        }
    }

    private void executeOrThrow(Connection conn, String query) throws Exception {
        try (com.ladybugdb.QueryResult res = conn.query(query)) {
            if (!res.isSuccess()) {
                throw new Exception("Cypher query failed: " + res.getErrorMessage() + " | Query: " + query);
            }
        }
    }
}
