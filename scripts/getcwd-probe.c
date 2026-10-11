// bionic getcwd() behavior probe for the pocket-harness bash decision.
// Question 1: does getcwd(NULL, 0) allocate on this device (glibc extension)?
// Question 2: does getcwd(buf, size) succeed in an app dir whose ancestors are not readable?
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>

int main(void) {
    errno = 0;
    char *p = getcwd(NULL, 0);
    printf("Q1 getcwd(NULL,0): ret=%s errno=%d(%s)\n", p ? "non-null" : "NULL", errno, strerror(errno));
    if (p) { printf("  value=%s\n", p); free(p); }

    char buf[4096];
    errno = 0;
    char *q = getcwd(buf, sizeof buf);
    printf("Q2 getcwd(buf,size): ret=%s errno=%d(%s)\n", q ? "non-null" : "NULL", errno, strerror(errno));
    if (q) printf("  value=%s\n", q);
    return 0;
}
