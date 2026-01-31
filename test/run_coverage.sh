#!/bin/bash
set -e

# Clean previous coverage
rm -f *.gcda *.gcno coverage.info coverage_summary.txt *.o

cleanup() {
  rm -f test_wrapper_cov *.gcda *.gcno coverage.info coverage_filtered.info coverage_tmp.info *.o
}
trap cleanup EXIT

# Compile wrapper.c separately with coverage, exposing static functions, and hooking malloc
# -DTEST_BUILD: enables STATIC macro to be empty
# -Dmalloc=my_malloc -Dfree=my_free: redirects malloc/free to hooks
gcc -c -o wrapper.o wrapper.c \
    -I. -Iopenjpeg/src/lib/openjp2 -Itest \
    -fprofile-arcs -ftest-coverage -g \
    -DTEST_BUILD -Dmalloc=my_malloc -Dfree=my_free

# Compile malloc hook
gcc -c -o malloc_hook.o test/malloc_hook.c -g

# Compile stubs
gcc -c -o stubs.o test/stubs.c -I. -Iopenjpeg/src/lib/openjp2 -Itest -g

# Compile test runner
gcc -c -o test_wrapper.o test/test_wrapper.c \
    -I. -Iopenjpeg/src/lib/openjp2 -Itest \
    -DTEST_BUILD -g

# Link everything
gcc -o test_wrapper_cov wrapper.o malloc_hook.o stubs.o test_wrapper.o \
    -lgcov

# Run the test executable
./test_wrapper_cov

# Capture coverage (only if lcov is available)
if command -v lcov >/dev/null 2>&1; then
    # Capture all coverage
    # Using --ignore-errors mismatch to handle potential checksum issues
    lcov --capture --directory . --output-file coverage.info --ignore-errors mismatch,empty

    echo "=== Files captured by lcov (Debug) ==="
    lcov --list coverage.info
    echo "======================================"

    # Strategy:
    # 1. Extract specifically 'wrapper.c' (assuming exact name match).
    #    We use '*/wrapper.c' to catch it regardless of relative path prefix ./ or full path.
    #    Also extracting 'test_wrapper.c' just to see it in debug if needed, but we want to isolate wrapper.c

    # Intention: Keep ONLY wrapper.c
    # We do this by extracting it directly.
    lcov --extract coverage.info '*/wrapper.c' --output-file coverage_tmp.info --ignore-errors unused

    # 2. To be absolutely sure 'test/test_wrapper.c' isn't matching '*/wrapper.c',
    #    we explicitly remove it.
    lcov --remove coverage_tmp.info '*/test_wrapper.c' --output-file coverage_filtered.info --ignore-errors unused

    echo "=== Final Coverage Report ==="
    lcov --list coverage_filtered.info > coverage_summary.txt
    cat coverage_summary.txt
else
    echo "lcov not found, skipping report generation."
fi
