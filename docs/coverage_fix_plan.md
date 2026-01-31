# Coverage Reporting Fix Plan

The current coverage reporting infrastructure (using `lcov` + `wrapper.c` included in `test_wrapper.c`) produces incorrect line coverage percentages (around 5.2%) in reports, likely due to `lcov` misinterpreting the included source file structure.

However, the underlying `gcov` data is correct and shows 100% coverage.

## Simplified Solution Procedure

To achieve correct coverage reporting without complex build restructuring, we should abandon `lcov` for report generation and directly utilize `gcov`.

1.  **Modify `test/run_coverage.sh`**
    *   Keep the existing compilation logic (compiling `test_wrapper.c` which includes `wrapper.c`).
    *   Remove the `lcov` commands (`--capture`, `--extract`, `--remove`).
    *   Instead, execute `gcov -b -c wrapper.c` directly after running the tests.
    *   Parse the output of `gcov` (or the content of `wrapper.c.gcov`) to generate a summary text file (`coverage_summary.txt`).

2.  **Update CI Workflow**
    *   The existing CI workflow (`.github/workflows/build.yml`) already reads `coverage_summary.txt` to post comments.
    *   By ensuring `test/run_coverage.sh` outputs the `gcov` summary (e.g., "Lines executed:100.00% of 269") into this file, the PR comment will correctly reflect the 100% coverage.

This approach is minimally invasive and leverages the fact that `gcov` is already working correctly.
