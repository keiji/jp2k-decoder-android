#include <stdlib.h>
#include <stddef.h>

// Malloc hooking for testing
// This file will be compiled and linked with the test executable.
// wrapper.c will be compiled with -Dmalloc=my_malloc -Dfree=my_free

int stub_should_malloc_succeed = 1;

void* my_malloc(size_t size) {
    if (!stub_should_malloc_succeed) return NULL;
    return malloc(size);
}

void my_free(void* ptr) {
    free(ptr);
}
