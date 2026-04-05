# compare.py

import json
import re
from dataclasses import dataclass, field


# ── Normalization ─────────────────────────────────────────────────────────────

def normalize_method_fqn(fqn: str, source: str) -> str:
    if not fqn:
        return fqn

    if source == "joern":
        # "com.example.Foo.bar:void(int,String)" → "com.example.Foo.bar(int,String)"
        fqn = re.sub(r':[^(]+(\(.*\))$', r'\1', fqn)
        # Inner class separator: "$" → "."
        fqn = fqn.replace("$", ".")
        # Normalize <init> to class name
        # "com.example.Foo.<init>()" → "com.example.Foo.Foo()"
        fqn = re.sub(
            r'([\w.]+)\.<init>(\(.*\))',
            lambda m: m.group(1) + "." + m.group(1).split(".")[-1] + m.group(2),
            fqn
        )
        # Strip generic type params: "Class<?>" → "Class"
        fqn = re.sub(r'(?<=\w)<[^>]*>', '', fqn)
        # Normalize typed params to arg-count: "method(java.lang.String, int)" → "method(2)"
        def params_to_count_joern(m):
            params = m.group(1)
            if not params.strip():
                return '(0)'
            count = len([p for p in params.split(',') if p.strip()])
            return f'({count})'
        fqn = re.sub(r'\(([^)]*)\)$', params_to_count_joern, fqn)
        return fqn.strip()

    if source == "ladybug":
        # "com.example.Foo→com.example.Foo.bar()" → "com.example.Foo.bar()"
        if "→" in fqn:
            fqn = fqn.split("→")[1]
        # Inner class separator: "." used for both package and inner class
        # Nothing to change — already using dot notation
        # Strip generic type params from signatures: "Class<?>", "Map<K,V>" → "Class", "Map"
        # BUT preserve semantic tokens like <operator>, <unresolvedNamespace>, <init>, <clinit>
        # by requiring the '<' to be preceded by a word character (class/method name).
        fqn = re.sub(r'(?<=\w)<[^>]*>', '', fqn)
        # Normalize varargs: "String..." → "String[]"
        fqn = fqn.replace("...", "[]")
        # Normalize typed params to arg-count: "method(String, int)" → "method(2)"
        def params_to_count_lb(m):
            params = m.group(1)
            if not params.strip():
                return '(0)'
            # If already pure numeric, keep as-is
            if params.strip().isdigit():
                return f'({params.strip()})'
            count = len([p for p in params.split(',') if p.strip()])
            return f'({count})'
        fqn = re.sub(r'\(([^)]*)\)$', params_to_count_lb, fqn)
        return fqn.strip()

    return fqn


def normalize_class_fqn(fqn: str, source: str) -> str:
    if not fqn:
        return fqn
    if source == "joern":
        return fqn.replace("$", ".").strip()
    return fqn.strip()


def normalize_edge(edge: dict, source: str, node_type: str = "method") -> str:
    norm = normalize_method_fqn if node_type == "method" \
           else normalize_class_fqn
    frm = norm(edge.get("from", ""), source)
    to  = norm(edge.get("to",   ""), source)
    return f"{frm}→{to}"


# ── Diff helpers ──────────────────────────────────────────────────────────────

@dataclass
class DiffResult:
    only_in_joern:   list = field(default_factory=list)
    only_in_ladybug: list = field(default_factory=list)
    in_both:         list = field(default_factory=list)

    @property
    def jaccard(self) -> float:
        union = len(self.only_in_joern) + \
                len(self.only_in_ladybug) + \
                len(self.in_both)
        return len(self.in_both) / union if union else 1.0

    @property
    def ladybug_recall(self) -> float:
        """What fraction of Joern items appear in Ladybug."""
        total = len(self.in_both) + len(self.only_in_joern)
        return len(self.in_both) / total if total else 1.0

    @property
    def joern_recall(self) -> float:
        """What fraction of Ladybug items appear in Joern."""
        total = len(self.in_both) + len(self.only_in_ladybug)
        return len(self.in_both) / total if total else 1.0


def diff_nodes(
    joern_items:   list[dict],
    ladybug_items: list[dict],
    key:           str,
    node_type:     str = "method"
) -> DiffResult:
    norm = normalize_method_fqn if node_type == "method" \
           else normalize_class_fqn

    joern_set   = {norm(i[key], "joern")   for i in joern_items if i.get(key)}
    ladybug_set = {norm(i[key], "ladybug") for i in ladybug_items if i.get(key)}

    return DiffResult(
        only_in_joern   = sorted(joern_set   - ladybug_set),
        only_in_ladybug = sorted(ladybug_set - joern_set),
        in_both         = sorted(joern_set   & ladybug_set)
    )


def diff_edges(
    joern_edges:   list[dict],
    ladybug_edges: list[dict],
    node_type:     str = "method"
) -> DiffResult:
    joern_set   = {normalize_edge(e, "joern",   node_type) for e in joern_edges}
    ladybug_set = {normalize_edge(e, "ladybug", node_type) for e in ladybug_edges}

    return DiffResult(
        only_in_joern   = sorted(joern_set   - ladybug_set),
        only_in_ladybug = sorted(ladybug_set - joern_set),
        in_both         = sorted(joern_set   & ladybug_set)
    )


# ── Reporting ─────────────────────────────────────────────────────────────────

def print_section(title: str, diff: DiffResult, limit: int = 20):
    print(f"\n{'─'*65}")
    print(f"  {title}")
    print(f"{'─'*65}")
    print(f"  In both:              {len(diff.in_both)}")
    print(f"  Only in Joern:        {len(diff.only_in_joern)}")
    print(f"  Only in Ladybug:      {len(diff.only_in_ladybug)}")
    print(f"  Jaccard overlap:      {diff.jaccard:.2%}")
    print(f"  Ladybug recall:       {diff.ladybug_recall:.2%}  "
          f"(how much of Joern is in Ladybug)")
    print(f"  Joern recall:         {diff.joern_recall:.2%}  "
          f"(how much of Ladybug is in Joern)")

    if diff.only_in_joern:
        print(f"\n  → Only in Joern (first {limit}):")
        for item in diff.only_in_joern[:limit]:
            print(f"      {item}")

    if diff.only_in_ladybug:
        print(f"\n  → Only in Ladybug (first {limit}):")
        for item in diff.only_in_ladybug[:limit]:
            print(f"      {item}")


# ── Main ──────────────────────────────────────────────────────────────────────

def compare(joern_path: str, ladybug_path: str):
    with open(joern_path)   as f: joern   = json.load(f)
    with open(ladybug_path) as f: ladybug = json.load(f)

    # ── Summary ───────────────────────────────────────────────
    print("\n" + "═"*65)
    print("  SUMMARY (raw counts before normalization)")
    print("═"*65)

    j_sum = joern["summary"]
    l_sum = ladybug["summary"]

    rows = [
        ("method_count",     "Methods"),
        ("class_count",      "Classes"),
        ("calls_count",      "Calls"),
        ("defines_count",    "Defines"),
        ("lambda_count",     "Lambdas"),
    ]
    for key, label in rows:
        j = j_sum.get(key, "—")
        l = l_sum.get(key, "—")
        if isinstance(j, int) and isinstance(l, int):
            delta = j - l
            flag  = "✓" if abs(delta) < max(j, l) * 0.1 else "⚠"
            print(f"  {flag} {label:<16} Joern={j:<10} Ladybug={l:<10} delta={delta:+}")
        else:
            print(f"  ? {label:<16} Joern={j:<10} Ladybug={l}")

    # Inheritance — Joern exports unified, Ladybug splits
    j_inh = j_sum.get("inheritance_count", 0)
    l_ext = l_sum.get("extends_count",     0)
    l_imp = l_sum.get("implements_count",  0)
    l_inh = l_ext + l_imp
    print(f"\n  Inheritance (Joern unified vs Ladybug split):")
    print(f"    Joern total:      {j_inh}")
    print(f"    Ladybug extends:  {l_ext}")
    print(f"    Ladybug impl:     {l_imp}")
    print(f"    Ladybug total:    {l_inh}  delta={j_inh - l_inh:+}")

    # ── Node diffs ────────────────────────────────────────────
    method_diff = diff_nodes(
        joern["methods"], ladybug["methods"], "fqn", "method"
    )
    print_section("METHODS (normalized fqn)", method_diff)

    class_diff = diff_nodes(
        joern["classes"], ladybug["classes"], "fqn", "class"
    )
    print_section("CLASSES (normalized fqn)", class_diff)

    # ── Edge diffs ────────────────────────────────────────────
    calls_diff = diff_edges(
        joern["calls"], ladybug["calls"], "method"
    )
    print_section("CALLS edges (normalized)", calls_diff)

    defines_diff = diff_edges(
        joern["defines"], ladybug["defines"], "method"
    )
    print_section("DEFINES edges (normalized)", defines_diff)

    # Inheritance — compare Joern unified against Ladybug combined
    ladybug_inheritance = ladybug.get("extends", []) + \
                          ladybug.get("implements", [])
    inheritance_diff = diff_edges(
        joern.get("inheritance", []),
        ladybug_inheritance,
        "class"
    )
    print_section("INHERITANCE edges (normalized)", inheritance_diff)

    # ── Machine readable report ───────────────────────────────
    report = {
        "normalization_applied": True,
        "summary_delta": {
            label: {
                "joern":   j_sum.get(key),
                "ladybug": l_sum.get(key),
                "delta":   (j_sum.get(key, 0) or 0) -
                           (l_sum.get(key, 0) or 0)
            }
            for key, label in rows
        },
        "methods": {
            "jaccard":         method_diff.jaccard,
            "ladybug_recall":  method_diff.ladybug_recall,
            "joern_recall":    method_diff.joern_recall,
            "only_in_joern":   method_diff.only_in_joern[:100],
            "only_in_ladybug": method_diff.only_in_ladybug[:100]
        },
        "classes": {
            "jaccard":         class_diff.jaccard,
            "ladybug_recall":  class_diff.ladybug_recall,
            "joern_recall":    class_diff.joern_recall,
            "only_in_joern":   class_diff.only_in_joern[:100],
            "only_in_ladybug": class_diff.only_in_ladybug[:100]
        },
        "calls": {
            "jaccard":         calls_diff.jaccard,
            "ladybug_recall":  calls_diff.ladybug_recall,
            "joern_recall":    calls_diff.joern_recall,
            "only_in_joern":   calls_diff.only_in_joern[:100],
            "only_in_ladybug": calls_diff.only_in_ladybug[:100]
        },
        "defines": {
            "jaccard":         defines_diff.jaccard,
            "ladybug_recall":  defines_diff.ladybug_recall,
            "joern_recall":    defines_diff.joern_recall,
            "only_in_joern":   defines_diff.only_in_joern[:100],
            "only_in_ladybug": defines_diff.only_in_ladybug[:100]
        },
        "inheritance": {
            "jaccard":         inheritance_diff.jaccard,
            "ladybug_recall":  inheritance_diff.ladybug_recall,
            "joern_recall":    inheritance_diff.joern_recall,
            "only_in_joern":   inheritance_diff.only_in_joern[:100],
            "only_in_ladybug": inheritance_diff.only_in_ladybug[:100]
        }
    }

    with open("comparison_report.json", "w") as f:
        json.dump(report, f, indent=2)

    print("\n" + "═"*65)
    print("  Full report written to comparison_report.json")
    print("  Paste that file back for gap analysis.")
    print("═"*65)


if __name__ == "__main__":
    compare("joern_export.json", "ladybug_export.json")
