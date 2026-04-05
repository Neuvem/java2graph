package com.neuvem.java2graph.passes;

import com.neuvem.java2graph.Java2GraphConfig;
import com.neuvem.java2graph.models.GraphContext;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

public class DelombokPass implements Pass {

    @Override
    public void execute(Java2GraphConfig config, GraphContext context) throws Exception {
        if (!config.isEnableLombok()) {
            return;
        }

        System.out.println("Processing Lombok annotations...");

        Path delombokDir = Files.createTempDirectory("delombok");
        String[] args = {
                "delombok",
                config.getSrcDir().toAbsolutePath().toString(),
                "-d",
                delombokDir.toAbsolutePath().toString()
        };

        Class<?> mainClass = Class.forName("lombok.launch.Main");
        Method mainMethod = mainClass.getDeclaredMethod("main", String[].class);
        mainMethod.setAccessible(true);
        mainMethod.invoke(null, (Object) args);

        // Update the src directory for subsequent passes
        config.setSrcDir(delombokDir);
        
        System.out.println("Lombok processing complete. Delomboked to: " + delombokDir);
    }
}
