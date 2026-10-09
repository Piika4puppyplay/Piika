package fr.piika.puppyphone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

/**
 * « Filmer » un fond animé PuppyPhone : la scène est dessinée image par image dans l'encodeur vidéo du téléphone
 * (H.264, 1080 px de large, 30 images/s, 12 s), puis rangée dans Films › PupWall.
 * One UI n'accepte sur son verrouillage que les vidéos posées par sa Galerie : on lui prépare la vidéo toute faite.
 */
final class PupWallVideo {
    private PupWallVideo() { }
    interface Progress { void on(float p); }
    static final int FPS = 30, SECONDS = 12;

    static int[] size(Context c) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        try { c.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(dm); } catch (Exception e) { dm = c.getResources().getDisplayMetrics(); }
        int sw = Math.min(dm.widthPixels, dm.heightPixels), sh = Math.max(dm.widthPixels, dm.heightPixels);
        for (int w : new int[]{1080, 900, 720}) {
            int h = Math.round(w * (sh / (float) sw) / 16f) * 16;
            if (supported(w, h)) return new int[]{w, h};
        }
        return new int[]{720, 1280};
    }

    static boolean supported(int w, int h) {
        try {
            for (MediaCodecInfo ci : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                    MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(t).getVideoCapabilities();
                    if (vc != null && vc.isSizeSupported(w, h)) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** Filme la scène et renvoie l'adresse de la vidéo dans la galerie. */
    static Uri film(Context c, String id, Progress pr) throws Exception {
        int[] wh = size(c); int W = wh[0], H = wh[1];
        File tmp = new File(c.getCacheDir(), "pupwall_" + id + ".mp4");
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 14_000_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaMuxer mux = null; Surface in = null;
        try {
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            in = enc.createInputSurface();
            enc.start();
            mux = new MediaMuxer(tmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            LayeredScene sc = new LayeredScene(c, id);
            sc.size(W, H);
            int total = FPS * SECONDS, track = -1; long outIdx = 0;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean[] started = {false};
            for (int i = 0; i <= total; i++) {
                if (i < total) {
                    Canvas cv = in.lockHardwareCanvas();
                    try { sc.draw(cv, i / (float) FPS, 1f / FPS, .5f); } finally { in.unlockCanvasAndPost(cv); }
                } else enc.signalEndOfInputStream();
                // on vide l'encodeur au fur et à mesure (horodatage refait proprement : 30 images/s)
                while (true) {
                    int o = enc.dequeueOutputBuffer(bi, i < total ? 2_000 : 20_000);
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (i < total) break; else continue; }
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(enc.getOutputFormat()); mux.start(); started[0] = true; continue; }
                    if (o < 0) continue;
                    ByteBuffer b = enc.getOutputBuffer(o);
                    boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0 && started[0] && b != null) {
                        bi.presentationTimeUs = outIdx++ * 1_000_000L / FPS;
                        b.position(bi.offset); b.limit(bi.offset + bi.size);
                        mux.writeSampleData(track, b, bi);
                    }
                    enc.releaseOutputBuffer(o, false);
                    if (eos) break;
                }
                if (pr != null && i % 6 == 0) pr.on(i / (float) total * .92f);
            }
            if (pr != null) pr.on(.95f);
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            if (in != null) in.release();
            if (mux != null) try { mux.stop(); mux.release(); } catch (Exception ignored) { }
        }
        String name = "PupWall_" + id + "_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.FRANCE).format(new java.util.Date()) + ".mp4";
        Uri u = PupSave.save(c, "video", "PupWall", name, "video/mp4", o -> {
            try (FileInputStream fi = new FileInputStream(tmp)) { byte[] b = new byte[1 << 16]; int n; while ((n = fi.read(b)) > 0) o.write(b, 0, n); }
        });
        tmp.delete();
        if (pr != null) pr.on(1f);
        return u;
    }

    /** Une image fixe de la scène, posée directement sur le verrouillage (sans vidéo). */
    static boolean still(Context c, String id) {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            c.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
            int W = Math.min(dm.widthPixels, dm.heightPixels), H = Math.max(dm.widthPixels, dm.heightPixels);
            Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            LayeredScene sc = new LayeredScene(c, id); sc.size(W, H);
            Canvas cv = new Canvas(b);
            for (int i = 0; i < 90; i++) sc.draw(cv, i / 30f, 1 / 30f, .5f); // on laisse les effets se mettre en place
            android.app.WallpaperManager.getInstance(c).setBitmap(b, null, true, android.app.WallpaperManager.FLAG_LOCK);
            b.recycle();
            return true;
        } catch (Exception e) { return false; }
    }
}
