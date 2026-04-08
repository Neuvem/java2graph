#!/bin/bash
function query_counts() {
  local db=$1
  echo "Analyzing $db"
  echo "Class count:"
  echo "MATCH (n:Class) RETURN count(*);" | lbug "$db" -r | grep -A 1 '│'
  echo "Method count:"
  echo "MATCH (n:Method) RETURN count(*);" | lbug "$db" -r | grep -A 1 '│'
  echo "Calls count:"
  echo "MATCH ()-[r:Calls]->() RETURN count(r);" | lbug "$db" -r | grep -A 1 '│'
  echo "Extends count:"
  echo "MATCH ()-[r:Extends]->() RETURN count(r);" | lbug "$db" -r | grep -A 1 '│'
  echo "Defines count:"
  echo "MATCH ()-[r:Defines]->() RETURN count(r);" | lbug "$db" -r | grep -A 1 '│'
  echo "Implements count:"
  echo "MATCH ()-[r:Implements]->() RETURN count(r);" | lbug "$db" -r | grep -A 1 '│'
}

query_counts "/Users/saurabh/products/benchmarks/kafka/test/decypher.db"
