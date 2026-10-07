package fr.piika.puppyphone;

import android.media.AudioManager;
import android.media.ToneGenerator;

/** Bips DTMF du clavier. */
final class Tones {
    private Tones() { }
    static ToneGenerator tg;

    static void play(String d) {
        try {
            if (tg == null) tg = new ToneGenerator(AudioManager.STREAM_DTMF, 70);
            int t;
            switch (d) {
                case "*": t = ToneGenerator.TONE_DTMF_S; break;
                case "#": t = ToneGenerator.TONE_DTMF_P; break;
                default: t = ToneGenerator.TONE_DTMF_0 + Integer.parseInt(d);
            }
            tg.startTone(t, 130);
        } catch (Exception ignored) { }
    }
}
