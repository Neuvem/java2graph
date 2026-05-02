package com.neuvem.java2graph;

import java.nio.file.Path;
import java.util.List;

public class Java2GraphConfig {
    private Path srcDir;
    private List<Path> jarPaths;
    private Path outCsvDir;
    private Path outDbPath;
    private int threads;
    private boolean enableLombok;
    private boolean fastResolve;
    private Path cacheDir;
    public enum DecompilerType {
        CFR,
        VINEFLOWER
    }

    private boolean indexAllJarEntries = true;
    private boolean decompile = true;
    private DecompilerType decompilerType = DecompilerType.CFR;
    private List<Path> incrementalFiles;
    private List<Path> incrementalJars;

    public Java2GraphConfig() {}

    public Path getSrcDir() { return srcDir; }
    public void setSrcDir(Path srcDir) { this.srcDir = srcDir; }

    public List<Path> getJarPaths() { return jarPaths; }
    public void setJarPaths(List<Path> jarPaths) { this.jarPaths = jarPaths; }

    public Path getOutCsvDir() { return outCsvDir; }
    public void setOutCsvDir(Path outCsvDir) { this.outCsvDir = outCsvDir; }

    public Path getOutDbPath() { return outDbPath; }
    public void setOutDbPath(Path outDbPath) { this.outDbPath = outDbPath; }

    public int getThreads() { return threads; }
    public void setThreads(int threads) { this.threads = threads; }

    public boolean isEnableLombok() { return enableLombok; }
    public void setEnableLombok(boolean enableLombok) { this.enableLombok = enableLombok; }

    public boolean isFastResolve() { return fastResolve; }
    public void setFastResolve(boolean fastResolve) { this.fastResolve = fastResolve; }

    public Path getCacheDir() { return cacheDir; }
    public void setCacheDir(Path cacheDir) { this.cacheDir = cacheDir; }

    public boolean isIndexAllJarEntries() { return indexAllJarEntries; }
    public void setIndexAllJarEntries(boolean indexAllJarEntries) { this.indexAllJarEntries = indexAllJarEntries; }

    public boolean isDecompile() { return decompile; }
    public void setDecompile(boolean decompile) { this.decompile = decompile; }

    public DecompilerType getDecompilerType() { return decompilerType; }
    public void setDecompilerType(DecompilerType decompilerType) { this.decompilerType = decompilerType; }

    public List<Path> getIncrementalFiles() { return incrementalFiles; }
    public void setIncrementalFiles(List<Path> incrementalFiles) { this.incrementalFiles = incrementalFiles; }

    public List<Path> getIncrementalJars() { return incrementalJars; }
    public void setIncrementalJars(List<Path> incrementalJars) { this.incrementalJars = incrementalJars; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Java2GraphConfig config = new Java2GraphConfig();
        public Builder srcDir(Path srcDir) { config.srcDir = srcDir; return this; }
        public Builder jarPaths(List<Path> jarPaths) { config.jarPaths = jarPaths; return this; }
        public Builder outCsvDir(Path outCsvDir) { config.outCsvDir = outCsvDir; return this; }
        public Builder outDbPath(Path outDbPath) { config.outDbPath = outDbPath; return this; }
        public Builder threads(int threads) { config.threads = threads; return this; }
        public Builder enableLombok(boolean enableLombok) { config.enableLombok = enableLombok; return this; }
        public Builder fastResolve(boolean fastResolve) { config.fastResolve = fastResolve; return this; }
        public Builder cacheDir(Path cacheDir) { config.cacheDir = cacheDir; return this; }
        public Builder indexAllJarEntries(boolean indexAllJarEntries) { config.indexAllJarEntries = indexAllJarEntries; return this; }
        public Builder decompile(boolean decompile) { config.decompile = decompile; return this; }
        public Builder decompilerType(DecompilerType decompilerType) { config.decompilerType = decompilerType; return this; }
        public Builder incrementalFiles(List<Path> incrementalFiles) { config.incrementalFiles = incrementalFiles; return this; }
        public Builder incrementalJars(List<Path> incrementalJars) { config.incrementalJars = incrementalJars; return this; }
        public Java2GraphConfig build() { return config; }
    }
}
