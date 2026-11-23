package io.github.dedecidao.dd64;

import android.graphics.Point;
import android.os.Bundle;
import android.view.Display;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * The game: SDL's activity running libdd64.so, with the touch controls on top.
 * The ROM path comes from LauncherActivity and goes to the native side as argv[1].
 */
public class GameActivity extends SDLActivity {
    public static final String EXTRA_ROM = "rom";
    /** "fidelity": the game as the N64 had it (the launcher's Play Fidelity); else Wrecked Metal */
    public static final String EXTRA_PROFILE = "profile";
    public static final String PROFILE_FIDELITY = "fidelity";

    private TouchControlsView mTouch;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "dd64" };
    }

    @Override
    protected String[] getArguments() {
        String rom = getIntent().getStringExtra(EXTRA_ROM);
        if (rom == null || rom.isEmpty()) {
            // Recreated by the system (after an app update, or from recents) with an
            // intent that has no extra: the ROM is where the launcher keeps it
            rom = LauncherActivity.romFile(this).getAbsolutePath();
        }
        String app = "--app=" + getPackageName();
        // When the app was installed or updated: native code skips a Data/env.txt older
        // than that, a test's left-over (android_main.c loadEnvFile)
        String installed = "--installed=0";
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            app += " " + pi.versionName + " (" + pi.versionCode + ")";
            installed = "--installed=" + pi.lastUpdateTime;
        } catch (Exception e) {
            // the version only goes in the log
        }
        app += fidelity() ? ", Fidelity" : ", Wrecked Metal";
        return fidelity() ? new String[] { rom, "--fidelity", app, installed } : new String[] { rom, app, installed };
    }

    boolean fidelity() {
        return PROFILE_FIDELITY.equals(getIntent().getStringExtra(EXTRA_PROFILE));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyOutputResolution();
        if (mLayout != null && Settings.touchEnabled(this)) {
            mTouch = new TouchControlsView(this, fidelity());
            mLayout.addView(mTouch, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /**
     * The video page's "resolution" (dd64.ini [video] window_height: 720 or 1080): the
     * surface the game draws to is that many lines, the width following the screen's
     * shape, and the display scales it to the screen. Read once, at start.
     */
    private void applyOutputResolution() {
        int lines = readIniInt("window_height", 720);
        if (mSurface == null || (lines != 720 && lines != 1080)) {
            return;
        }
        if (readIniInt("crt", 0) != 0) {
            // [video] crt: the CRT filter draws its scanlines on the surface itself, so it
            // keeps the screen's full resolution (the 3D still draws at internal_height)
            return;
        }
        Display d = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        d.getRealSize(size);
        int longSide = Math.max(size.x, size.y), shortSide = Math.min(size.x, size.y);
        if (shortSide <= 0) {
            return;
        }
        int width = (int) Math.round((double) lines * longSide / shortSide) & ~1;
        mSurface.getHolder().setFixedSize(width, lines);
    }

    /** An integer key of dd64.ini (in the app's external files folder), or `def` */
    private int readIniInt(String key, int def) {
        File ini = new File(getExternalFilesDir(null), fidelity() ? "dd64-fidelity.ini" : "dd64.ini");
        try (BufferedReader r = new BufferedReader(new FileReader(ini))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(key)) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && line.substring(0, eq).trim().equals(key)) {
                        return Integer.parseInt(line.substring(eq + 1).trim());
                    }
                }
            }
        } catch (Exception e) {
            // no dd64.ini yet (first start): the default
        }
        return def;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mTouch != null) {
            mTouch.refreshGamepadState();
        }
    }
}
