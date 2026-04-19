package com.neuvem.java2graph.util;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A TypeSolver wrapper that enforces a structural lookup quota.
 * This prevents infinite resolution loops in the underlying symbol solver
 * by "tripping" a circuit breaker after a certain number of lookups.
 */
public class BudgetedTypeSolver implements TypeSolver {

    private final TypeSolver delegate;
    private final long quota;
    private final AtomicLong currentCount = new AtomicLong(0);
    private final java.util.Set<String> unsolvedCache = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private TypeSolver parent;

    public BudgetedTypeSolver(TypeSolver delegate, long quota) {
        this.delegate = delegate;
        this.quota = quota;
    }

    public void reset() {
        currentCount.set(0);
    }

    public long getCurrentCount() {
        return currentCount.get();
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
        if (unsolvedCache.contains(name)) {
            return SymbolReference.unsolved(ResolvedReferenceTypeDeclaration.class);
        }

        ResolutionTracer.setLastSymbol(name);
        if (currentCount.incrementAndGet() > quota) {
            throw new QuotaExceededException("Symbol solver exceeded lookup quota of " + quota + " for this file.");
        }
        
        SymbolReference<ResolvedReferenceTypeDeclaration> ref = delegate.tryToSolveType(name);
        if (!ref.isSolved()) {
            unsolvedCache.add(name);
        }
        return ref;
    }
}
