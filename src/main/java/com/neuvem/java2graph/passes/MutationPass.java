package com.neuvem.java2graph.passes;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ladybugdb.Connection;
import com.ladybugdb.Database;
import com.ladybugdb.FlatTuple;
import com.ladybugdb.QueryResult;
import com.neuvem.java2graph.Java2GraphConfig;
import com.neuvem.java2graph.models.GraphContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;

public class MutationPass implements Pass {
    private static final Logger logger = LogManager.getLogger(MutationPass.class);
    private final Gson gson = new Gson();

    // Graph connection — null when no DB is available
    private Database db;

    @Override
    public void execute(Java2GraphConfig config, GraphContext context) throws Exception {
        if (config.getMutationPlanPath() == null) {
            return;
        }

        logger.info("Executing Mutation Plan from {}...", config.getMutationPlanPath());

        String planJson = Files.readString(config.getMutationPlanPath());
        JsonObject plan = gson.fromJson(planJson, JsonObject.class);
        JsonArray operations = plan.getAsJsonArray("operations");

        if (operations == null || operations.size() == 0) {
            logger.warn("No operations found in mutation plan.");
            return;
        }

        // Open graph DB if available
        initGraphConnection(config);

        if (config.isGitCheckpoint()) {
            createGitCheckpoint(config.getSrcDir());
        }

        int totalOps = 0;
        int totalFilesModified = 0;

        try {
            for (JsonElement opElement : operations) {
                JsonObject op = opElement.getAsJsonObject();
                String type = op.get("type").getAsString();

                try {
                    int filesModified = 0;
                    switch (type) {
                        case "rename_node":
                            filesModified = handleRenameNode(op, config);
                            break;
                        case "replace_body":
                            filesModified = handleReplaceBody(op, config);
                            break;
                        case "add_method":
                            filesModified = handleAddMethod(op, config);
                            break;
                        case "create_class":
                            filesModified = handleCreateClass(op, config);
                            break;
                        case "delete_node":
                            filesModified = handleDeleteNode(op, config);
                            break;
                        default:
                            logger.error("Unknown mutation type: {}", type);
                    }

                    if (filesModified == 0) {
                        logger.warn("Operation '{}' did not modify any files.", type);
                    } else {
                        logger.info("Operation '{}' modified {} file(s).", type, filesModified);
                    }

                    totalOps++;
                    totalFilesModified += filesModified;
                } catch (Exception e) {
                    logger.error("Failed to execute mutation {}: {}", type, e.getMessage());
                    throw e; // Abort on first error to prevent inconsistent state
                }
            }
        } finally {
            closeGraphConnection();
        }

        if (totalFilesModified == 0) {
            logger.warn("Mutation Plan completed but NO files were modified ({} operations attempted).", totalOps);
        } else {
            logger.info("Mutation Plan executed successfully: {} operations applied, {} files modified.", totalOps, totalFilesModified);
        }
    }

    // -----------------------------------------------------------------------
    // Node type resolution
    // -----------------------------------------------------------------------

    private String resolveNodeType(JsonObject op, String fqn) {
        if (op.has("nodeType") && !op.get("nodeType").isJsonNull()) {
            return op.get("nodeType").getAsString();
        }
        // Fallback heuristic with deprecation warning
        logger.warn("Operation missing 'nodeType' field for FQN '{}'. Using heuristic detection. " +
                     "Please add explicit 'nodeType': 'class' or 'method' to your mutation plan.", fqn);
        if (fqn.contains("(") && fqn.contains(")")) {
            return "method";
        }
        return "class";
    }

    // -----------------------------------------------------------------------
    // RENAME NODE
    // -----------------------------------------------------------------------

    private int handleRenameNode(JsonObject op, Java2GraphConfig config) throws IOException {
        String fqn = op.get("fqn").getAsString();
        String newName = op.get("newName").getAsString();
        String nodeType = resolveNodeType(op, fqn);

        if ("class".equals(nodeType)) {
            return renameClass(fqn, newName, config);
        } else if ("method".equals(nodeType)) {
            return renameMethod(fqn, newName, config);
        } else {
            throw new RuntimeException("Unknown nodeType: " + nodeType);
        }
    }

    private int renameClass(String fqn, String newName, Java2GraphConfig config) throws IOException {
        String oldSimpleName = extractSimpleName(fqn);
        String oldPackage = fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
        int filesModified = 0;

        // 1. Find and modify declaration file
        Path declFile = findFileForClass(config.getSrcDir(), fqn);
        if (declFile == null) {
            throw new RuntimeException("Could not find declaration file for class: " + fqn);
        }

        boolean modified = modifyFileWithStringFallback(declFile, oldSimpleName, newName, cu -> {
            boolean[] changed = {false};
            // Rename the type declaration (covers class, interface, enum, annotation, record)
            cu.findAll(TypeDeclaration.class).forEach(td -> {
                if (td.getNameAsString().equals(oldSimpleName)) {
                    td.setName(newName);
                    changed[0] = true;
                }
            });
            // Rename constructors
            cu.findAll(ConstructorDeclaration.class).forEach(cd -> {
                if (cd.getNameAsString().equals(oldSimpleName)) {
                    cd.setName(newName);
                    changed[0] = true;
                }
            });
            // Update all name references (types, static access, etc.)
            updateAllClassReferences(cu, oldSimpleName, newName, changed);
            return changed[0];
        });
        if (modified) filesModified++;

        // 2. Rename the .java file if it matches the old class name
        if (declFile.getFileName().toString().equals(oldSimpleName + ".java")) {
            Path newFilePath = declFile.getParent().resolve(newName + ".java");
            Files.move(declFile, newFilePath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Renamed file: {} -> {}", declFile, newFilePath);
            declFile = newFilePath; // update reference
        }

        // 3. Cross-file updates: find all files that reference this class
        Set<Path> affectedFiles = findFilesReferencingClass(config.getSrcDir(), fqn);
        // Remove the declaration file (already handled and possibly renamed)
        affectedFiles.remove(declFile);

        for (Path file : affectedFiles) {
            boolean fileModified = modifyFileWithStringFallback(file, oldSimpleName, newName, cu -> {
                boolean[] changed = {false};
                // Update imports
                cu.getImports().forEach(imp -> {
                    String importName = imp.getNameAsString();
                    if (importName.equals(fqn)) {
                        String newFqn = oldPackage.isEmpty() ? newName : oldPackage + "." + newName;
                        imp.setName(newFqn);
                        changed[0] = true;
                    }
                });
                // Update all name references (types, static access, etc.)
                updateAllClassReferences(cu, oldSimpleName, newName, changed);
                return changed[0];
            });
            if (fileModified) filesModified++;
        }

        return filesModified;
    }

    /**
     * Updates all references to a class name in a CompilationUnit:
     * - ClassOrInterfaceType (variable types, return types, new expressions, extends/implements)
     * - NameExpr (static member access like OldClass.CONSTANT)
     */
    private void updateAllClassReferences(CompilationUnit cu, String oldName, String newName, boolean[] changed) {
        // 1. ClassOrInterfaceType — must use replace() due to LexicalPreservingPrinter bug
        List<ClassOrInterfaceType> toReplace = new ArrayList<>();
        cu.findAll(ClassOrInterfaceType.class).forEach(type -> {
            if (type.getNameAsString().equals(oldName)) {
                toReplace.add(type);
            }
        });
        for (ClassOrInterfaceType type : toReplace) {
            ClassOrInterfaceType newType = new ClassOrInterfaceType(null, newName);
            // Preserve generic type arguments if present
            type.getTypeArguments().ifPresent(newType::setTypeArguments);
            type.replace(newType);
            changed[0] = true;
        }

        // 2. NameExpr — handles static member access (e.g. OldClass.SOME_FIELD)
        cu.findAll(NameExpr.class).forEach(ne -> {
            if (ne.getNameAsString().equals(oldName)) {
                ne.setName(newName);
                changed[0] = true;
            }
        });
    }

    private int renameMethod(String fqn, String newName, Java2GraphConfig config) throws IOException {
        String oldMethodName = extractSimpleName(fqn);
        int filesModified = 0;

        // 1. Find and modify declaration file
        Path declFile = findFileForMethod(config.getSrcDir(), fqn);
        if (declFile == null) {
            throw new RuntimeException("Could not find declaration file for method: " + fqn);
        }

        boolean modified = modifyFile(declFile, cu -> {
            boolean[] changed = {false};
            cu.findAll(MethodDeclaration.class).forEach(md -> {
                if (md.getNameAsString().equals(oldMethodName)) {
                    md.setName(newName);
                    changed[0] = true;
                }
            });
            // Also update call sites within the same file
            cu.findAll(MethodCallExpr.class).forEach(mce -> {
                if (mce.getNameAsString().equals(oldMethodName)) {
                    mce.setName(newName);
                    changed[0] = true;
                }
            });
            return changed[0];
        });
        if (modified) filesModified++;

        // 2. Cross-file updates: find all files that call this method
        Set<Path> callerFiles = findFilesCallingMethod(config.getSrcDir(), fqn);
        callerFiles.remove(declFile);

        for (Path file : callerFiles) {
            boolean fileModified = modifyFile(file, cu -> {
                boolean[] changed = {false};
                cu.findAll(MethodCallExpr.class).forEach(mce -> {
                    if (mce.getNameAsString().equals(oldMethodName)) {
                        mce.setName(newName);
                        changed[0] = true;
                    }
                });
                return changed[0];
            });
            if (fileModified) filesModified++;
        }

        return filesModified;
    }

    // -----------------------------------------------------------------------
    // REPLACE BODY
    // -----------------------------------------------------------------------

    private int handleReplaceBody(JsonObject op, Java2GraphConfig config) throws IOException {
        String fqn = op.get("fqn").getAsString();
        String newSource = op.get("newSource").getAsString();

        Path path = findFileForMethod(config.getSrcDir(), fqn);
        if (path == null)
            throw new RuntimeException("Could not find file for FQN: " + fqn);

        String methodName = extractSimpleName(fqn);
        boolean modified = modifyFile(path, cu -> {
            boolean[] changed = {false};
            cu.findAll(MethodDeclaration.class).forEach(md -> {
                if (md.getNameAsString().equals(methodName)) {
                    var newBody = StaticJavaParser.parseBlock(newSource);
                    md.setBody(newBody);
                    
                    // Handle imports
                    addExplicitImports(cu, op);
                    autoResolveImports(cu, newBody);
                    
                    changed[0] = true;
                }
            });
            return changed[0];
        });
        return modified ? 1 : 0;
    }

    // -----------------------------------------------------------------------
    // ADD METHOD
    // -----------------------------------------------------------------------

    private int handleAddMethod(JsonObject op, Java2GraphConfig config) throws IOException {
        String classFqn = op.get("classFqn").getAsString();
        String newMethodSource = op.get("newMethodSource").getAsString();

        Path path = findFileForClass(config.getSrcDir(), classFqn);
        if (path == null)
            throw new RuntimeException("Could not find file for FQN: " + classFqn);

        String className = extractSimpleName(classFqn);
        boolean modified = modifyFile(path, cu -> {
            boolean[] changed = {false};
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cd -> {
                if (cd.getNameAsString().equals(className)) {
                    var newMethod = StaticJavaParser.parseMethodDeclaration(newMethodSource);
                    cd.addMember(newMethod);

                    // Handle imports
                    addExplicitImports(cu, op);
                    autoResolveImports(cu, newMethod);

                    changed[0] = true;
                }
            });
            return changed[0];
        });
        return modified ? 1 : 0;
    }

    // -----------------------------------------------------------------------
    // CREATE CLASS
    // -----------------------------------------------------------------------

    private int handleCreateClass(JsonObject op, Java2GraphConfig config) throws IOException {
        String filePath = op.get("filePath").getAsString();
        String source = op.get("source").getAsString();

        Path path = config.getSrcDir().resolve(filePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
        logger.info("Created new class at {}", path);
        return 1;
    }

    // -----------------------------------------------------------------------
    // DELETE NODE
    // -----------------------------------------------------------------------

    private int handleDeleteNode(JsonObject op, Java2GraphConfig config) throws IOException {
        String fqn = op.get("fqn").getAsString();
        String nodeType = resolveNodeType(op, fqn);

        Path path;
        if ("class".equals(nodeType)) {
            path = findFileForClass(config.getSrcDir(), fqn);
        } else {
            path = findFileForMethod(config.getSrcDir(), fqn);
        }
        if (path == null)
            throw new RuntimeException("Could not find file for FQN: " + fqn);

        String simpleName = extractSimpleName(fqn);
        boolean modified = modifyFile(path, cu -> {
            boolean[] changed = {false};
            if ("class".equals(nodeType)) {
                cu.findAll(TypeDeclaration.class).forEach(td -> {
                    if (td.getNameAsString().equals(simpleName)) {
                        td.remove();
                        changed[0] = true;
                    }
                });
            } else {
                cu.findAll(MethodDeclaration.class).forEach(md -> {
                    if (md.getNameAsString().equals(simpleName)) {
                        md.remove();
                        changed[0] = true;
                    }
                });
            }
            return changed[0];
        });
        return modified ? 1 : 0;
    }

    // -----------------------------------------------------------------------
    // Import Management
    // -----------------------------------------------------------------------

    private void addExplicitImports(CompilationUnit cu, JsonObject op) {
        if (op.has("imports") && op.get("imports").isJsonArray()) {
            JsonArray imports = op.getAsJsonArray("imports");
            for (JsonElement imp : imports) {
                String importFqn = imp.getAsString();
                if (!cu.getImports().stream().anyMatch(i -> i.getNameAsString().equals(importFqn))) {
                    cu.addImport(importFqn);
                    logger.info("Added explicit import: {}", importFqn);
                }
            }
        }
    }

    private void autoResolveImports(CompilationUnit cu, Node newNode) {
        if (db == null) return; // Need graph to resolve names

        Set<String> typesInNode = new HashSet<>();
        newNode.findAll(ClassOrInterfaceType.class).forEach(type -> {
            // Only look at simple names (not FQNs which don't need imports)
            if (!type.getNameAsString().contains(".")) {
                typesInNode.add(type.getNameAsString());
            }
        });

        for (String typeName : typesInNode) {
            // Skip if already imported or in the same package
            if (isTypeAlreadyAvailable(cu, typeName)) continue;

            // Query graph for the FQN of this class name
            String escaped = escapeForCypher(typeName);
            List<String> fqns = queryGraphForStrings("MATCH (c:Class {name: '" + escaped + "'}) RETURN c.fqn");

            if (fqns.size() == 1) {
                String fqn = fqns.get(0);
                cu.addImport(fqn);
                logger.info("Auto-resolved and added import for {}: {}", typeName, fqn);
            } else if (fqns.size() > 1) {
                logger.warn("Ambiguous type '{}' found in graph ({} matches). Skipping auto-import.", typeName, fqns.size());
            }
        }
    }

    private boolean isTypeAlreadyAvailable(CompilationUnit cu, String typeName) {
        // Check explicit imports
        if (cu.getImports().stream().anyMatch(i -> i.getNameAsString().endsWith("." + typeName))) return true;
        
        // Check java.lang (implicit)
        List<String> javaLangClasses = Arrays.asList("String", "Integer", "Long", "Double", "Boolean", "Object", "System", "Exception", "RuntimeException");
        if (javaLangClasses.contains(typeName)) return true;

        return false;
    }

    // -----------------------------------------------------------------------
    // File modification with change tracking
    // -----------------------------------------------------------------------

    @FunctionalInterface
    private interface AstModifier {
        boolean apply(CompilationUnit cu);
    }

    /**
     * Parses a file, applies the modifier, and writes back only if the AST changed.
     * Returns true if the file was actually modified.
     */
    private boolean modifyFile(Path path, AstModifier modifier) throws IOException {
        return modifyFileWithStringFallback(path, null, null, modifier);
    }

    /**
     * Like modifyFile, but also applies a post-LPP string replacement pass for class renames.
     * LexicalPreservingPrinter has known bugs where it fails to reflect certain AST changes
     * (especially generic type arguments like List<OldName>). The string fallback catches these.
     */
    private boolean modifyFileWithStringFallback(Path path, String oldName, String newName, AstModifier modifier) throws IOException {
        String originalCode = Files.readString(path);

        // Create backup if it doesn't exist yet
        Path bakPath = Paths.get(path.toString() + ".bak");
        if (!Files.exists(bakPath)) {
            Files.writeString(bakPath, originalCode);
        }

        CompilationUnit cu = StaticJavaParser.parse(originalCode);
        LexicalPreservingPrinter.setup(cu);

        boolean changed = modifier.apply(cu);

        if (!changed) {
            logger.debug("No AST changes detected in file: {}", path);
            return false;
        }

        String modifiedCode = LexicalPreservingPrinter.print(cu);

        // Post-LPP string fallback: fix references that LPP failed to update
        // (e.g. generic type arguments like List<OldName>, Map<String, OldName>)
        // Uses word-boundary-aware regex so we don't match partial names
        if (oldName != null && newName != null && modifiedCode.contains(oldName)) {
            String pattern = "(?<![\\w.])" + Pattern.quote(oldName) + "(?![\\w])";
            String fixed = modifiedCode.replaceAll(pattern, newName);
            if (!fixed.equals(modifiedCode)) {
                logger.debug("Post-LPP string fallback applied for '{}' -> '{}' in {}", oldName, newName, path);
                modifiedCode = fixed;
            }
        }

        // Double check: don't write if string content is identical
        if (modifiedCode.equals(originalCode)) {
            logger.debug("AST reported changes but output is identical for file: {}", path);
            return false;
        }

        Files.writeString(path, modifiedCode);
        logger.info("Modified file: {}", path);
        return true;
    }

    // -----------------------------------------------------------------------
    // Graph-driven file discovery
    // -----------------------------------------------------------------------

    private void initGraphConnection(Java2GraphConfig config) {
        Path dbPath = config.getDbPath();
        if (dbPath == null) {
            logger.warn("No --db path provided. Running in single-file mode (no cross-file updates).");
            return;
        }
        if (!Files.exists(dbPath)) {
            logger.warn("Database path {} does not exist. Running in single-file mode.", dbPath);
            return;
        }
        try {
            db = new Database(dbPath.toString());
            logger.info("Connected to code graph at {}", dbPath);
        } catch (Exception e) {
            logger.warn("Failed to connect to code graph at {}: {}. Running in single-file mode.", dbPath, e.getMessage());
            if (db != null) {
                try { db.close(); } catch (Exception closeEx) { /* ignore */ }
            }
            db = null;
        }
    }

    private void closeGraphConnection() {
        if (db != null) {
            try { db.close(); } catch (Exception e) { /* ignore */ }
        }
        db = null;
    }

    private List<String> queryGraphForStrings(String cypher) {
        List<String> results = new ArrayList<>();
        if (db == null) return results;
        try (Connection localConn = new Connection(db);
             QueryResult res = localConn.query(cypher)) {
            if (!res.isSuccess()) {
                logger.warn("Graph query failed: {} | Query: {}", res.getErrorMessage(), cypher);
                return results;
            }
            while (res.hasNext()) {
                try (FlatTuple t = res.getNext()) {
                    Object val = t.getValue(0).getValue();
                    if (val != null) {
                        results.add(val.toString());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Graph query error: {} | Query: {}", e.getMessage(), cypher);
        }
        return results;
    }

    /**
     * Find all files that reference a class — uses graph if available, falls back to filesystem walk.
     */
    private Set<Path> findFilesReferencingClass(Path srcDir, String classFqn) {
        Set<Path> files = new LinkedHashSet<>();
        String simpleName = extractSimpleName(classFqn);
        String escaped = escapeForCypher(classFqn);

        if (db != null) {
            // Files containing methods that call methods of this class
            List<String> callerFiles = queryGraphForStrings(
                "MATCH (c:Class {fqn: '" + escaped + "'})-[:Defines]->(m:Method)<-[:Calls]-(caller:Method) " +
                "WHERE caller.filePath <> '' RETURN DISTINCT caller.filePath");
            for (String f : callerFiles) {
                Path p = resolveFilePath(srcDir, f);
                if (p != null) files.add(p);
            }

            // Files containing subclasses
            List<String> subclassFiles = queryGraphForStrings(
                "MATCH (child:Class)-[:Extends|Implements]->(parent:Class {fqn: '" + escaped + "'}) " +
                "WHERE child.filePath <> '' RETURN DISTINCT child.filePath");
            for (String f : subclassFiles) {
                Path p = resolveFilePath(srcDir, f);
                if (p != null) files.add(p);
            }
        }

        // Fallback / supplement: walk all .java files looking for usage of the simple name
        try (var walk = Files.walk(srcDir)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !files.contains(p)) // skip files already found
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        if (content.contains(simpleName)) {
                            files.add(p);
                        }
                    } catch (IOException e) {
                        // skip
                    }
                });
        } catch (IOException e) {
            logger.warn("Failed to walk source directory: {}", e.getMessage());
        }

        return files;
    }

    /**
     * Find all files that call a given method — uses graph if available, falls back to filesystem walk.
     */
    private Set<Path> findFilesCallingMethod(Path srcDir, String methodFqn) {
        Set<Path> files = new LinkedHashSet<>();
        String methodName = extractSimpleName(methodFqn);
        String escaped = escapeForCypher(methodFqn);

        if (db != null) {
            List<String> callerFiles = queryGraphForStrings(
                "MATCH (m:Method {fqn: '" + escaped + "'})<-[:Calls]-(caller:Method) " +
                "WHERE caller.filePath <> '' RETURN DISTINCT caller.filePath");
            for (String f : callerFiles) {
                Path p = resolveFilePath(srcDir, f);
                if (p != null) files.add(p);
            }
        }

        // Fallback / supplement
        try (var walk = Files.walk(srcDir)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !files.contains(p))
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        if (content.contains(methodName)) {
                            files.add(p);
                        }
                    } catch (IOException e) {
                        // skip
                    }
                });
        } catch (IOException e) {
            logger.warn("Failed to walk source directory: {}", e.getMessage());
        }

        return files;
    }

    // -----------------------------------------------------------------------
    // File path resolution
    // -----------------------------------------------------------------------

    /**
     * Find the file for a class FQN. Uses graph first, then convention, then filesystem walk.
     */
    private Path findFileForClass(Path srcDir, String classFqn) {
        // 1. Try graph
        if (db != null) {
            String escaped = escapeForCypher(classFqn);
            List<String> paths = queryGraphForStrings(
                "MATCH (c:Class {fqn: '" + escaped + "'}) RETURN c.filePath");
            for (String f : paths) {
                Path p = resolveFilePath(srcDir, f);
                if (p != null) return p;
            }
        }
        // 2. Convention: com.foo.Bar -> com/foo/Bar.java
        return findFileByConventionOrWalk(srcDir, classFqn);
    }

    /**
     * Find the file for a method FQN. Uses graph first, then derives class FQN and falls back.
     */
    private Path findFileForMethod(Path srcDir, String methodFqn) {
        // 1. Try graph (Method node has filePath)
        if (db != null) {
            String escaped = escapeForCypher(methodFqn);
            List<String> paths = queryGraphForStrings(
                "MATCH (m:Method {fqn: '" + escaped + "'}) RETURN m.filePath");
            for (String f : paths) {
                Path p = resolveFilePath(srcDir, f);
                if (p != null) return p;
            }
        }
        // 2. Derive class FQN from method FQN
        String classFqn = deriveClassFqn(methodFqn);
        if (classFqn != null && !classFqn.isEmpty()) {
            return findFileForClass(srcDir, classFqn);
        }
        // 3. Walk
        String simpleName = extractSimpleName(methodFqn);
        return walkForFile(srcDir, simpleName);
    }

    private Path findFileByConventionOrWalk(Path srcDir, String fqn) {
        String relPath = fqn.replace('.', '/') + ".java";
        Path path = srcDir.resolve(relPath);
        if (Files.exists(path)) return path;

        String simpleName = extractSimpleName(fqn);
        return walkForFile(srcDir, simpleName);
    }

    private Path walkForFile(Path srcDir, String simpleName) {
        // 1. Try to find an exact file name match first (e.g. MyClass.java)
        try (var walk = Files.walk(srcDir)) {
            Optional<Path> exact = walk.filter(p -> p.getFileName().toString().equals(simpleName + ".java")).findFirst();
            if (exact.isPresent()) return exact.get();
        } catch (IOException e) {
            // ignore
        }

        // 2. Fallback: find any file that CONTAINS the word and looks like a declaration
        try (var walk = Files.walk(srcDir)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String content = Files.readString(p);
                            if (!content.contains(simpleName)) return false;
                            
                            // Heuristic to check if it's likely a declaration rather than just a caller
                            String regex = "(?s).*\\b(?:class|interface|enum|record)\\s+" + simpleName + "\\b.*" +
                                           "|.*\\b" + simpleName + "\\s*\\(.*";
                            return content.matches(regex);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Path resolveFilePath(Path srcDir, String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        Path p = Paths.get(filePath);
        if (p.isAbsolute() && Files.exists(p)) return p;
        Path resolved = srcDir.resolve(filePath);
        if (Files.exists(resolved)) return resolved;
        return null;
    }

    // -----------------------------------------------------------------------
    // Utility methods
    // -----------------------------------------------------------------------

    private String extractSimpleName(String fqn) {
        if (fqn.contains("(")) {
            String beforeParen = fqn.substring(0, fqn.indexOf('('));
            return beforeParen.contains(".") ? beforeParen.substring(beforeParen.lastIndexOf('.') + 1) : beforeParen;
        }
        return fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
    }

    private String deriveClassFqn(String methodFqn) {
        // "com.foo.Bar.baz(int)" -> "com.foo.Bar"
        String base = methodFqn.contains("(") ? methodFqn.substring(0, methodFqn.indexOf('(')) : methodFqn;
        int lastDot = base.lastIndexOf('.');
        return lastDot > 0 ? base.substring(0, lastDot) : null;
    }

    private String escapeForCypher(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private void createGitCheckpoint(Path srcDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "commit", "-am", "decypher pre-mutation checkpoint");
            pb.directory(srcDir.toFile());
            pb.inheritIO();
            Process p = pb.start();
            p.waitFor();
        } catch (Exception e) {
            logger.warn("Failed to create git checkpoint: {}", e.getMessage());
        }
    }
}
