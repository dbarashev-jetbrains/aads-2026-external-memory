#!/bin/bash

set -e
DEFAULT_COST=$(./gradlew run --args='sort-benchmark --cache-impl clock --cache-size 80 --data-scale 20 --sort-algorithm topk' | grep "SORT COST" | cut -d ":" -f 2)
echo "Sort cost with the DEFAULT sort implementation: $DEFAULT_COST"

REAL_COST=$(./gradlew run --args='sort-benchmark --cache-impl clock --cache-size 80 --data-scale 20 --sort-algorithm real' | grep "SORT COST" | cut -d ":" -f 2)
echo "Sort cost with the SUBMITTED sort implementation: $REAL_COST"

EXPR="${DEFAULT_COST}/2 < ${REAL_COST}"
echo "$EXPR ?"
if (($(bc <<< "$EXPR") == 1)); then
    echo "It appears that the SUBMITTED implementation may be improved!";
    exit 1;
fi
