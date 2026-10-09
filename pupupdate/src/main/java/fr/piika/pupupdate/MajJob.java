package fr.piika.pupupdate;

import android.app.job.JobParameters;
import android.app.job.JobService;

import org.json.JSONObject;

/** Vérification en arrière-plan toutes les ~6 h (réseau requis) : notification si PuppyPhone a une nouvelle version. */
public class MajJob extends JobService {
    @Override public boolean onStartJob(JobParameters p) {
        new Thread(() -> {
            try {
                JSONObject r = Maj.check(this);
                JSONObject pp = r.optJSONObject("pp");
                if (pp != null && pp.optBoolean("installed")) {
                    long latest = pp.optLong("latest"), cur = pp.optLong("cur");
                    if (latest > cur && latest != Maj.sp(this).getLong("notified", 0) && Maj.sp(this).getBoolean("notify", true)) {
                        Maj.sp(this).edit().putLong("notified", latest).apply();
                        Maj.notifyNew(this, latest, Maj.firstLine(r.optJSONArray("notes")));
                    }
                }
            } catch (Exception ignored) { }
            jobFinished(p, false);
        }).start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters p) { return true; }
}
