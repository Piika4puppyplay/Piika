package fr.piika.puppyphone;

import android.content.Context;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * Les défis — Piika, embarqué hors-ligne dans PuppyPhone (son appli de suivi de défis, inchangée).
 * Par défaut SOBRE : on garde l'habillage d'origine de Piika. Un thème PuppyPlay (néon) est
 * activable dans les Réglages du lanceur ; il ne fait que réhabiller, sans toucher à la logique de Piika.
 */
public class DefisActivity extends PupWebActivity {
    @Override String host() { return "pupdefis.local"; }
    @Override String page() { return "piika/index.html"; }
    @Override String uiName() { return "PiikaUI"; }

    static boolean skinOn(Context c) {
        return "1".equals(c.getSharedPreferences("pup_store", MODE_PRIVATE).getString("piikaSkin", null));
    }

    @Override void onReady() {
        super.onReady();
        if (web == null) return;
        // Piika enregistre ses sauvegardes via window.claude.use("downloads"). Hors Cowork ce hook n'existe pas et
        // le repli <a download> ne marche pas dans la WebView → on fournit un pont natif qui écrit dans Téléchargements/Piika.
        web.evaluateJavascript("(function(){if(!window.claude){window.claude={use:function(n){return n==='downloads'?{save:function(o){return new Promise(function(res){try{Pup.saveText(o.filename,typeof o.data==='string'?o.data:'');Pup.toast('Sauvegarde enregistrée dans Téléchargements/Piika 🦴')}catch(e){}res()})}}:null}}}})()", null);
        if (skinOn(this)) {
            String css = ":root,:root[data-theme=\"dark\"],:root[data-theme=\"light\"]{"
                    + "--bg:#140a24!important;--card:#241138!important;--txt:#f4e9ff!important;"
                    + "--mut:#b79fd4!important;--line:#3a2259!important;--warn:#ffb627!important}"
                    + "body{background:radial-gradient(70% 40% at 20% 0%,rgba(155,92,255,.28),transparent 70%),"
                    + "radial-gradient(60% 40% at 100% 20%,rgba(255,63,164,.2),transparent 70%),"
                    + "radial-gradient(60% 50% at 50% 100%,rgba(41,230,255,.14),transparent 70%),#140a24!important}"
                    + "h1,h2{text-shadow:0 0 12px rgba(255,63,164,.5)}"
                    + ".card{box-shadow:0 10px 30px rgba(0,0,0,.5),0 0 24px -10px rgba(155,92,255,.6)!important}";
            String js = "(function(){var s=document.getElementById('pupskin');if(!s){s=document.createElement('style');s.id='pupskin';document.head.appendChild(s);}s.textContent=" + JSONObject.quote(css) + ";})()";
            web.evaluateJavascript(js, null);
        } else {
            web.evaluateJavascript("(function(){var s=document.getElementById('pupskin');if(s)s.remove();})()", null);
        }
    }

    Object bridge() { return new Pont(); }

    /** Pont minimal (Piika est autonome) : thème + écriture de la sauvegarde dans Téléchargements/Piika. */
    class Pont extends Common {
        @JavascriptInterface public boolean puppySkin() { return skinOn(DefisActivity.this); }
        @JavascriptInterface public void saveText(String name, String data) {
            final String n = (name == null || name.isEmpty()) ? "piika-sauvegarde.json" : name;
            final byte[] bytes = (data == null ? "" : data).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            new Thread(() -> {
                try { PupSave.save(DefisActivity.this, "download", "Piika", n, "application/json", o -> o.write(bytes)); }
                catch (Exception e) { ui.post(() -> toast("Sauvegarde impossible : " + e.getMessage())); }
            }).start();
        }
        void toast(String s) { try { android.widget.Toast.makeText(DefisActivity.this, s, android.widget.Toast.LENGTH_LONG).show(); } catch (Exception ignored) { } }
    }
}
