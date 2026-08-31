# Multiway Merge Sort Algorithm
This repository contains code for the multiway merge sort assignment  
from Advanced Algorithms and Data Structures course that is being read at Constructor University Bremen in fall 2026.

## Framework overview
The assignment runs against a small toy database engine that emulates external memory. Data lives in fixed-size 4 KB disk pages 
(DiskPage) holding variable-length byte-array records; a Storage implementation (createHardDriveEmulatorStorage()) plays the role of a         
single-platter HDD, actually keeping pages in memory, but charging for every access as if it were physical. 
Above it sits a PageCache of a bounded number of page buffers with a choice of the eviction policies. 
The cache size, measured in page buffer count, can be tuned with the --cache-size command-line option. 
The cache is your RAM budget, and it is the only memory a correct implementation is allowed to treat as free. 

Above that, StorageAccessManager provides table-level abstractions: createTable, addPage, tablePages, and createFullScan(...) 
which iterates pages or parsed records. Your MultiwayMergeSort implementation is handed exactly these two objects (storageAccessManager, cache) 
and returns the name of a new sorted table.

The framework exposes a cost model: the emulated disk accumulates a virtual time in milliseconds (Storage.totalAccessCost), 
and this — not wall-clock time — is what the benchmark reports. Every random access (a single-page read, a single-page write, or positioning at the
start of a bulk operation) costs 5.0 ms, modelling seek + rotational latency; 
each page transferred inside a sequential bulkRead/bulkWrite costs only 1.3 ms (one eighth of a 5400 RPM rotation, 8 pages per track). 
So a sequential run is roughly 4× cheaper per page than random I/O, and the cache is what turns page requests into 
actual storage operations: a cache hit costs nothing, a miss costs a random read (or a bulk read via load(startPageId, pageCount) for read-ahead), 
and evicting or flushing a dirty page costs a random write.      


## How to start

1. You should start with creating a class that implements `MultiwayMergeSort` interface. 
   Ask your IDE to create a stub implementation of the interface methods.
2. Create instance of your class at line 57 of the file `Initialize.kt`
3. Parameterize your class with the `storageAccessManager: StorageAccessManager` and `cache: PageCache` objects and   
   implement the real multiway merge sort. Feel free to use `SlowSortImpl` class as a reference.

## Testing
* You can run unit tests with `./gradlew test -Dsort.impl=real` command. The tests are pretty basic and do not cover all 
  the aspects of the sort procedure, so passing unit tests does not necessarily mean that the implementation is correct. 
  However, if they pass, it is a very good start.

* You can run a benchmark that sort a table using your multiway merge sort implementation:

```
./gradlew run --args='sort-benchmark --cache-size 8 --data-scale 8 --sort-algorithm real'
```

  The benchmark prints ACCESS COST so you can compare your implementation against the baseline sort algorithm.
* You can compare your implementation and the baseline implementation using `run-sort-benchmark.sh` shell script.
  The script will run the benchmark for both implementations and will print the results, access cost measured in virtual   
  "I/O operations". You can tune the parameters of the benchmark, such as data scale or cache size, to change the cost of   
  the sort procedure.

## GitHub action

There is a GitHub action that runs the unit tests and the benchmark. It will check your pull requests and will turn the green light if your implementation is likely to be correct.

## Performance dynamics research

Second part of the assignment is to study the performance dynamics of your implementation. 
You can run the benchmark with different parameters and observe how the access cost changes. 
You can also compare your implementation with the default "slow" implementation and observe how the access cost changes.

The task is to create a graph that shows the performance dynamics of two implementations, show the point where multiway 
merge sort becomes more efficient than the default "slow" implementation, write an analysis and explanation of the results.


## Notes for TAs

The default "slow" Top-K implementation scans through the data set and builds an in-memory Top-K structure. 
The size of the priority queue is proportional to the cache size. Once the entire input is scanned, it flushes the contents of the priority queue to disk and remembers 
the last seen sort key. Then it starts scanning again, and exits when the last seen sort key is reached. 

The benchmark sort "tickets" table.  
AI will easily create an efficient multiway merge sort implementation. However, it will not outperform the baseline implementation with 
the given default cache and data size in the benchmark. The students will need to tune the parameters of the benchmark to observe the performance 
dynamics of their implementation and to understand when MWMS becomes more efficient than the default "slow" implementation.

Possible questions:
1. Explain how the cache size affects the performance of MWMS. What is the condition when only 1 pass over the data set is required?
   When only two passes are required?
2. What is the condition when TopK sort is more efficient than MWMS?

Perhaps it makes sense to review student's work using AI and ask the agent to prepare code-related questions to the student.
