package com.neuvem.java2graph.passes;

import com.neuvem.java2graph.Java2GraphConfig;
import com.neuvem.java2graph.models.GraphContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class DelombokPass implements Pass {
    private static final Logger logger = LogManager.getLogger(DelombokPass.class);

    @Override
    public void execute(Java2GraphConfig config, GraphContext context) throws Exception {
        if (!config.isEnableLombok()) {
            return;
        }

        logger.info("Processing Lombok annotations...");

        Path delombokDir = Files.createTempDirectory("delombok");
        String javaHome = System.getProperty("java.home");
        String javaExecutable = javaHome + "/bin/java";
        
        // Build the comprehensive classpath (Tool CP + Project Dependencies)
        StringBuilder fullClassPath = new StringBuilder(System.getProperty("java.class.path"));
        if (config.getJarPaths() != null) {
            for (Path jarPath : config.getJarPaths()) {
                collectJars(jarPath, fullClassPath);
            }
        }

        // 1. Package-Aware Source Root Discovery: Find the 'True Roots' (e.g., src/main/java)
        // This is the only 100% accurate way to avoid "wrong package" errors in multi-module projects.
        java.util.Set<Path> sourceRoots = new java.util.HashSet<>();
        try (var walk = Files.walk(config.getSrcDir(), 14)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> {
                    String s = p.toString().replace('\\', '/');
                    return !s.contains("/build/") && !s.contains("/target/") && 
                           !s.contains("/out/") && !s.contains("/bin/") && 
                           !s.contains("/.gradle/") && !s.contains("/.git/") &&
                           !s.contains("/.idea/") && !s.contains("/.java2graph/");
                })
                .forEach(javaFile -> {
                    try {
                        Path root = findSourceRoot(javaFile);
                        if (root != null) sourceRoots.add(root);
                    } catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}

        // Fallback: If no roots are discovered, use the project root
        if (sourceRoots.isEmpty()) {
            sourceRoots.add(config.getSrcDir());
        }

        logger.info("Executing Mirrored Delombok on {} True Roots...", sourceRoots.size());
        for (Path root : sourceRoots) {
            logger.info("  Found True Root: {}", config.getSrcDir().relativize(root));
        }

        // Build a global sourcepath containing all true roots
        StringBuilder globalSourcePath = new StringBuilder();
        int rootIndex = 0;
        for (Path root : sourceRoots) {
            if (rootIndex++ > 0) globalSourcePath.append(File.pathSeparator);
            globalSourcePath.append(root.toAbsolutePath());
        }

        // logger.info("Executing Mirrored Delombok on {} roots...", sourceRoots.size());

        // Discover the Lombok JAR in the current tool classpath to isolate the Delombok process
        String lombokJar = null;
        for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (path.toLowerCase().contains("lombok-1.18") || path.toLowerCase().contains("lombok.jar")) {
                lombokJar = path;
                break;
            }
        }
        String workerCP = (lombokJar != null) ? lombokJar : System.getProperty("java.class.path");
        if (lombokJar != null) {
            logger.info("  Isolated Delombok Engine: {}", new File(lombokJar).getName());
        }

        for (Path root : sourceRoots) {
            Path relativePath = config.getSrcDir().relativize(root);
            Path targetDir = delombokDir.resolve(relativePath);
            Files.createDirectories(targetDir);

            // Filter files for this specific root
            List<String> javaFiles = new ArrayList<>();
            try (var walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .forEach(javaFiles::add);
            }
            if (javaFiles.isEmpty()) continue;

            // Restore CLI compatibility: Put only filenames in the @args-file
            Path javaFilesListFile = Files.createTempFile("delombok_files", ".txt");
            Files.write(javaFilesListFile, javaFiles);

            // Phase 1 (JVM Args): Comprehensive modularity for JDK 21/23/25
            List<String> command = new ArrayList<>();
            command.add(javaExecutable);
            command.add("-Xmx2g");
            command.add("-Dlombok.permitReflection=true");
            command.add("--add-modules=ALL-SYSTEM");

            // Add selective modularity flags for modern JDK compatibility
            String version = System.getProperty("java.specification.version");
            try {
                double v = Double.parseDouble(version);
                if (v >= 16) {
                    String[] modules = {
                        "jdk.compiler/com.sun.tools.javac.api", "jdk.compiler/com.sun.tools.javac.code",
                        "jdk.compiler/com.sun.tools.javac.comp", "jdk.compiler/com.sun.tools.javac.file",
                        "jdk.compiler/com.sun.tools.javac.jvm", "jdk.compiler/com.sun.tools.javac.main",
                        "jdk.compiler/com.sun.tools.javac.model", "jdk.compiler/com.sun.tools.javac.parser",
                        "jdk.compiler/com.sun.tools.javac.processing", "jdk.compiler/com.sun.tools.javac.tree",
                        "jdk.compiler/com.sun.tools.javac.util"
                    };
                    for (String mod : modules) {
                        command.add("--add-opens=" + mod + "=ALL-UNNAMED");
                        command.add("--add-exports=" + mod + "=ALL-UNNAMED");
                    }
                    command.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
                    command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
                    command.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
                    command.add("--add-opens=java.base/java.lang.invoke=ALL-UNNAMED");
                    command.add("--add-opens=java.base/java.lang.constant=ALL-UNNAMED"); // JDK 25+
                    command.add("--add-opens=java.base/java.io=ALL-UNNAMED");
                    command.add("--add-opens=java.base/java.net=ALL-UNNAMED");
                    command.add("--add-opens=java.base/java.text=ALL-UNNAMED");
                    command.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
                    command.add("--add-opens=java.base/java.util.concurrent=ALL-UNNAMED");
                    command.add("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED");
                    command.add("--add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED"); // JDK 21+
                    command.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
                }
            } catch (NumberFormatException ignored) {}

            if (lombokJar != null) {
                command.add("-javaagent:" + lombokJar); 
                command.add("-jar");                    
                command.add(lombokJar);
            } else {
                command.add("-cp");
                command.add(System.getProperty("java.class.path"));
                command.add("lombok.launch.Main");
            }

            // Phase 3 & 4: Application command and options
            command.add("delombok");
            command.add("-q"); // Suppress Javac diagnostic output that can crash the runner
            command.add("-d");
            command.add(targetDir.toAbsolutePath().toString());
            // Intentionally not passing -s globalSourcePath to prevent JavaCompiler from 
            // implicitly reading and crashing on unparsable dependencies from other roots.
            command.add("-c");
            command.add(fullClassPath.toString());
            command.add("--nocopy");
            command.add("@" + javaFilesListFile.toAbsolutePath().toString());

            logger.info("  Delombok-ing root: {} ({} files)", relativePath, javaFiles.size());
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

        // Capture full output for debugging
        StringBuilder taskOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                taskOutput.append(line).append("\n");
                // Intentionally suppressed real-time error printing to reduce console noise
            }
        }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("    [Lombok] WARNING: Delombok reported compilation errors for root {} (exit code {}). Output will normally be best-effort.", relativePath, exitCode);
                // We intentionally DO NOT throw an exception here because Delombok correctly generates
                // output files even when Javac encounters missing symbols or unresolved dependencies.
            }
            Files.deleteIfExists(javaFilesListFile);

            // Fallback: If Delombok completely crashed on a specific file and dropped it, 
            // manually copy the original file into the target directory so we never lose symbols.
            for (String originalFilePath : javaFiles) {
                Path original = Path.of(originalFilePath);
                Path expectedOutput = targetDir.resolve(root.relativize(original));
                if (!Files.exists(expectedOutput)) {
                    Files.createDirectories(expectedOutput.getParent());
                    Files.copy(original, expectedOutput);
                }
            }
        }

        // Update the src directory for subsequent passes
        config.setSrcDir(delombokDir);
        logger.info("Lombok processing complete. Mirrored to: {}", delombokDir);
    }

    private void collectJars(Path path, StringBuilder cp) {
        if (Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.filter(p -> p.toString().endsWith(".jar"))
                    .forEach(p -> cp.append(File.pathSeparator).append(p.toAbsolutePath()));
            } catch (Exception ignored) {}
        } else if (path.toString().endsWith(".jar")) {
            cp.append(File.pathSeparator).append(path.toAbsolutePath());
        }
    }

    private Path findSourceRoot(Path javaFile) {
        try {
            // Read first 8KB to find package declaration safely
            byte[] buffer = new byte[8192];
            try (var is = Files.newInputStream(javaFile)) {
                int read = is.read(buffer);
                if (read <= 0) return javaFile.getParent();
                String content = new String(buffer, 0, read);
                
                // Regex matches 'package' at start of line or after newline (ignoring comments)
                java.util.regex.Pattern pkgPattern = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][a-zA-Z0-9_\\.]*)\\s*;");
                java.util.regex.Matcher matcher = pkgPattern.matcher(content);
                if (matcher.find()) {
                    String pkgName = matcher.group(1);
                    String[] parts = pkgName.split("\\.");
                    Path root = javaFile.getParent();
                    for (int i = 0; i < parts.length; i++) {
                        if (root != null) root = root.getParent();
                    }
                    return root;
                }
            }
        } catch (Exception ignored) {}
        return javaFile.getParent();
    }
}
