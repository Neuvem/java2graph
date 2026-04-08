package com.neuvem.java2graph.passes;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;

import java.util.Map;
import java.util.Optional;

public class SourceMemoryTypeSolver implements TypeSolver {

    private TypeSolver parent;
    private final Map<String, CompilationUnit> classToIndex;

    public SourceMemoryTypeSolver(Map<String, CompilationUnit> classToIndex) {
        this.classToIndex = classToIndex;
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
        CompilationUnit cu = classToIndex.get(name);
        if (cu != null) {
            // Instant O(1) matching using short-circuit findFirst instead of array-based findAll
            @SuppressWarnings("unchecked")
            Optional<TypeDeclaration<?>> typeDeclaration = (Optional<TypeDeclaration<?>>)(Object) cu.findFirst(TypeDeclaration.class, t -> {
                Optional<String> fqn = t.getFullyQualifiedName();
                return fqn.isPresent() && fqn.get().equals(name);
            });

            if (typeDeclaration.isPresent()) {
                return SymbolReference.solved(JavaParserFacade.get(this).getTypeDeclaration(typeDeclaration.get()));
            }
        }
        
        // If not found in our index, we can't solve it
        return SymbolReference.unsolved(ResolvedReferenceTypeDeclaration.class);
    }
}
