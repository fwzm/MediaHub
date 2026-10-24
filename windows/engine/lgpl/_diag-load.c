/* Load-test each DLL the LGPL mpv build will need at runtime.
   Prints LOAD OK / LOAD FAIL per library so a CRT mismatch is visible as a fact. */
#include <windows.h>
#include <stdio.h>

int main(int argc, char **argv) {
    int rc = 0;
    for (int i = 1; i < argc; i++) {
        HMODULE h = LoadLibraryA(argv[i]);
        if (h) {
            printf("LOAD OK   %s\n", argv[i]);
        } else {
            DWORD e = GetLastError();
            printf("LOAD FAIL %s  (GetLastError=%lu)\n", argv[i], (unsigned long)e);
            rc = 1;
        }
    }
    return rc;
}
