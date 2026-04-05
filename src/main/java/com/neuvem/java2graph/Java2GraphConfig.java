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

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Java2GraphConfig config = new Java2GraphConfig();
        public Builder srcDir(Path srcDir) { config.srcDir = srcDir; return this; }
        public Builder jarPaths(List<Path> jarPaths) { config.jarPaths = jarPaths; return this; }
        public Builder outCsvDir(Path outCsvDir) { config.outCsvDir = outCsvDir; return this; }
        public Builder outDbPath(Path outDbPath) { config.outDbPath = outDbPath; return this; }
        public Builder threads(int threads) { config.threads = threads; return this; }
        public Builder enableLombok(boolean enableLombok) { config.enableLombok = enableLombok; return this; }
        public Java2GraphConfig build() { return config; }
    }
}
