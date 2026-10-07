package fr.piika.puppyphone;

import android.accessibilityservice.AccessibilityService;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Service d'accessibilité PupNav : héberge la barre PupNavigationBar (bouton Retour fonctionnel partout),
 * masque la barre quand le clavier est ouvert et suit l'appli au premier plan (« Balade en cours »).
 * Il ne lit pas le contenu des écrans.
 */
public class PupNavA11y extends AccessibilityService implements PupNav.Host {
    static PupNavA11y I;
    static volatile String fgPkg = "", fgClass = "";
    PupNav nav;

    @Override protected void onServiceConnected() {
        I = this;
        PupNav.stopOverlay(this);
        nav = new PupNav(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, this);
        nav.attach();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            String pk = e.getPackageName().toString();
            String cl = e.getClassName() == null ? "" : e.getClassName().toString();
            if (!pk.equals("com.android.systemui") && !cl.startsWith("android.widget") && !cl.startsWith("android.view")) {
                fgPkg = pk; fgClass = cl;
                if (nav != null) {
                    String hide = PupNav.prefs(this).getString("hideIn", "");
                    nav.setHiddenForApp(!hide.isEmpty() && ("," + hide + ",").contains("," + pk + ","));
                }
            }
        }
        if (nav != null) nav.setImeVisible(imeShown());
    }

    boolean imeShown() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        } catch (Exception ignored) { }
        return false;
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(android.content.Intent i) {
        if (nav != null) nav.detach();
        nav = null; I = null;
        return super.onUnbind(i);
    }
    @Override public void onDestroy() { if (nav != null) nav.detach(); nav = null; I = null; super.onDestroy(); }

    @Override public boolean back() { return performGlobalAction(GLOBAL_ACTION_BACK); }
    @Override public boolean home() { return performGlobalAction(GLOBAL_ACTION_HOME); }
}
