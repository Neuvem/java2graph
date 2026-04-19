package com.neuvem.java2graph.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class DecompileCache {
    private final Path cacheDir;

    public DecompileCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            System.err.println("Warning: Could not create cache directory: " + e.getMessage());
        }
    }

    public Optional<String> get(String fqn) {
        Path filePath = getPath(fqn);
        if (Files.exists(filePath)) {
            try {
                return Optional.of(Files.readString(filePath));
            } catch (IOException e) {
                System.err.println("Warning: Could not read cache for " + fqn + ": " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    public void put(String fqn, String source) {
        Path filePath = getPath(fqn);
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, source);
        } catch (IOException e) {
            System.err.println("Warning: Could not write cache for " + fqn + ": " + e.getMessage());
        }
    }

    public Path getPath(String fqn) {
        // Replace . with / and add .java extension
        String relativePath = fqn.replace('.', '/') + ".java";
        return cacheDir.resolve(relativePath);
    }
}
