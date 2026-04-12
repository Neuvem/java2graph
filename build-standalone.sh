#!/bin/bash
set -e

echo "Building standalone application..."
mvn clean package -DskipTests

echo "Finding required JDK modules..."
# Always include java.sql and other key modules, then append jdeps results
MODULES=$(jdeps --ignore-missing-deps -q --multi-release 17 --print-module-deps target/java2graph-1.0-SNAPSHOT-jar-with-dependencies.jar || echo "java.base")
MODULES="${MODULES},java.sql,java.compiler,java.desktop,java.instrument,java.management,java.logging,jdk.attach,jdk.jdi,jdk.unsupported,jdk.compiler,jdk.zipfs"
echo "Modules required: $MODULES"

rm -rf custom-jre dist

echo "Creating custom minimal JRE..."
jlink --add-modules "$MODULES" --bind-services --strip-debug --no-man-pages --no-header-files --compress=2 --output custom-jre

jpackage --type app-image --name java2graph --input target --main-jar java2graph-1.0-SNAPSHOT-jar-with-dependencies.jar --main-class com.neuvem.java2graph.Main --runtime-image custom-jre --dest dist --java-options "-Xmx8G"

echo "Done! Standalone binary created at dist/"
