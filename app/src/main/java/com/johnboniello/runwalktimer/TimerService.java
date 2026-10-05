package com.johnboniello.runwalktimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs the workout clock and plays every cue. It is a foreground service holding a
 * partial wake lock, so it keeps time and keeps talking with the screen off.
 * Time is measured with elapsedRealtime, so a late tick never makes the schedule drift.
 */
public class TimerService extends Service implements TextToSpeech.OnInitListener {
    static final String ACTION_START = "start", ACTION_PAUSE = "pause", ACTION_RESET = "reset", ACTION_TEST = "test";
    static final String EXTRA_SETTINGS = "settings";

    private static final String CHANNEL = "workout";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS = "workout";

    // Workout state, read by the UI through stateJson().
    private static final Object LOCK = new Object();
    private static Settings active;      // settings of the workout in progress, null when none
    private static long accMs;           // time banked before the current run segment
    private static long startedAt;       // elapsedRealtime when the current segment started
    private static boolean running;

    private HandlerThread thread;
    private Handler handler;
    private Sounds sounds;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final List<String> pendingSpeech = new ArrayList<>();
    private AudioManager audio;
    private AudioFocusRequest focus;
    private AudioAttributes cueAttrs;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private long lastSec;
    private String lastNotificationKey = "";

    static long elapsedMs() {
        synchronized (LOCK) {
            return running ? accMs + SystemClock.elapsedRealtime() - startedAt : accMs;
        }
    }

    static String stateJson() {
        synchronized (LOCK) {
            JSONObject o = new JSONObject();
            try {
                o.put("elapsed", elapsedMs() / 1000.0);
                o.put("running", running);
                o.put("started", active != null);
                if (active != null) o.put("settings", active.toJson());
            } catch (JSONException ignored) {
            }
            return o.toString();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("workout-timer");
        thread.start();
        handler = new Handler(thread.getLooper());

        // Navigation-guidance audio plays over music and ducks it, like a running app's voice.
        cueAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        sounds = new Sounds(cueAttrs);
        audio = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(cueAttrs)
                .build();
        vibrator = getSystemService(Vibrator.class);
        wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunWalkTimer:workout");
        wakeLock.setReferenceCounted(false);
        tts = new TextToSpeech(this, this);

        NotificationChannel ch = new NotificationChannel(CHANNEL, "Workout", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shows the current interval while a workout is running");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public void onInit(int status) {
        handler.post(() -> {
            if (status != TextToSpeech.SUCCESS) return;
            if (tts.setLanguage(Locale.getDefault()) < 0) tts.setLanguage(Locale.US);
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            ttsReady = true;
            for (String s : pendingSpeech) speak(s);
            pendingSpeech.clear();
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            // Restarted by the system after the process was killed: pick the workout back up.
            restore(this);
            synchronized (LOCK) {
                if (active == null) { stopSelf(); return START_NOT_STICKY; }
            }
            goForeground();
            synchronized (LOCK) { if (running) handler.post(this::resumeTicking); }
            return START_STICKY;
        }
        switch (action) {
            case ACTION_START:
                goForeground();
                String json = intent.getStringExtra(EXTRA_SETTINGS);
                handler.post(() -> start(json));
                break;
            case ACTION_PAUSE:
                handler.post(this::pause);
                break;
            case ACTION_RESET:
                handler.post(() -> reset(startId));
                break;
            case ACTION_TEST:
                handler.post(() -> test(startId));
                break;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (wakeLock.isHeld()) wakeLock.release();
        tts.shutdown();
        sounds.release();
        audio.abandonAudioFocusRequest(focus);
        thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- controls

    private void start(String settingsJson) {
        boolean fresh;
        synchronized (LOCK) {
            if (running) return;
            fresh = active == null;
            if (fresh) { active = Settings.parse(settingsJson); accMs = 0; }
            startedAt = SystemClock.elapsedRealtime();
            running = true;
        }
        if (fresh) { cue(Sounds.GO); speak("Run"); }
        persist();
        resumeTicking();
    }

    private void resumeTicking() {
        wakeLock.acquire(12 * 60 * 60 * 1000L);
        lastSec = elapsedMs() / 1000;
        lastNotificationKey = "";
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void pause() {
        synchronized (LOCK) {
            if (!running) return;
            accMs += SystemClock.elapsedRealtime() - startedAt;
            running = false;
        }
        handler.removeCallbacks(tick);
        if (wakeLock.isHeld()) wakeLock.release();
        persist();
        updateNotification(true);
    }

    private void reset(int startId) {
        synchronized (LOCK) {
            active = null; accMs = 0; running = false;
        }
        handler.removeCallbacks(tick);
        if (wakeLock.isHeld()) wakeLock.release();
        persist();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId); // a newer Start keeps the service alive
    }

    private void test(int startId) {
        cue(Sounds.TICK);
        handler.postDelayed(() -> cue(Sounds.GO), 400);
        handler.postDelayed(() -> { cue(Sounds.EAT_START); speak("Start eating"); }, 1200);
        handler.postDelayed(() -> { cue(Sounds.DRINK); speak("Drink"); }, 3600);
        handler.postDelayed(() -> {
            synchronized (LOCK) { if (active == null) stopSelf(startId); }
        }, 8000);
    }

    // ---------------------------------------------------------------- the clock

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Settings s;
            synchronized (LOCK) {
                if (!running) return;
                s = active;
            }
            long el = elapsedMs();
            long cur = el / 1000;
            if (cur - lastSec > 3) lastSec = cur - 1; // after a long stall, skip the stale beeps
            while (lastSec < cur) fire(s, ++lastSec);
            updateNotification(false);
            handler.postDelayed(this, 1000 - (elapsedMs() % 1000) + 5);
        }
    };

    /** Everything that happens at whole second {@code sec} of the workout. */
    private void fire(Settings s, long sec) {
        long cyc = s.run + s.walk, pos = sec % cyc;
        if (sec > 0 && pos == s.run) { cue(Sounds.GO); speak("Walk"); vibrate(0, 400); }
        else if (sec > 0 && pos == 0) { cue(Sounds.GO); speak("Run"); vibrate(0, 400); }
        else {
            long r = pos < s.run ? s.run - pos : cyc - pos;
            if (r <= 5) { cue(Sounds.TICK); vibrate(0, 60); }
        }
        if (s.eatOn && sec > 0) {
            long pe = sec % (s.eatEvery + s.eatWin);
            if (pe == s.eatEvery) { cue(Sounds.EAT_START); speak("Start eating"); vibrate(0, 200, 100, 200); }
            else if (pe == 0) { cue(Sounds.EAT_STOP); speak("Stop eating"); vibrate(0, 200, 100, 200); }
        }
        if (s.drinkOn && sec > 0 && sec % s.drinkEvery == 0) {
            cue(Sounds.DRINK); speak("Drink"); vibrate(0, 100, 80, 100, 80, 100);
        }
    }

    // ---------------------------------------------------------------- output

    private final Runnable releaseFocus = () -> audio.abandonAudioFocusRequest(focus);

    private void holdFocus() {
        handler.removeCallbacks(releaseFocus);
        audio.requestAudioFocus(focus);
        handler.postDelayed(releaseFocus, 3000);
    }

    private void cue(int sound) {
        holdFocus();
        sounds.play(sound);
    }

    private void speak(String text) {
        if (!ttsReady) { pendingSpeech.add(text); return; }
        holdFocus();
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, text + SystemClock.elapsedRealtime());
    }

    private void vibrate(long... pattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
    }

    // ---------------------------------------------------------------- notification

    private void goForeground() {
        Notification n = buildNotification(false);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    /** Rebuilds the notification only when the phase or fuel state changes; the countdown runs itself. */
    private void updateNotification(boolean force) {
        Settings s;
        synchronized (LOCK) { s = active; }
        if (s == null) return;
        long el = elapsedMs() / 1000;
        long cyc = s.run + s.walk;
        boolean eating = s.eatOn && el % (s.eatEvery + s.eatWin) >= s.eatEvery;
        String key = (el / cyc) + ":" + (el % cyc < s.run) + ":" + eating;
        if (!force && key.equals(lastNotificationKey)) return;
        lastNotificationKey = key;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(force));
    }

    private Notification buildNotification(boolean paused) {
        Settings s;
        boolean isRunning;
        synchronized (LOCK) { s = active; isRunning = running; }
        long elMs = elapsedMs();

        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_WORKOUT)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        if (s == null) {
            return b.setContentTitle("Run/Walk Timer").setContentText("Starting").build();
        }

        long el = elMs / 1000, cyc = s.run + s.walk, pos = el % cyc;
        boolean isRun = pos < s.run;
        long remMs = (isRun ? s.run : cyc) * 1000L - (elMs % (cyc * 1000L));
        boolean eating = s.eatOn && el % (s.eatEvery + s.eatWin) >= s.eatEvery;
        String text = "Interval " + (el / cyc + 1) + (eating ? " · Eating now" : "");

        if (paused || !isRunning) {
            b.setContentTitle("Paused · " + (isRun ? "Run" : "Walk") + " " + fmt((remMs + 999) / 1000) + " left")
                    .setContentText(text)
                    .setShowWhen(false);
            b.addAction(new Notification.Action.Builder(null, "Resume", serviceIntent(ACTION_START, 1)).build());
        } else {
            b.setContentTitle(isRun ? "Run" : "Walk")
                    .setContentText(text)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis() + remMs)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true);
            b.addAction(new Notification.Action.Builder(null, "Pause", serviceIntent(ACTION_PAUSE, 2)).build());
        }
        return b.build();
    }

    private PendingIntent serviceIntent(String action, int code) {
        Intent i = new Intent(this, TimerService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE);
    }

    private static String fmt(long sec) {
        return sec / 60 + ":" + String.format(Locale.US, "%02d", sec % 60);
    }

    // ---------------------------------------------------------------- survive a process restart

    private void persist() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        synchronized (LOCK) {
            if (active == null) { e.clear(); }
            else {
                e.putString("settings", active.toJson().toString())
                        .putLong("accMs", accMs)
                        .putLong("startedAt", startedAt)
                        .putBoolean("running", running);
            }
        }
        e.apply();
    }

    /** Reloads a workout saved before the process was killed. Returns true if one is in progress. */
    static boolean restore(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = p.getString("settings", null);
        synchronized (LOCK) {
            if (active != null) return true;
            if (json == null) return false;
            long started = p.getLong("startedAt", 0);
            if (started > SystemClock.elapsedRealtime()) { p.edit().clear().apply(); return false; } // phone rebooted
            active = Settings.parse(json);
            accMs = p.getLong("accMs", 0);
            startedAt = started;
            running = p.getBoolean("running", false);
            return true;
        }
    }
}
