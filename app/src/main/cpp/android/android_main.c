/*
 * Entry point on Android, in place of external/dd64/port/src/main.c.
 *
 * SDLActivity (GameActivity.java) loads libSDL3.so and libdd64.so and calls
 * SDL_main on its own thread. The arguments are the path of the ROM the
 * launcher copied into the app's storage. The rest is the PC main loop as it is.
 */
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <android/log.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>
#include <unwind.h>
#include <dlfcn.h>
#include <dirent.h>
#include <pthread.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "port.h"
#include "port_config.h"
#include "port_raphnet.h"

void boot(void);
const char *DD64A_GetBasePath(void);

volatile int gPortQuit;
static FILE *sLogFile;

void Port_Log(const char *fmt, ...) {
    char buf[1024];
    va_list ap;

    va_start(ap, fmt);
    SDL_vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_write(ANDROID_LOG_INFO, "DD64", buf);
    if (sLogFile != NULL) {
        fprintf(sLogFile, "%s\n", buf);
        fflush(sLogFile);
    }
}

void Port_Fatal(const char *fmt, ...) {
    char buf[1024];
    va_list ap;

    va_start(ap, fmt);
    SDL_vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Port_Log("fatal: %s", buf);
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Destruction Derby 64", buf, NULL);
    exit(1);
}

void Port_RequestQuit(void) {
    gPortQuit = 1;
}

/* ---- Crash log: registers and call chain, for llvm-addr2line on libdd64.so ---- */

typedef struct {
    int n;
} UnwindState;

static _Unwind_Reason_Code unwindOne(struct _Unwind_Context *ctx, void *arg) {
    UnwindState *st = arg;
    uintptr_t pc = _Unwind_GetIP(ctx);
    Dl_info d;

    if (pc == 0 || st->n >= 32) {
        return _URC_END_OF_STACK;
    }
    if (dladdr((void *)pc, &d) && d.dli_fname != NULL) {
        Port_Log("crash: #%02d %08X %s+%X (%s)", st->n, (unsigned)pc, d.dli_fname,
                 (unsigned)(pc - (uintptr_t)d.dli_fbase), d.dli_sname != NULL ? d.dli_sname : "?");
    } else {
        Port_Log("crash: #%02d %08X", st->n, (unsigned)pc);
    }
    st->n++;
    return _URC_NO_REASON;
}

static void crashHandler(int sig, siginfo_t *si, void *uc) {
    const mcontext_t *m = &((const ucontext_t *)uc)->uc_mcontext;
    UnwindState st = { 0 };
    Dl_info d;

    Port_Log("crash: signal %d at pc %08lX, accessing %p", sig, (unsigned long)m->arm_pc, si->si_addr);
    if (dladdr((void *)m->arm_pc, &d) && d.dli_fname != NULL) {
        Port_Log("crash: pc = %s+%lX", d.dli_fname, (unsigned long)(m->arm_pc - (uintptr_t)d.dli_fbase));
    }
    if (dladdr((void *)m->arm_lr, &d) && d.dli_fname != NULL) {
        Port_Log("crash: lr = %s+%lX", d.dli_fname, (unsigned long)(m->arm_lr - (uintptr_t)d.dli_fbase));
    }
    Port_Log("crash: r0 %08lX r1 %08lX r2 %08lX r3 %08lX r4 %08lX r5 %08lX r6 %08lX r7 %08lX",
             m->arm_r0, m->arm_r1, m->arm_r2, m->arm_r3, m->arm_r4, m->arm_r5, m->arm_r6, m->arm_r7);
    Port_Log("crash: r8 %08lX r9 %08lX r10 %08lX fp %08lX ip %08lX sp %08lX lr %08lX",
             m->arm_r8, m->arm_r9, m->arm_r10, m->arm_fp, m->arm_ip, m->arm_sp, m->arm_lr);
    _Unwind_Backtrace(unwindOne, &st);
    signal(sig, SIG_DFL);
    raise(sig);
}

/*
 * DD64_STACKS=<milliseconds> (Data/env.txt): every so often, each running game thread
 * ("n64 ...") logs where it is, as libdd64.so offsets for llvm-addr2line. For a game
 * that hangs or spins: Android does not let an app profile itself (simpleperf needs
 * a system property), so the threads report themselves. With DD64_PEEK=addr,addr...
 * the sampler also logs those N64 RAM words each time (progress counters).
 * Signal context: logcat only (liblog), never the stdio log file, whose lock the
 * thread may hold; pc and lr only (unwinding from there crashed in libunwind).
 */
static void sampleAddr(const char *what, const char *thread, uintptr_t a) {
    Dl_info d;

    if (dladdr((void *)a, &d) && d.dli_fname != NULL) {
        __android_log_print(ANDROID_LOG_INFO, "DD64", "stacks: [%s] %s %s+%X", thread, what,
                            strrchr(d.dli_fname, '/') ? strrchr(d.dli_fname, '/') + 1 : d.dli_fname,
                            (unsigned)(a - (uintptr_t)d.dli_fbase));
    } else {
        __android_log_print(ANDROID_LOG_INFO, "DD64", "stacks: [%s] %s %08X", thread, what, (unsigned)a);
    }
}

static void stackSample(int sig, siginfo_t *si, void *uc) {
    const mcontext_t *m = &((const ucontext_t *)uc)->uc_mcontext;
    char name[32] = "?";

    (void)sig;
    (void)si;
    prctl(PR_GET_NAME, (unsigned long)name, 0, 0, 0);
    sampleAddr("pc", name, m->arm_pc);
    sampleAddr("lr", name, m->arm_lr);
}

static void logPeek(void) {
    static char peek[256];
    static int peekInit;
    char tmp[256], buf[512], *p, *save = NULL;
    int len = 0;

    if (!peekInit) {
        const char *v = getenv("DD64_PEEK");
        peekInit = 1;
        snprintf(peek, sizeof(peek), "%s", v != NULL ? v : "");
    }
    if (peek[0] == 0) {
        return;
    }
    snprintf(tmp, sizeof(tmp), "%s", peek);
    buf[0] = 0;
    for (p = strtok_r(tmp, ",", &save); p != NULL && len < (int)sizeof(buf) - 32; p = strtok_r(NULL, ",", &save)) {
        u32 a = (u32)strtoul(p, NULL, 16);
        void *h = Port_N64ToHost(a);
        len += snprintf(buf + len, sizeof(buf) - len, " %08X=%08X", a, h != NULL ? *(volatile u32 *)h : 0xDEADBEEFu);
    }
    __android_log_print(ANDROID_LOG_INFO, "DD64", "peek:%s", buf);
}

static void *stackSampler(void *arg) {
    int ms = (int)(intptr_t)arg;

    for (;;) {
        DIR *dir;
        struct dirent *e;

        usleep(ms * 1000);
        logPeek();
        dir = opendir("/proc/self/task");
        while (dir != NULL && (e = readdir(dir)) != NULL) {
            char path[64], comm[32] = "", stat[256] = "", *rp;
            int tid = atoi(e->d_name);
            FILE *f;

            if (tid <= 0) {
                continue;
            }
            snprintf(path, sizeof(path), "/proc/self/task/%d/comm", tid);
            f = fopen(path, "r");
            if (f != NULL) {
                if (fgets(comm, sizeof(comm), f) == NULL) {
                    comm[0] = 0;
                }
                fclose(f);
            }
            if (strncmp(comm, "n64", 3) != 0) {
                continue;
            }
            snprintf(path, sizeof(path), "/proc/self/task/%d/stat", tid);
            f = fopen(path, "r");
            if (f != NULL) {
                if (fgets(stat, sizeof(stat), f) == NULL) {
                    stat[0] = 0;
                }
                fclose(f);
            }
            rp = strrchr(stat, ')');
            if (rp != NULL && rp[1] == ' ' && rp[2] == 'R') { /* running threads only */
                syscall(SYS_tgkill, getpid(), tid, SIGUSR2);
            }
        }
        if (dir != NULL) {
            closedir(dir);
        }
    }
    return NULL;
}

static void startStackSampler(void) {
    const char *v = getenv("DD64_STACKS");
    struct sigaction sa;
    pthread_t th;
    int ms = v != NULL ? atoi(v) : 0;

    if (ms <= 0) {
        return;
    }
    SDL_zero(sa);
    sa.sa_sigaction = stackSample;
    sa.sa_flags = SA_SIGINFO | SA_RESTART | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGUSR2, &sa, NULL);
    pthread_create(&th, NULL, stackSampler, (void *)(intptr_t)ms);
    pthread_detach(th);
    Port_Log("stacks: running game threads report every %d ms", ms);
}

static void installCrashHandler(void) {
    static const int sigs[] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT };
    struct sigaction sa;
    size_t i;

    SDL_zero(sa);
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    for (i = 0; i < SDL_arraysize(sigs); i++) {
        sigaction(sigs[i], &sa, NULL);
    }
}

static void openLog(void) {
    const char *base = DD64A_GetBasePath();
    char path[1024];

    SDL_snprintf(path, sizeof(path), "%sData/Logs", base);
    SDL_CreateDirectory(path);
    SDL_snprintf(path, sizeof(path), "%sData/Logs/dd64.log", base);
    sLogFile = fopen(path, "w");
}

/*
 * Data/env.txt: KEY=VALUE lines set as environment variables at startup, for the
 * PC port's DD64_* test and debug switches (DD64_SHOT, DD64_PRESS, DD64_GFXLOG...),
 * which on a PC are set in the shell. Lines starting with # are skipped.
 *
 * One launch only: the file is moved to Data/env.used.txt before it is read, so a
 * test switch left behind (DD64_BOOT straight into a race, DD64_FAKEGAS holding the
 * gas) never sticks to the next time the player opens the game. A file older than
 * the app's install or update (GameActivity: --installed=<ms>) is a left-over from
 * an earlier build's test and is moved away without being applied. If the file can't
 * be moved, it isn't applied either.
 */
static long long sInstalledMs; /* the app's last install or update, ms since 1970 (0 unknown) */

static void loadEnvFile(void) {
    const char *base = DD64A_GetBasePath();
    char path[1024], used[1024], line[1024];
    struct stat st;
    int stale;
    FILE *f;

    SDL_snprintf(path, sizeof(path), "%sData/env.txt", base);
    SDL_snprintf(used, sizeof(used), "%sData/env.used.txt", base);
    if (stat(path, &st) != 0) {
        return;
    }
    stale = sInstalledMs > 0 && (long long)st.st_mtime * 1000 < sInstalledMs;
    remove(used);
    if (rename(path, used) != 0) {
        Port_Log("env: Data/env.txt could not be moved to env.used.txt, not applied");
        return;
    }
    if (stale) {
        Port_Log("env: Data/env.txt is older than this install (a left-over test), not applied; "
                 "moved to env.used.txt");
        return;
    }
    f = fopen(used, "r");
    if (f == NULL) {
        return;
    }
    while (fgets(line, sizeof(line), f) != NULL) {
        char *eq, *end;

        line[strcspn(line, "\r\n")] = 0;
        eq = strchr(line, '=');
        if (line[0] == '#' || eq == NULL || eq == line) {
            continue;
        }
        *eq = 0;
        for (end = eq; end > line && (end[-1] == ' ' || end[-1] == '\t'); end--) {
            end[-1] = 0;
        }
        SDL_setenv_unsafe(line, eq + 1, 1); /* getenv and SDL_getenv both see it */
        Port_Log("env: %s=%s (this launch only)", line, eq + 1);
    }
    fclose(f);
}
/*
 * The device sheet at the top of dd64.log, so a log sent from another phone or
 * tablet says what ran it (the GPU and its driver follow from SDL_gpu, the frame
 * rate every 30 s from video.c). argv carries the app's package and version
 * (GameActivity: --app=...).
 */
static void logDevice(int argc, char **argv) {
    char man[PROP_VALUE_MAX] = "", model[PROP_VALUE_MAX] = "", dev[PROP_VALUE_MAX] = "";
    char rel[PROP_VALUE_MAX] = "", sdk[PROP_VALUE_MAX] = "", socMan[PROP_VALUE_MAX] = "";
    char soc[PROP_VALUE_MAX] = "", board[PROP_VALUE_MAX] = "", hw[PROP_VALUE_MAX] = "";
    char abis[PROP_VALUE_MAX] = "";
    const char *appInfo = "?";
    int a;

    __system_property_get("ro.product.manufacturer", man);
    __system_property_get("ro.product.model", model);
    __system_property_get("ro.product.device", dev);
    __system_property_get("ro.build.version.release", rel);
    __system_property_get("ro.build.version.sdk", sdk);
    __system_property_get("ro.soc.manufacturer", socMan); /* Android 12+ */
    __system_property_get("ro.soc.model", soc);
    __system_property_get("ro.board.platform", board);
    __system_property_get("ro.hardware", hw);
    __system_property_get("ro.product.cpu.abilist", abis);
    for (a = 2; a < argc; a++) {
        if (argv[a] != NULL && SDL_strncmp(argv[a], "--app=", 6) == 0) {
            appInfo = argv[a] + 6;
        }
    }
    Port_Log("device: %s %s (%s), Android %s (SDK %s)", man, model, dev, rel, sdk);
    Port_Log("device: SoC %s %s, board %s, hardware %s; ABIs %s; the game runs %s", socMan[0] ? socMan : "?",
             soc[0] ? soc : "?", board, hw, abis, sizeof(void *) == 4 ? "32-bit (armeabi-v7a)" : "64-bit (arm64-v8a)");
    Port_Log("app: %s", appInfo);
}

int main(int argc, char **argv) {
    Port_RamReserve(); /* 0x80000000 up, before anything else can take it */
    openLog();
    /* Debug dumps (DD64_SHOT, DD64_TEXDUMP...) use relative paths: into the app's folder */
    if (chdir(DD64A_GetBasePath()) != 0) {
        Port_Log("chdir %s failed", DD64A_GetBasePath());
    }
    logDevice(argc, argv);
    {
        int a;
        for (a = 1; a < argc; a++) {
            if (argv[a] != NULL && SDL_strncmp(argv[a], "--installed=", 12) == 0) {
                sInstalledMs = SDL_strtoll(argv[a] + 12, NULL, 10);
            }
        }
    }
    loadEnvFile();
    installCrashHandler();
    startStackSampler();
    Port_Log("rom: argument '%s'", argc > 1 && argv[1] != NULL ? argv[1] : "(none)");
    if (argc > 1 && argv[1] != NULL && argv[1][0] != '\0') {
        SDL_setenv_unsafe("DD64_ROM", argv[1], 1); /* rom.c tries DD64_ROM first */
    }
    /* Landscape, full screen, no on-screen keyboard; the back button is the game's Start */
    SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
    {
        int a;
        for (a = 2; a < argc; a++) {
            if (argv[a] != NULL && SDL_strcmp(argv[a], "--fidelity") == 0) {
                /* the launcher's Play Fidelity (GameActivity): before the settings, which
                   then come from dd64-fidelity.ini (port/src/config.c) */
                gPortConfig.fidelity = 1;
            }
        }
    }
    Port_ConfigLoad();
    gPortConfig.fullscreen = 1;
    /* The renderer: Vulkan (SDL_gpu) by default on Android (port/src/config.c);
       OpenGL can be picked in the video page */
    SDL_SetAppMetadata("Wrecked Metal", "0.1", "com.costelinhagames.wreckedmetal");
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMEPAD | SDL_INIT_AUDIO)) {
        Port_Fatal("SDL_Init: %s", SDL_GetError());
    }
    if (!Port_RomLoad()) {
        Port_Fatal("Could not load the Destruction Derby 64 (USA) ROM.\n\n"
                   "Pick your cart image (.z64, .n64 or .v64) again on the start screen.");
    }
    Port_RamInit();
    Port_OsInit();
    Port_RcpInit();
    Port_VideoInit();
    Port_AudioInit();
    Port_InputInit();

    boot();
    Port_ViStart();

    while (!gPortQuit) {
        Port_PumpEvents();
        Port_RcpRunPending();
        Port_ViRetrace();
        Port_VideoPresentInterp();
        Port_OptionsApplyPending();
        Port_RcpWait(SDL_GetTicksNS() + 1000000);
    }

    SDL_Quit();
    /* The game's threads have no way to stop cleanly: end the process, so that
       the next launch starts with the RAM image mapped afresh */
    exit(0);
}
