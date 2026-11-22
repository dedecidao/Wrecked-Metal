/*
 * Touch controls and player 1's pad on Android.
 *
 * The on-screen controls are drawn and tracked in Java (TouchControlsView),
 * above the game's surface, so they work with any renderer. The view sends the
 * pressed N64 buttons and the stick (N64 units, -80..80) here; they are merged
 * into player 1's pad.
 *
 * port/src/input.c is built with Port_InputGetPad renamed to
 * Port_InputGetPadHost (CMakeLists.txt): this file is the Port_InputGetPad the
 * rest of the port calls, wrapping the PC one (gamepads, scripted input).
 */
#include <SDL3/SDL.h>
#include <jni.h>

#include "port.h"

void Port_InputGetPadHost(int channel, OSContPad *out);
int Port_InputConnectedHost(int channel);

static SDL_AtomicInt sTouchButtons;
static SDL_AtomicInt sTouchStick; /* x in the low 16 bits, y in the high, signed */
static SDL_AtomicInt sTouchActive;
/* Buttons pressed since the game last read the pad: a tap shorter than the game's
   polling interval still counts once */
static SDL_AtomicInt sTouchLatched;

int Port_TouchDriving(void); /* port/src/video.c */

/* The view hides the arrows' up and down while the player drives (TouchControlsView) */
JNIEXPORT jint JNICALL Java_io_github_dedecidao_dd64_TouchControlsView_nativeDriving(JNIEnv *env, jclass cls) {
    return Port_TouchDriving();
}

JNIEXPORT void JNICALL Java_io_github_dedecidao_dd64_TouchControlsView_nativeSetState(JNIEnv *env, jclass cls,
                                                                                     jint buttons, jint x, jint y) {
    int old = SDL_GetAtomicInt(&sTouchButtons), latched;
    do {
        latched = SDL_GetAtomicInt(&sTouchLatched);
    } while (!SDL_CompareAndSwapAtomicInt(&sTouchLatched, latched, latched | (buttons & ~old & 0xFFFF)));
    SDL_SetAtomicInt(&sTouchButtons, buttons & 0xFFFF);
    SDL_SetAtomicInt(&sTouchStick, (int)((u32)(u16)(s16)x | ((u32)(u16)(s16)y << 16)));
    SDL_SetAtomicInt(&sTouchActive, 1);
}

/*
 * Testing aids (Data/env.txt), run on the game thread as it reads the pads:
 *   DD64_POKE=frame:addr=value[,addr=value...]  32-bit words into N64 RAM (host order)
 *   DD64_GOTO=frame:mode                        Game_SetNextMode(mode, 1) at that frame
 * For reaching a screen without playing up to it (a record's name entry is 0x24).
 */
extern unsigned gfx_presents;
void Game_SetNextMode(s32 mode, s32 arg);

static void testAids(void) {
    static int pokeDone, gotoDone;
    const char *p;

    if (!pokeDone && (p = SDL_getenv("DD64_POKE")) != NULL && gfx_presents >= (unsigned)SDL_atoi(p)) {
        const char *q = SDL_strchr(p, ':');
        pokeDone = 1;
        while (q != NULL) {
            u32 addr = (u32)SDL_strtoul(q + 1, (char **)&q, 16), val;
            u32 *dst = (u32 *)Port_N64ToHost(addr);
            if (q == NULL || *q != '=') {
                break;
            }
            val = (u32)SDL_strtoul(q + 1, (char **)&q, 16);
            if (dst != NULL) {
                *dst = val;
                Port_Log("test: poke %08X = %08X", addr, val);
            }
            q = (q != NULL && *q == ',') ? q : NULL;
        }
    }
    if (!gotoDone && (p = SDL_getenv("DD64_GOTO")) != NULL && gfx_presents >= (unsigned)SDL_atoi(p)) {
        const char *q = SDL_strchr(p, ':');
        gotoDone = 1;
        if (q != NULL) {
            s32 mode = (s32)SDL_strtol(q + 1, NULL, 0);
            Port_Log("test: game mode %#x", mode);
            Game_SetNextMode(mode, 1);
        }
    }
}

void Port_InputGetPad(int channel, OSContPad *out) {
    if (channel == 0) {
        testAids();
    }
    Port_InputGetPadHost(channel, out);
    if (channel == 0 && SDL_GetAtomicInt(&sTouchActive)) {
        u32 stick = (u32)SDL_GetAtomicInt(&sTouchStick);
        s8 x = (s8)(s16)(stick & 0xFFFF), y = (s8)(s16)(stick >> 16);
        out->button |= (u16)(SDL_GetAtomicInt(&sTouchButtons) | SDL_SetAtomicInt(&sTouchLatched, 0));
        /* The touch stick wins while it is held; a gamepad's stick otherwise */
        if (x != 0 || y != 0) {
            out->stick_x = x;
            out->stick_y = y;
        }
    }
}

int Port_InputConnected(int channel) {
    return Port_InputConnectedHost(channel);
}
