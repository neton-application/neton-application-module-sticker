#ifndef PRIVCHAT_PAGPROCESS_H
#define PRIVCHAT_PAGPROCESS_H
#include <unistd.h>
#include <fcntl.h>
#include <sys/resource.h>

/* All child work stays in C after fork; no Kotlin runtime executes before exec. */
static inline int privchat_pag_spawn(const char* executable, const char* input, const char* usage, int output) {
    pid_t pid = fork();
    if (pid != 0) return (int)pid;
    struct rlimit cpu = {5, 5};
    struct rlimit core = {0, 0};
    int nullfd = open("/dev/null", O_RDWR);
    if (setrlimit(RLIMIT_CPU, &cpu) ||
        setrlimit(RLIMIT_CORE, &core) || nullfd < 0 ||
        dup2(nullfd, STDIN_FILENO) < 0 || dup2(nullfd, STDERR_FILENO) < 0 ||
        dup2(output, STDOUT_FILENO) < 0) _exit(127);
    if (nullfd > STDERR_FILENO) close(nullfd);
    if (output > STDERR_FILENO) close(output);
    char* const argv[] = {(char*)executable, (char*)input, (char*)usage, NULL};
    char* const env[] = {"PATH=/usr/bin:/bin", "LANG=C", NULL};
    execve(executable, argv, env);
    _exit(127);
}
#endif
