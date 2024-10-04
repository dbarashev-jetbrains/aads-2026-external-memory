# Multiway Merge Sort Algorithm
This repository contains code for the multiway merge sort assignment  from Advanced Algorithms and Data Structures course that is being read at Constructor University Bremen in fall 2024.

## How to start

1. You should start with creating a class that implements `MultiwayMergeSort` interface. Ask your IDE to create a stub implementation of the interface methods.
1. Create instance of your class at line 56 of the file `Initialize.kt`
2. Parameterize your class with the `storageAccessManager: StorageAccessManager` and `cache: PageCache` objects and implement the real multiway merge sort. Feel free to use `SlowSortImpl` class as a reference.

## Testing
* You can run unit tests with `./gradlew test` command. The tests are pretty basic and do not cover all the aspects of 
the sort procedure, so passing unit tests does not necessarily mean that the implementation is correct. 
However, if they pass, it is a very good start.

* You can run a benchmark that joins two tables using your multiway merge sort implementation:

```
./gradlew run --args='join-benchmark --cache-impl clock --cache-size 70 --data-scale 10 --join-algorithm merge --real-sort'
```

* You can compare your implementation and the default "slow" implementation using `run-sort-merge-join-benchmark.sh` shell script. If your implementation is efficient, it will run approximately 100 times faster than the slow one.

## GitHub action

There is a GitHub action that runs the unit tests and the benchmark. It will check your pull requests and will turn the green light if your implementation is likely to be correct.
