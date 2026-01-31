#!/bin/bash
set -e

# Clean previous coverage
rm -f *.gcda *.gcno coverage.info coverage_summary.txt *.gcov

cleanup() {
  rm -f test_wrapper_cov *.gcda *.gcno coverage.info *.gcov
}
trap cleanup EXIT

# Compile with coverage flags
gcc -o test_wrapper_cov test/test_wrapper.c test/stubs.c \
    -I. -Iopenjpeg/src/lib/openjp2 -Itest -DOPJ_STATIC \
    -fprofile-arcs -ftest-coverage -g

# Run the test executable
./test_wrapper_cov

# Generate coverage report using gcov
# We target the compiled source file (test/test_wrapper.c) but must explicitly point to the
# generated data file because GCC prefixes it with the executable name (test_wrapper_cov-test_wrapper.gcda).
echo "Generating gcov report..."
gcov_out=$(gcov -b -c -o test_wrapper_cov-test_wrapper.gcda test/test_wrapper.c)

# Parse output for wrapper.c coverage
# We exclude "test_wrapper" to avoid matching "test/test_wrapper.c" which also ends in "wrapper.c"
echo "$gcov_out" | awk '/File .*wrapper\.c/ && !/test_wrapper/{flag=1; next} /Lines executed:/{if(flag){print $0; flag=0}}' > coverage_summary.txt

# Display summary
if [ -s coverage_summary.txt ]; then
    cat coverage_summary.txt
else
    echo "Error: Failed to generate coverage summary for wrapper.c"
    echo "$gcov_out"
    exit 1
fi
