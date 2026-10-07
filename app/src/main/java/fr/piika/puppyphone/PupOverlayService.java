package fr.piika.puppyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.view.WindowManager;

/** Mode « overlay » (SYSTEM_ALERT_WINDOW) de la PupNavigationBar, utilisé quand l'accessibilité n'est pas activée. */
public class PupOverlayService extends Service implements PupNav.Host {
    PupNav nav;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("pupnav", "PupNav — barre de navigation", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "pupnav").setSmallIcon(R.drawable.ic_paw)
                .setContentTitle("PupNav en service 🐾").setContentText("Barre de navigation puppyplay active")
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, TasksActivity.class).putExtra("settings", true), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(4646, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(4646, n);
        } catch (Exception ignored) { }
        if (PupNavA11y.I != null || !PupNav.canOverlay(this)) { stopSelf(); return; }
        nav = new PupNav(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, this);
        nav.attach();
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (PupNavA11y.I != null) { stopSelf(); return START_NOT_STICKY; }
        if (nav != null) nav.refresh();
        return START_STICKY;
    }

    @Override public void onDestroy() { if (nav != null) nav.detach(); nav = null; super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public boolean back() { PupNavA11y a = PupNavA11y.I; return a != null && a.back(); }
    @Override public boolean home() { PupNav.goHome(this); return true; }
}
