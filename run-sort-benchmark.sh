#!/bin/bash

set -e
DEFAULT_COST=$(./gradlew run --args='sort-benchmark --cache-size 128 --data-scale 80 --sort-algorithm topk' | grep "ACCESS COST" | cut -d ":" -f 2)
echo "Sort cost with the DEFAULT sort implementation: $DEFAULT_COST"
[ -z "$DEFAULT_COST" ] && exit 1

REAL_COST=$(./gradlew run --args='sort-benchmark --cache-size 128 --data-scale 80 --sort-algorithm real' | grep "ACCESS COST" | cut -d ":" -f 2)
echo "Sort cost with the SUBMITTED sort implementation: $REAL_COST"
[ -z "$REAL_COST" ] && exit 1

echo "Is it good?"
EXPR="${DEFAULT_COST}*0.5 > ${REAL_COST}"
echo "$EXPR ?"
if awk -v d="$DEFAULT_COST" -v r="$REAL_COST" 'BEGIN { exit !(d * 0.5 > r) }'; then
    echo "Yes, it appears that the FAST implementation makes the sorting way more faster!";
else
    echo "No, it appears that the FAST implementation may be improved!";
    exit 1;
fi
