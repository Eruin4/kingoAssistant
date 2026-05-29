package com.example.homeassistantvoice;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MicService extends Service {
    static final String ACTION_TOGGLE = "com.example.homeassistantvoice.TOGGLE_MIC";
    static final String ACTION_WIDGET_UPDATE = "com.example.homeassistantvoice.WIDGET_UPDATE";
    static final String ACTION_SCHEDULE_CHANGED = "com.example.homeassistantvoice.SCHEDULE_CHANGED";
    static final String EXTRA_RECORDING = "recording";

    private static final int NOTIFICATION_ID = 9001;

    private final WavRecorder recorder = new WavRecorder();
    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private TextToSpeech tts;
    private File lastWav;
    private boolean recording = false;

    @Override
    public void onCreate() {
        super.onCreate();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            if (recording) {
                stopRecording();
            } else {
                startRecording();
            }
        }
        return START_NOT_STICKY;
    }

    private void startRecording() {
        try {
            File dir = new File(getExternalFilesDir(null), "recordings");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("recordings directory create failed");
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            lastWav = new File(dir, "widget_" + stamp + ".wav");
            recorder.start(lastWav);
            recording = true;
            startForeground(NOTIFICATION_ID, buildNotification("녹음 중..."));
            broadcastState(true);
        } catch (Exception e) {
            stopSelf();
        }
    }

    private void stopRecording() {
        recorder.stop();
        recording = false;
        broadcastState(false);
        updateNotification("서버 전송 중...");

        if (lastWav == null || !lastWav.isFile()) {
            stopSelf();
            return;
        }

        queue.execute(() -> {
            try {
                JSONObject response = uploadWav(lastWav);
                if (response != null) {
                    String text = response.optString("response", "");
                    broadcastScheduleChanged();
                    if (!text.isEmpty()) {
                        speak(text);
                        // Wait a bit for TTS to finish before stopping
                        Thread.sleep(Math.min(text.length() * 150L, 10000));
                    }
                }
            } catch (Exception ignored) {
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        });
    }

    private JSONObject uploadWav(File wav) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String serverUrl = prefs.getString(MainActivity.PREF_SERVER_URL, "");
        String apiKey = prefs.getString(MainActivity.PREF_API_KEY, "");

        if (serverUrl.isEmpty()) {
            return null;
        }

        String boundary = "WidgetBoundary" + System.currentTimeMillis();
        try {
            String endpoint = serverUrl;
            if (!endpoint.endsWith("/")) endpoint += "/";
            endpoint += "stt-command";

            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(300000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (!apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            conn.setDoOutput(true);

            try (OutputStream out = conn.getOutputStream(); FileInputStream in = new FileInputStream(wav)) {
                writeAscii(out, "--" + boundary + "\r\n");
                writeAscii(out, "Content-Disposition: form-data; name=\"audio\"; filename=\"" + wav.getName() + "\"\r\n");
                writeAscii(out, "Content-Type: audio/wav\r\n\r\n");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                writeAscii(out, "\r\n--" + boundary + "--\r\n");
            }

            int code = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return new JSONObject(response.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private void writeAscii(OutputStream out, String value) throws java.io.IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private void speak(String text) {
        if (tts != null && text != null && !text.trim().isEmpty()) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "widget-tts-" + System.currentTimeMillis());
        }
    }

    private Notification buildNotification(String text) {
        Intent toggleIntent = new Intent(this, MicService.class);
        toggleIntent.setAction(ACTION_TOGGLE);
        PendingIntent pendingIntent = PendingIntent.getService(
                this, 0, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, MainActivity.CHANNEL_ID)
                .setContentTitle("Home Voice")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    private void broadcastState(boolean isRecording) {
        Intent update = new Intent(ACTION_WIDGET_UPDATE);
        update.putExtra(EXTRA_RECORDING, isRecording);
        update.setPackage(getPackageName());
        sendBroadcast(update);
    }

    private void broadcastScheduleChanged() {
        Intent update = new Intent(ACTION_SCHEDULE_CHANGED);
        update.setPackage(getPackageName());
        sendBroadcast(update);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        recorder.stop();
        queue.shutdownNow();
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }
}
