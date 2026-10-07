package fr.piika.puppyphone;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Écran d'appel PupPhone (entrant, en cours, en attente) — s'affiche même verrouillé. */
public class CallActivity extends DialerActivity {
    PowerManager.WakeLock prox;

    @Override String mode() { return "call"; }

    @Override void beforeWeb() {
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) prox = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "pupphone:prox");
        } catch (Exception ignored) { }
    }

    @Override void addBridges(WebView w) {
        super.addBridges(w);
        w.addJavascriptInterface(new CallBridge(), "Call");
    }

    @Override void askPerms() { }

    @Override protected void onResume() {
        super.onResume();
        PupInCallService.listener = () -> { emit("calls", callsJson()); updateProx(); if (PupInCallService.current() == null && PupInCallService.ringing() == null) ui.postDelayed(this::finishIfIdle, 1600); };
        emit("calls", callsJson());
        updateProx();
    }

    @Override protected void onPause() {
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (prox != null && prox.isHeld()) prox.release();
        PupInCallService.listener = null;
        super.onDestroy();
    }

    void finishIfIdle() { if (PupInCallService.current() == null && PupInCallService.ringing() == null) finishAndRemoveTask(); }

    void updateProx() {
        ui.post(() -> {
            if (prox == null) return;
            Call c = PupInCallService.current();
            CallAudioState a = PupInCallService.audio;
            boolean earpiece = a == null || a.getRoute() == CallAudioState.ROUTE_EARPIECE || a.getRoute() == CallAudioState.ROUTE_WIRED_OR_EARPIECE;
            boolean want = c != null && (c.getState() == Call.STATE_ACTIVE || c.getState() == Call.STATE_DIALING) && earpiece;
            if (want && !prox.isHeld()) prox.acquire(3 * 60 * 60 * 1000L);
            else if (!want && prox.isHeld()) prox.release();
        });
    }

    static String stateName(int s) {
        switch (s) {
            case Call.STATE_RINGING: return "ringing";
            case Call.STATE_DIALING: case Call.STATE_CONNECTING: case Call.STATE_PULLING_CALL: return "dialing";
            case Call.STATE_ACTIVE: return "active";
            case Call.STATE_HOLDING: return "holding";
            case Call.STATE_DISCONNECTING: case Call.STATE_DISCONNECTED: return "ended";
            default: return "other";
        }
    }

    String callsJson() {
        try {
            JSONObject o = new JSONObject();
            JSONArray a = new JSONArray();
            List<Call> l;
            synchronized (PupInCallService.calls) { l = new ArrayList<>(PupInCallService.calls); }
            for (Call c : l) {
                String num = PupInCallService.number(c);
                String[] ct = SmsCore.contact(this, num);
                a.put(new JSONObject().put("id", System.identityHashCode(c)).put("num", num).put("name", ct[0] == null ? "" : ct[0]).put("photo", ct[1] == null ? "" : ct[1])
                        .put("state", stateName(c.getState())).put("since", c.getDetails().getConnectTimeMillis())
                        .put("canHold", c.getDetails().can(Call.Details.CAPABILITY_HOLD))
                        .put("conf", c.getDetails().hasProperty(Call.Details.PROPERTY_CONFERENCE)));
            }
            o.put("calls", a);
            CallAudioState s = PupInCallService.audio;
            o.put("muted", s != null && s.isMuted());
            o.put("route", s == null ? CallAudioState.ROUTE_EARPIECE : s.getRoute());
            o.put("routes", s == null ? CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER : s.getSupportedRouteMask());
            o.put("now", System.currentTimeMillis());
            return o.toString();
        } catch (Exception e) { return "{\"calls\":[]}"; }
    }

    Call byId(int id) {
        synchronized (PupInCallService.calls) { for (Call c : PupInCallService.calls) if (System.identityHashCode(c) == id) return c; }
        return null;
    }

    class CallBridge {
        @JavascriptInterface public String calls() { return callsJson(); }
        @JavascriptInterface public void answer() { ui.post(PupInCallService::answer); }
        @JavascriptInterface public void hangup(int id) { ui.post(() -> { Call c = byId(id); if (c == null) PupInCallService.hangup(); else if (c.getState() == Call.STATE_RINGING) c.reject(false, null); else c.disconnect(); }); }
        @JavascriptInterface public void rejectSms(int id, String text) {
            ui.post(() -> {
                Call c = byId(id);
                if (c == null) return;
                String num = PupInCallService.number(c);
                c.reject(false, null);
                if (num != null && !num.isEmpty()) new Thread(() -> SmsCore.sendSms(CallActivity.this, num, text)).start();
            });
        }
        @JavascriptInterface public void mute(boolean m) { ui.post(() -> PupInCallService.setMute(m)); }
        @JavascriptInterface public void route(int r) { ui.post(() -> { PupInCallService.route(r); updateProx(); }); }
        @JavascriptInterface public void hold(int id, boolean h) { ui.post(() -> { Call c = byId(id); if (c != null) { if (h) c.hold(); else c.unhold(); } }); }
        @JavascriptInterface public void dtmf(String d) {
            ui.post(() -> {
                Call c = PupInCallService.current();
                if (c != null && d != null && !d.isEmpty()) { c.playDtmfTone(d.charAt(0)); ui.postDelayed(c::stopDtmfTone, 180); }
            });
        }
        @JavascriptInterface public void dismissKeyguard() {
            ui.post(() -> { try { if (Build.VERSION.SDK_INT >= 26) getSystemService(KeyguardManager.class).requestDismissKeyguard(CallActivity.this, null); } catch (Exception ignored) { } });
        }
        @JavascriptInterface public void close() { ui.post(() -> { if (PupInCallService.current() == null && PupInCallService.ringing() == null) finishAndRemoveTask(); else moveTaskToBack(true); }); }
    }
}
