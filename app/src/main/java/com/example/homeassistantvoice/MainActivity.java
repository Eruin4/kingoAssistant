package com.example.homeassistantvoice;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

public class MainActivity extends Activity {
    private static final int REQ_RECORD_AUDIO = 1001;

    private final WavRecorder recorder = new WavRecorder();
    private final ExecutorService queue = Executors.newSingleThreadExecutor();

    private TextToSpeech tts;
    private MediaPlayer player;
    private File lastWav;
    private Button recordButton;
    private TextView logView;
    private EditText serverEdit;
    private EditText ttsEdit;
    private CheckBox autoBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN);
            }
        });
        setContentView(buildUi());
        log("Ready. Server STT mode.");
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Home Voice - Whisper");
        title.setTextSize(26);
        title.setGravity(Gravity.START);
        root.addView(title);

        serverEdit = new EditText(this);
        serverEdit.setHint("Server /command URL");
        serverEdit.setSingleLine(true);
        serverEdit.setText("http://192.168.0.3:8001/command");
        root.addView(serverEdit);

        ttsEdit = new EditText(this);
        ttsEdit.setHint("TTS or manual command text");
        ttsEdit.setText("Android TTS test.");
        root.addView(ttsEdit);

        recordButton = addButton(root, "Start WAV recording", v -> toggleRecording());
        addButton(root, "Play last WAV", v -> playLastWav());
        addButton(root, "Speak text with Android TTS", v -> speak(ttsEdit.getText().toString()));
        addButton(root, "Send WAV to server STT", v -> enqueueSttAndServer(false));
        addButton(root, "Send text to /command", v -> enqueueServerOnly());

        autoBox = new CheckBox(this);
        autoBox.setText("After recording: upload WAV -> server STT -> TTS");
        autoBox.setChecked(true);
        root.addView(autoBox);

        logView = new TextView(this);
        logView.setTextSize(14);
        logView.setPadding(0, 24, 0, 0);
        root.addView(logView);
        return scroll;
    }

    private Button addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        root.addView(button);
        return button;
    }

    private void toggleRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        if (recorder.isRecording()) {
            recorder.stop();
            recordButton.setText("Start WAV recording");
            log("Saved WAV: " + lastWav.getAbsolutePath());
            if (autoBox.isChecked()) {
                enqueueSttAndServer(true);
            }
            return;
        }

        try {
            File dir = new File(getExternalFilesDir(null), "recordings");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("recordings directory create failed");
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            lastWav = new File(dir, "voice_" + stamp + ".wav");
            recorder.start(lastWav);
            recordButton.setText("Stop and save recording");
            log("Recording started");
        } catch (Exception e) {
            log("Recording failed: " + e.getMessage());
        }
    }

    private void playLastWav() {
        if (lastWav == null || !lastWav.isFile()) {
            log("No WAV to play.");
            return;
        }
        try {
            if (player != null) {
                player.release();
            }
            player = new MediaPlayer();
            player.setDataSource(lastWav.getAbsolutePath());
            player.prepare();
            player.start();
            log("Playback started");
        } catch (Exception e) {
            log("Playback failed: " + e.getMessage());
        }
    }

    private void enqueueSttAndServer(boolean fromAuto) {
        if (lastWav == null || !lastWav.isFile()) {
            log("No WAV for STT.");
            return;
        }
        queue.execute(() -> {
            String response = postWavToServer(lastWav);
            if (!response.isEmpty()) {
                speak(response);
            } else if (!fromAuto) {
                log("Server STT produced no response.");
            }
        });
        log("Queued WAV upload job");
    }

    private void enqueueServerOnly() {
        String text = ttsEdit.getText().toString();
        queue.execute(() -> {
            String response = postCommand(text);
            if (!response.isEmpty()) {
                speak(response);
            }
        });
        log("Queued server job");
    }

    private String postCommand(String command) {
        try {
            URL url = new URL(serverEdit.getText().toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            JSONObject body = new JSONObject();
            body.put("command", command);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
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
            String text = parseServerText(response.toString());
            log("Server response (" + code + "): " + text);
            return text;
        } catch (Exception e) {
            log("Server call failed: " + e.getMessage());
            return "";
        }
    }

    private String postWavToServer(File wav) {
        String boundary = "HomeVoiceBoundary" + System.currentTimeMillis();
        try {
            URL url = new URL(sttUrl());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(300000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setDoOutput(true);
            log("Uploading WAV to " + url);
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
            String text = parseServerText(response.toString());
            log("Server STT response (" + code + "): " + text);
            return text;
        } catch (Exception e) {
            log("WAV upload failed: " + e.getMessage());
            return "";
        }
    }

    private String sttUrl() {
        String commandUrl = serverEdit.getText().toString();
        if (commandUrl.endsWith("/command")) {
            return commandUrl.substring(0, commandUrl.length() - "/command".length()) + "/stt-command";
        }
        if (commandUrl.endsWith("/")) {
            return commandUrl + "stt-command";
        }
        return commandUrl + "/stt-command";
    }

    private void writeAscii(OutputStream out, String value) throws java.io.IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private String parseServerText(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            String[] keys = {"response", "text", "message", "result"};
            for (String key : keys) {
                String value = json.optString(key, "");
                if (!value.isEmpty()) {
                    return value;
                }
            }
        } catch (Exception ignored) {
        }
        return raw;
    }

    private void speak(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        runOnUiThread(() -> {
            if (tts != null) {
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, "home-voice-" + System.currentTimeMillis());
            }
        });
    }

    private void log(String message) {
        runOnUiThread(() -> {
            String current = logView == null ? "" : logView.getText().toString();
            String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + message;
            if (logView != null) {
                logView.setText(line + "\n" + current);
            }
        });
    }

    @Override
    protected void onDestroy() {
        recorder.stop();
        queue.shutdownNow();
        if (player != null) {
            player.release();
        }
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }
}
