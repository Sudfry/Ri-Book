package com.sudfry.todo.util;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * 点按音效。用系统 ToneGenerator 现场发声，不带任何音频文件，
 * 音量与音色都能在设置里调（对应 ribook 的音效设置）。
 */
public final class Sfx {

    private static ToneGenerator tg;
    private static int madeVol = -1;

    private Sfx() {
    }

    public static final String[] TYPES = {"soft", "crisp", "pop", "wood"};

    public static String typeName(String t) {
        switch (t) {
            case "crisp":
                return "清脆";
            case "pop":
                return "气泡";
            case "wood":
                return "木鱼";
            default:
                return "轻柔";
        }
    }

    private static int toneOf(String type) {
        switch (type) {
            case "crisp":
                return ToneGenerator.TONE_PROP_ACK;
            case "pop":
                return ToneGenerator.TONE_CDMA_PIP;
            case "wood":
                return ToneGenerator.TONE_PROP_BEEP2;
            default:
                return ToneGenerator.TONE_PROP_BEEP;
        }
    }

    /** 播放一次点按音。 */
    public static void click(Context c) {
        AppPrefs p = AppPrefs.get(c);
        if (!p.soundOn()) {
            return;
        }
        int vol = Math.max(1, Math.min(100, p.soundVol()));
        try {
            if (tg == null || madeVol != vol) {
                release();
                tg = new ToneGenerator(AudioManager.STREAM_MUSIC, vol);
                madeVol = vol;
            }
            tg.startTone(toneOf(p.soundType()), 35);
        } catch (Throwable ignored) {
            // 个别设备上 ToneGenerator 可能不可用，静默跳过，不影响功能
        }
    }

    /** 试听（设置页用，忽略开关）。 */
    public static void preview(Context c, int vol, String type) {
        try {
            if (tg == null || madeVol != vol) {
                release();
                tg = new ToneGenerator(AudioManager.STREAM_MUSIC, Math.max(1, Math.min(100, vol)));
                madeVol = vol;
            }
            tg.startTone(toneOf(type), 60);
        } catch (Throwable ignored) {
        }
    }

    public static void release() {
        try {
            if (tg != null) {
                tg.release();
            }
        } catch (Throwable ignored) {
        }
        tg = null;
        madeVol = -1;
    }

    /** 点一下：音效 + 轻微震动。 */
    public static void tap(View v) {
        if (v == null) {
            return;
        }
        click(v.getContext());
        try {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Throwable ignored) {
        }
    }
}
