# Contributing to Java2Graph

First off, thank you for considering contributing to Java2Graph! 

## Development Setup

The repository is built strictly using **Java 17** and **Maven**.

### Local Environment
Ensure you have the following installed:
1. **JDK 17** (Temurin/Eclipse Adoptium is heavily recommended)
2. **Maven 3.6+**

To bootstrap the local IDE bindings and download the required Picocli and JavaParser dependencies:
```bash
mvn clean install -DskipTests
```

## Architecture

Java2Graph works by pipelining a raw source directory through a rigid sequence of strictly uncoupled Passes.
If you are adding a new feature, find the corresponding pass:

1. **`DelombokPass`**: Intercepts the raw filesystem. Detects true `src/main/java` roots and isolates individual executions of Lombok, copying missing original files as a fail-safe.
2. **`ParsePass`**: Boots the `JavaParser` module and streams the Java codebase sequentially into raw `CompilationUnit` AST states.
3. **`ResolvePass`**: The heavy lifter. Crawls the `CompilationUnit` models using O(1) TokenRange heuristic maps and/or SymbolSolvers to deduce `Implements` and `MethodCall` edges.
4. **`ExportPass`**: Enforces strict directory wiping protocols and writes the `GraphContext` memory-state sequentially into `.csv` datasets, instructing `LadybugDB` to bulk-ingest them.

### Data Integrity

If you must modify the structural POJOs like `InheritanceEdge` or `MethodCallEdge` mapped inside `GraphContext`:
1. **Do not remove `Objects.hash()`/`equals()`**: The global memory structures rely on exact identity equality within `java.util.Set` collections to deduplicate overlapping multi-module source discoveries. 

## Testing Protocols

Java2Graph includes an automated **JUnit 5 (Jupiter)** and **JaCoCo** metrics suite. All functional pull requests must pass the core suite.

To execute the test suite locally:
```bash
mvn clean test
```

To view the coverage footprint:
```bash
mvn jacoco:report
```
*Note: We heavily favor fully synthetic headless AST verification Tests over testing raw Database/Kuzu bindings, as it allows GitHub CI/CD to run OS-agnostic testing pipelines.*

## Submitting a Pull Request
1. Fork the repo.
2. Cut a new branch explicitly scoped to your feature (e.g., `feature/custom-edges`).
3. Ensure the project builds cleanly, and `mvn clean test` clears.
4. Push your branch and open a definitive Pull Request. We will review it shortly!
