package com.neuvem.java2graph.passes;

import com.neuvem.java2graph.Java2GraphConfig;
import com.neuvem.java2graph.models.GraphContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExportPassDirectoryTest {

    @Test
    void testCleanDatabaseDirectoryOnExport(@TempDir Path tempRoot) throws Exception {
        // Setup existing target directory imitating an old ladybug db run
        Path outDbPath = tempRoot.resolve("benchmarks/kafka");
        Files.createDirectories(outDbPath);

        // ExportPass appends 'decypher.db' if the extension is not .db
        Path expectedDecypherDb = outDbPath.resolve("decypher.db");
        Files.createDirectories(expectedDecypherDb);
        Path mockOldFile = expectedDecypherDb.resolve("old_data.bin");
        Files.writeString(mockOldFile, "corrupted_graph_history");

        assertThat(Files.exists(mockOldFile)).isTrue();

        Java2GraphConfig config = Java2GraphConfig.builder()
                .outDbPath(outDbPath)
                .build();
        
        GraphContext context = new GraphContext();

        ExportPass exportPass = new ExportPass();
        
        try {
            exportPass.execute(config, context);
        } catch (Exception e) {
            // It will crash creating the Ladybug Connection because Lbug JNI isn't present
            // in standard pure unit testing without Kuzu core, but the pre-wipe logic 
            // MUST execute perfectly before the crash.
        }

        // Verify the old data was wiped exactly at the decypher.db target!
        assertThat(Files.exists(mockOldFile)).isFalse();
        // The directory itself is recreated by LadyBug, but we just verify the file wipe
    }
}
