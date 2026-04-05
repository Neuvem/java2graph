# ldb_extract.py

import json
import sys

# ── Connection ─────────────────────────────────────────────
# Update this to match however ladybug exposes its client
# e.g. import ladybug as lbug / from ladybug import Connection
# Paste your working connection code here and we'll wire it in

DB_PATH = "./decypher.db"  # update this

def get_connection(db_path: str):
    """
    Replace this with your working ladybug connection.
    Paste the import and connection lines that work for you.
    """
    try:
        import real_ladybug as kuzu
        db   = kuzu.Database(db_path)
        conn = kuzu.Connection(db)
        return conn
    except Exception as e:
        print(f"Connection failed: {e}")
        print("Update get_connection() with your ladybug client code")
        sys.exit(1)

def query(conn, cypher: str) -> list:
    result = conn.execute(cypher)
    rows   = []
    while result.has_next():
        rows.append(result.get_next())
    return rows

def extract_graph(db_path: str) -> dict:
    conn = get_connection(db_path)

    # ── Methods ─────────────────────────────────────────────
    method_rows = query(conn, """
        MATCH (m:Method)
        RETURN
            m.id          AS id,
            m.fqn         AS fqn,
            m.name        AS name,
            m.signature   AS signature,
            m.isLambda    AS isLambda,
            m.sourceCode  AS sourceCode
    """)
    methods = [
        {
            "id":         r[0],
            "fqn":        r[1],
            "name":       r[2],
            "signature":  r[3],
            "isLambda":   r[4],
            "sourceCode": r[5]
        }
        for r in method_rows
    ]

    # ── Classes ─────────────────────────────────────────────
    class_rows = query(conn, """
        MATCH (c:Class)
        RETURN
            c.id          AS id,
            c.fqn         AS fqn,
            c.name        AS name,
            c.isInterface AS isInterface
    """)
    classes = [
        {
            "id":          r[0],
            "fqn":         r[1],
            "name":        r[2],
            "isInterface": r[3]
        }
        for r in class_rows
    ]

    # ── Defines edges ────────────────────────────────────────
    defines_rows = query(conn, """
        MATCH (c:Class)-[:Defines]->(m:Method)
        RETURN c.fqn AS from, m.fqn AS to
    """)
    defines = [{"from": r[0], "to": r[1]} for r in defines_rows]

    # ── Calls edges ──────────────────────────────────────────
    calls_rows = query(conn, """
        MATCH (a:Method)-[:Calls]->(b:Method)
        RETURN a.fqn AS from, b.fqn AS to
    """)
    calls = [{"from": r[0], "to": r[1]} for r in calls_rows]

    # ── Extends edges ────────────────────────────────────────
    extends_rows = query(conn, """
        MATCH (a:Class)-[:Extends]->(b:Class)
        RETURN a.fqn AS from, b.fqn AS to
    """)
    extends = [{"from": r[0], "to": r[1]} for r in extends_rows]

    # ── Implements edges ─────────────────────────────────────
    implements_rows = query(conn, """
        MATCH (a:Class)-[:Implements]->(b:Class)
        RETURN a.fqn AS from, b.fqn AS to
    """)
    implements = [{"from": r[0], "to": r[1]} for r in implements_rows]

    # ── Summary ──────────────────────────────────────────────
    lambdas      = [m for m in methods if m.get("isLambda")]
    interfaces   = [c for c in classes if c.get("isInterface")]

    summary = {
        "method_count":     len(methods),
        "lambda_count":     len(lambdas),
        "class_count":      len(classes),
        "interface_count":  len(interfaces),
        "calls_count":      len(calls),
        "defines_count":    len(defines),
        "extends_count":    len(extends),
        "implements_count": len(implements)
    }

    return {
        "source":     "ladybug",
        "summary":    summary,
        "methods":    methods,
        "classes":    classes,
        "calls":      calls,
        "defines":    defines,
        "extends":    extends,
        "implements": implements
    }


if __name__ == "__main__":
    data = extract_graph(DB_PATH)

    with open("ladybug_export.json", "w") as f:
        json.dump(data, f, indent=2)

    s = data["summary"]
    print("Done. Written to ladybug_export.json")
    print(f"Methods:       {s['method_count']}")
    print(f"  Lambdas:     {s['lambda_count']}")
    print(f"Classes:       {s['class_count']}")
    print(f"  Interfaces:  {s['interface_count']}")
    print(f"Calls:         {s['calls_count']}")
    print(f"Defines:       {s['defines_count']}")
    print(f"Extends:       {s['extends_count']}")
    print(f"Implements:    {s['implements_count']}")
