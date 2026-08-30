I upsolved the External Memory lab. Everything is working, and I think it's ready for students. Few things I've noticed:

## Issues/Concerns

- `close()` vs. the documented output-table ownership contradict each other:
  - Existing smoke test requires `close()` to delete the final sorted output table along with all the others: `OperationsTest.kt:57` (`assertFalse(accessMethodManager.tableExists(sortOutputTableName))`, in `doTestSorting`, `OperationsTest.kt:41-58`)
  - Interface's own doc comment says the opposite: "Releases all the temporary resources, such as intermediate tables, except for the output table." (`Operations.kt:34-37`; see also the `sort()` doc just above it, `Operations.kt:26-31`, which says the output table is "owned by the client")
- As the TA notes mention, the shell script proves that an efficient MWMS doesn't outperform the TopK algorithm with default params, so the GitHub Action doesn't pass. Am I right that it's expected for students to change the params of the shell script? If so, I think it's worth mentioning in the README.md. As well as which of the params should be minimized. In the analysis, should they report params for which the shell script passes (`{DEFAULT_COST}*0.5 > ${REAL_COST}`) or where MWMS starts to outperform the "slow" sort (`DEFAULT_COST > REAL_COST`)?

## Suggestions

- Maybe in the task description it makes sense to explain how the HardDisk/RAM Abstractions work and what their interface functions are. For example, methods of `StorageAccessManager`, `PageCache`; or what tables, pages, records, catalogue are and how to interact with them.
- For writing the algorithm, understanding the internals of the abstractions is not needed, but for the analysis part it's better to understand how `TableBuilder`'s methods work. Maybe mention this in the README.md. (`TableBuilder.kt:23-51`, esp. `insert()`/`newPage()` at `TableBuilder.kt:29-46`, which is what silently costs a `cache.getAndPin()` per page fill)
- `StorageImpl.kt:125-135` charges 5.0ms per single-page read/write but only 5.0ms + 1.3ms/page for a bulk op. Maybe expose this in the README.md so students can understand what affects their algorithm performance. Note also that the bulk path is only reachable on reads (`PageCache.load()`, `PageCache.kt:61`, used by `TopKSortImpl.kt:110-119`) — there is no equivalent exposed for writes (`Storage.bulkWrite()`, `Storage.kt:162`, is never reachable through `TableBuilder`/`PageCache`), so this asymmetry caps how much any correct MWMS can close the gap with `TopKSortImpl` regardless of algorithm quality.

P.S. Of course, if it's expected of students to use AI to familiarize themselves with the project, none of what's mentioned above needs to be added.
