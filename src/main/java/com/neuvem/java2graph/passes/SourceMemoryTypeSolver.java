package com.neuvem.java2graph.passes;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A TypeSolver backed by a FQN → file path index with LRU caching.
 * Instead of holding all CompilationUnits in memory, it lazily parses
 * files on-demand and caches the most recently used ones.
 */
public class SourceMemoryTypeSolver implements TypeSolver {

    private TypeSolver parent;
    private final Map<String, Path> fqnToPath;
    private final Map<String, CompilationUnit> cuCache;
    private static final int CACHE_SIZE = 100;

    public SourceMemoryTypeSolver(Map<String, Path> fqnToPath) {
        this.fqnToPath = fqnToPath;
        // LRU cache: evicts eldest entry when capacity exceeded
        this.cuCache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CompilationUnit> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }

    @Override
    public TypeSolver getParent() {
        return parent;
    }

    @Override
    public void setParent(TypeSolver parent) {
        this.parent = parent;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        // Check if we even know about this type
        Path filePath = fqnToPath.get(name);
        if (filePath == null) {
            return SymbolReference.unsolved(ResolvedReferenceTypeDeclaration.class);
        }

        // Try cache first
        CompilationUnit cu = cuCache.get(name);
        if (cu == null) {
            // Cache miss: parse the file lazily
            cu = parseLazily(filePath);
            if (cu != null) {
                cuCache.put(name, cu);
            }
        }

        if (cu != null) {
            @SuppressWarnings("unchecked")
            Optional<TypeDeclaration<?>> typeDeclaration = (Optional<TypeDeclaration<?>>)(Object) cu.findFirst(TypeDeclaration.class, t -> {
                Optional<String> fqn = t.getFullyQualifiedName();
                return fqn.isPresent() && fqn.get().equals(name);
            });

            if (typeDeclaration.isPresent()) {
                return SymbolReference.solved(JavaParserFacade.get(this).getTypeDeclaration(typeDeclaration.get()));
            }
        }

        return SymbolReference.unsolved(ResolvedReferenceTypeDeclaration.class);
    }

    private CompilationUnit parseLazily(Path filePath) {
        try {
            ParserConfiguration config = new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                    .setStoreTokens(false)
                    .setAttributeComments(false);
            JavaParser parser = new JavaParser(config);
            ParseResult<CompilationUnit> result = parser.parse(filePath);
            return result.getResult().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
