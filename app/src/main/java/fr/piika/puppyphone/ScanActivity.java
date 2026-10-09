package fr.piika.puppyphone;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/** PupScan : scanner de documents (PDF / JPG) et lecteur de QR codes. Caméra via getUserMedia dans la page. */
public class ScanActivity extends PupWebActivity {
    @Override String host() { return "pupscan.local"; }
    @Override String page() { return "scan.html"; }
    @Override String uiName() { return "ScanUI"; }
    @Override Object bridge() { return new Bridge(); }

    final ArrayList<File> pages = new ArrayList<>();

    static byte[] fromDataUrl(String d) { int i = d.indexOf(','); return Base64.decode(i >= 0 ? d.substring(i + 1) : d, Base64.DEFAULT); }
    static String safe(String n) { String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim(); return s.isEmpty() ? "Scan" : s.length() > 60 ? s.substring(0, 60) : s; }

    class Bridge extends Common {
        @JavascriptInterface public void beginDoc() { for (File f : pages) f.delete(); pages.clear(); }
        @JavascriptInterface public boolean addPage(String dataUrl) {
            try {
                File f = new File(getCacheDir(), "scan_" + System.nanoTime() + ".jpg");
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(fromDataUrl(dataUrl)); }
                pages.add(f); return true;
            } catch (Exception e) { return false; }
        }
        /** Assemble les pages en PDF A4 dans Documents/PupScan. Renvoie {ok, uri, name} ou {ok:false, msg}. */
        @JavascriptInterface public String saveDoc(String name) {
            JSONObject r = new JSONObject();
            try {
                if (pages.isEmpty()) throw new Exception("Aucune page");
                String fn = safe(name) + ".pdf";
                Uri u = PupSave.save(ScanActivity.this, "doc", "PupScan", fn, "application/pdf", o -> {
                    PdfDocument pdf = new PdfDocument();
                    Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                    int n = 1;
                    for (File f : pages) {
                        BitmapFactory.Options op = new BitmapFactory.Options(); op.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(f.getAbsolutePath(), op);
                        boolean land = op.outWidth > op.outHeight;
                        int pw = land ? 842 : 595, ph = land ? 595 : 842;
                        PdfDocument.Page pg = pdf.startPage(new PdfDocument.PageInfo.Builder(pw, ph, n++).create());
                        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                        if (b != null) {
                            float m = 14, sc = Math.min((pw - 2 * m) / b.getWidth(), (ph - 2 * m) / b.getHeight());
                            float w = b.getWidth() * sc, h = b.getHeight() * sc;
                            Canvas cv = pg.getCanvas();
                            cv.drawColor(0xFFFFFFFF);
                            cv.drawBitmap(b, null, new RectF((pw - w) / 2, (ph - h) / 2, (pw + w) / 2, (ph + h) / 2), pp);
                            b.recycle();
                        }
                        pdf.finishPage(pg);
                    }
                    pdf.writeTo(o); pdf.close();
                });
                for (File f : pages) f.delete(); pages.clear();
                r.put("ok", true).put("uri", u.toString()).put("name", fn);
            } catch (Exception e) { try { r.put("ok", false).put("msg", String.valueOf(e.getMessage())); } catch (Exception ignored) { } }
            return r.toString();
        }
        /** Enregistre une page en JPG dans Pictures/PupScan. */
        @JavascriptInterface public String saveJpg(String dataUrl, String name) {
            JSONObject r = new JSONObject();
            try {
                byte[] b = fromDataUrl(dataUrl); String fn = safe(name) + ".jpg";
                Uri u = PupSave.save(ScanActivity.this, "img", "PupScan", fn, "image/jpeg", o -> o.write(b));
                r.put("ok", true).put("uri", u.toString()).put("name", fn);
            } catch (Exception e) { try { r.put("ok", false).put("msg", String.valueOf(e.getMessage())); } catch (Exception ignored) { } }
            return r.toString();
        }
        @JavascriptInterface public void openFile(String uri, String mime) { ui.post(() -> PupSave.open(ScanActivity.this, Uri.parse(uri), mime)); }
        @JavascriptInterface public void shareFile(String uri, String mime, String title) { ui.post(() -> PupSave.share(ScanActivity.this, Uri.parse(uri), mime, title)); }
        @JavascriptInterface public void wifiSettings() { ui.post(() -> { try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) { } }); }
    }
}
