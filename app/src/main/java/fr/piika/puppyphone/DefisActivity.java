package fr.piika.puppyphone;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Les défis — Piika, embarqué hors-ligne dans PuppyPhone (sa PWA, STRICTEMENT identique à la version Netlify).
 * Tout ce qui est propre à PuppyOS est AJOUTÉ PAR-DESSUS ici, jamais écrit dans Piika :
 *   • thème PuppyPlay optionnel (sobre par défaut) — injecté en CSS ;
 *   • horloge de confiance anti-triche — injectée en JS : Piika lit l'heure de l'OS PuppyPhone
 *     (ancrée sur l'heure internet), pas celle, trafiquable, du téléphone, et sans aller sur Internet elle-même ;
 *   • service worker neutralisé (pas de cache concurrent dans la WebView).
 * Comme on ne touche pas aux fichiers de Piika, une mise à jour des défis = simple recopie de la nouvelle Piika.
 */
public class DefisActivity extends PupWebActivity {
    @Override String host() { return "pupdefis.local"; }
    @Override String page() { return "piika/index.html"; }
    @Override String uiName() { return "PiikaUI"; }

    /** Injecté tout en haut de <head>, AVANT le code de Piika : SW coupé + Date() basée sur l'heure de confiance de PuppyPhone. */
    static final String SHIM =
        "(function(){try{"
      + "if(navigator.serviceWorker){try{navigator.serviceWorker.register=function(){return Promise.reject(new Error('off'))}}catch(e){}}"
      + "var _D=Date,p0=(window.performance&&performance.now)?performance.now():0,base=null;"
      + "try{var t=(window.Pup&&Pup.netNow)?Pup.netNow():-1;if(t&&t>0)base=t;}catch(e){}"
      + "function pn(){return (window.performance&&performance.now)?performance.now():0;}"
      + "function tnow(){return base==null?_D.now():Math.round(base+(pn()-p0));}"
      + "function ND(a,b,c,d,e,f,g){switch(arguments.length){case 0:return new _D(tnow());case 1:return new _D(a);case 2:return new _D(a,b);case 3:return new _D(a,b,c);case 4:return new _D(a,b,c,d);case 5:return new _D(a,b,c,d,e);case 6:return new _D(a,b,c,d,e,f);default:return new _D(a,b,c,d,e,f,g);}}"
      + "ND.prototype=_D.prototype;ND.now=tnow;ND.parse=_D.parse;ND.UTC=_D.UTC;window.Date=ND;"
      + "window.__pupTrusted=function(){return base!=null};"
      + "window.__pupNetTime=function(x){try{x=+x;if(x>0){base=x;p0=pn();}}catch(e){}};"
      + "}catch(e){}})();";

    static boolean skinOn(Context c) {
        return "1".equals(c.getSharedPreferences("pup_store", MODE_PRIVATE).getString("piikaSkin", null));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Met à jour l'horloge de confiance en arrière-plan, puis rebase l'heure dans Piika quand c'est prêt.
        PupTime.syncAsync(this, t -> {
            if (t > 0) ui.post(() -> { if (web != null) web.evaluateJavascript("window.__pupNetTime&&window.__pupNetTime(" + t + ")", null); });
        });
    }

    /** On sert index.html en y glissant le shim en tête de <head>, sans modifier le fichier sur le disque. */
    @Override WebResourceResponse serve(Uri u) {
        if (u != null && host().equals(u.getHost())) {
            String p = u.getPath();
            if (p == null || p.equals("/") || p.equals("/piika/index.html")) {
                try {
                    String html = readAll(getAssets().open("www/piika/index.html"));
                    html = html.replaceFirst("(?i)<head>", "<head><script>" + java.util.regex.Matcher.quoteReplacement(SHIM) + "</script>");
                    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                    return new WebResourceResponse("text/html", "utf-8", 200, "OK", new HashMap<>(), new ByteArrayInputStream(bytes));
                } catch (Exception ignored) { }
            }
        }
        return super.serve(u);
    }

    static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    @Override void onReady() {
        super.onReady();
        if (web == null) return;
        // Correctif d'affichage : la WebView PuppyPhone est plein écran (derrière les barres système) et ne remplit pas
        // env(safe-area-inset-*). On réutilise les marges système --st/--sb de PuppyPhone pour que la barre d'onglets du bas
        // de Piika (position:fixed;bottom:0) remonte AU-DESSUS de la barre de navigation Android, et que rien ne soit coupé.
        String fit = "html{padding-top:calc(env(safe-area-inset-top,0px) + var(--st,0px))!important}"
                + "nav{padding-bottom:calc(8px + env(safe-area-inset-bottom,0px) + var(--sb,0px))!important}"
                + "body{padding-bottom:calc(96px + var(--sb,0px))!important}"
                + "#sosf{bottom:calc(84px + env(safe-area-inset-bottom,0px) + var(--sb,0px))!important}";
        web.evaluateJavascript("(function(){var s=document.getElementById('pupfit');if(!s){s=document.createElement('style');s.id='pupfit';document.head.appendChild(s);}s.textContent=" + JSONObject.quote(fit) + ";})()", null);
        if (skinOn(this)) {
            String css = ":root,:root[data-theme=\"dark\"],:root[data-theme=\"light\"]{"
                    + "--bg:#140a24!important;--card:#241138!important;--txt:#f4e9ff!important;"
                    + "--mut:#b79fd4!important;--line:#3a2259!important;--warn:#ffb627!important}"
                    + "body{background:radial-gradient(70% 40% at 20% 0%,rgba(155,92,255,.28),transparent 70%),"
                    + "radial-gradient(60% 40% at 100% 20%,rgba(255,63,164,.2),transparent 70%),"
                    + "radial-gradient(60% 50% at 50% 100%,rgba(41,230,255,.14),transparent 70%),#140a24!important}"
                    + "h1,h2{text-shadow:0 0 12px rgba(255,63,164,.5)}"
                    + ".card{box-shadow:0 10px 30px rgba(0,0,0,.5),0 0 24px -10px rgba(155,92,255,.6)!important}";
            web.evaluateJavascript("(function(){var s=document.getElementById('pupskin');if(!s){s=document.createElement('style');s.id='pupskin';document.head.appendChild(s);}s.textContent=" + JSONObject.quote(css) + ";})()", null);
        } else {
            web.evaluateJavascript("(function(){var s=document.getElementById('pupskin');if(s)s.remove();})()", null);
        }
        // Piika enregistre via window.claude.use("downloads") (hook Cowork). Absent ici → on écrit dans Téléchargements/Piika.
        web.evaluateJavascript("(function(){if(!window.claude){window.claude={use:function(n){return n==='downloads'?{save:function(o){return new Promise(function(res){try{Pup.saveText(o.filename,typeof o.data==='string'?o.data:'');Pup.toast('Sauvegarde enregistrée dans Téléchargements/Piika 🦴')}catch(e){}res()})}}:null}}}})()", null);
    }

    Object bridge() { return new Pont(); }

    /** Pont minimal (Piika est autonome) : thème + écriture de la sauvegarde dans Téléchargements/Piika. */
    class Pont extends Common {
        @JavascriptInterface public boolean puppySkin() { return skinOn(DefisActivity.this); }
        @JavascriptInterface public void saveText(String name, String data) {
            final String n = (name == null || name.isEmpty()) ? "piika-sauvegarde.json" : name;
            final byte[] bytes = (data == null ? "" : data).getBytes(StandardCharsets.UTF_8);
            new Thread(() -> {
                try { PupSave.save(DefisActivity.this, "download", "Piika", n, "application/json", o -> o.write(bytes)); }
                catch (Exception e) { ui.post(() -> toast("Sauvegarde impossible : " + e.getMessage())); }
            }).start();
        }
        void toast(String s) { try { android.widget.Toast.makeText(DefisActivity.this, s, android.widget.Toast.LENGTH_LONG).show(); } catch (Exception ignored) { } }
    }
}
