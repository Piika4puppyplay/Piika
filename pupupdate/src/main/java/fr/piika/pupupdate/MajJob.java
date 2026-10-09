package fr.piika.pupupdate;

import android.app.job.JobParameters;
import android.app.job.JobService;

import org.json.JSONObject;

/** Vérification en arrière-plan toutes les ~6 h (réseau requis), indépendante de PuppyPhone : notifie une nouvelle version de PuppyPhone ET une nouvelle version de PupUpdate lui-même. */
public class MajJob extends JobService {
    @Override public boolean onStartJob(JobParameters p) {
        new Thread(() -> {
            try {
                JSONObject r = Maj.check(this);
                boolean notify = Maj.sp(this).getBoolean("notify", true);
                // Nouvelle version de PuppyPhone
                JSONObject pp = r.optJSONObject("pp");
                if (pp != null && pp.optBoolean("installed")) {
                    long latest = pp.optLong("latest"), cur = pp.optLong("cur");
                    if (latest > cur && latest != Maj.sp(this).getLong("notified", 0) && notify) {
                        Maj.sp(this).edit().putLong("notified", latest).apply();
                        Maj.notifyNew(this, latest, Maj.firstLine(r.optJSONArray("notes")));
                    }
                }
                // Nouvelle version de PupUpdate lui-même (surveillance indépendante)
                JSONObject self = r.optJSONObject("self");
                if (self != null) {
                    long sLatest = self.optLong("latest"), sCur = self.optLong("cur");
                    if (sLatest > sCur && sLatest != Maj.sp(this).getLong("notifiedSelf", 0) && notify) {
                        Maj.sp(this).edit().putLong("notifiedSelf", sLatest).apply();
                        Maj.notifySelf(this, sLatest);
                    }
                }
            } catch (Exception ignored) { }
            jobFinished(p, false);
        }).start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters p) { return true; }
}
