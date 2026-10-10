package fr.piika.puppyphone;

/**
 * PupCaméra Éco — caméra d'urgence « espace faible ».
 * Exactement la même caméra que PupCamera (code intact), mais elle filme en H.265 forcé à débit très réduit
 * → fichiers ~2 à 3× plus petits (ex. un 4K ~1 Go devient ~400 Mo), sans étape de décompression (vidéo lisible direct).
 */
public class EcoCameraActivity extends CameraActivity {
    { ecoFactor = 0.4f; forceHevc = true; ecoNoTs = true; }

    @Override void onCamReady() {
        super.onCamReady();
        // petit badge pour qu'on sache qu'on est dans le mode éco (compression forte)
        String js = "(function(){if(document.getElementById('ecoBadge'))return;var b=document.createElement('div');b.id='ecoBadge';"
                + "b.textContent='\\uD83D\\uDEA8 \\u00C9CO \\u00B7 compression forte (H.265)';"
                + "b.style.cssText='position:fixed;left:50%;top:calc(env(safe-area-inset-top,0px) + 10px);transform:translateX(-50%);z-index:999999;"
                + "pointer-events:none;padding:6px 14px;border-radius:999px;font:600 12px system-ui,-apple-system,sans-serif;color:#2a0016;white-space:nowrap;"
                + "background:linear-gradient(135deg,#ffd166,#ff7a7a);box-shadow:0 2px 10px rgba(0,0,0,.55),inset 0 0 0 1px rgba(255,255,255,.45)';"
                + "document.body.appendChild(b);})()";
        runJs(js);
    }
}
