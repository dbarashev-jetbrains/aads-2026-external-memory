#!/bin/bash

set -e
DEFAULT_COST=$(./gradlew run --args='sort-benchmark --cache-size 8 --data-scale 8 --sort-algorithm topk' | grep "ACCESS COST" | cut -d ":" -f 2)
echo "Sort cost with the DEFAULT sort implementation: $DEFAULT_COST"
[ -z "$DEFAULT_COST" ] && exit 1

REAL_COST=$(./gradlew run --args='sort-benchmark --cache-size 8 --data-scale 8 --sort-algorithm real' | grep "ACCESS COST" | cut -d ":" -f 2)
echo "Sort cost with the SUBMITTED sort implementation: $REAL_COST"
[ -z "$REAL_COST" ] && exit 1

echo "Is it good?"
EXPR="${DEFAULT_COST}*0.5 > ${REAL_COST}"
echo "$EXPR ?"
if (($(bc <<< "$EXPR") == 0)); then
    echo "No, it appears that the FAST implementation may be improved!";
    exit 1;
else
    echo "Yes, it appears that the FAST implementation makes the sorting way more faster!";
fi
