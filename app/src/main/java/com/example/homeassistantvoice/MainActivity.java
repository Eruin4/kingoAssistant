package com.example.homeassistantvoice;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_RECORD_AUDIO = 1001;
    static final String PREFS_NAME = "home_voice_prefs";
    static final String PREF_SERVER_URL = "server_url";
    static final String PREF_API_KEY = "api_key";
    static final String PREF_ALARM_MINUTES = "alarm_minutes";
    static final String PREF_TTS_ENABLED = "tts_enabled";
    static final String CHANNEL_ID = "mic_service_channel";
    static final String SCHEDULE_CHANNEL_ID = "schedule_alarm_channel";
    private static final String DEFAULT_SERVER_URL = "https://eruin.mooo.com/voice";
    private static final int COLOR_BG = Color.rgb(15, 18, 22);
    private static final int COLOR_ROW = Color.rgb(33, 41, 49);
    private static final int COLOR_TEXT = Color.rgb(238, 243, 247);
    private static final int COLOR_MUTED = Color.rgb(153, 166, 178);
    private static final int COLOR_ACCENT = Color.rgb(55, 192, 150);
    private static final int COLOR_WARN = Color.rgb(239, 83, 80);

    private final WavRecorder recorder = new WavRecorder();
    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private MediaPlayer player;
    private File lastWav;
    private Button micButton;
    private Button chatTab;
    private Button calendarTab;
    private Button taskTab;
    private Button settingsTab;
    private EditText serverEdit;
    private EditText apiKeyEdit;
    private EditText alarmMinutesEdit;
    private CheckBox ttsOffCheck;
    private EditText manualEdit;
    private LinearLayout proposalBand;
    private TextView proposalSummary;
    private LinearLayout chatPane;
    private LinearLayout calendarPane;
    private LinearLayout taskPane;
    private LinearLayout settingsPane;
    private LinearLayout chatList;
    private RecyclerView calendarRecycler;
    private RecyclerView taskRecycler;
    private RecyclerView proposalRecycler;
    private EventAdapter eventAdapter;
    private TaskAdapter taskAdapter;
    private ProposalAdapter proposalAdapter;
    private TextView logView;
    private JSONObject currentSchedule;
    private int selectedPane = 0;
    private final BroadcastReceiver scheduleChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshSchedule();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_RECORD_AUDIO + 1);
        }
        createNotificationChannel();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN);
            }
        });
        setContentView(buildUi());
        registerReceiverCompat();
        refreshSchedule();
    }

    private void registerReceiverCompat() {
        IntentFilter filter = new IntentFilter(MicService.ACTION_SCHEDULE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scheduleChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scheduleChangedReceiver, filter);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel micChannel = new NotificationChannel(
                    CHANNEL_ID, "Microphone recording", NotificationManager.IMPORTANCE_LOW);
            micChannel.setDescription("Shows recording state while voice capture is active.");
            NotificationChannel scheduleChannel = new NotificationChannel(
                    SCHEDULE_CHANNEL_ID, "Schedule reminders", NotificationManager.IMPORTANCE_DEFAULT);
            scheduleChannel.setDescription("Upcoming schedule notifications.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(micChannel);
            manager.createNotificationChannel(scheduleChannel);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 20);
        root.setBackgroundColor(COLOR_BG);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = label("Schedule Voice", 26, COLOR_TEXT, true);
        top.addView(title);

        proposalBand = new LinearLayout(this);
        proposalBand.setOrientation(LinearLayout.VERTICAL);
        proposalBand.setPadding(18, 14, 18, 14);
        proposalBand.setBackground(box(Color.rgb(45, 51, 41), 10));
        proposalBand.setVisibility(View.GONE);
        proposalBand.setOnClickListener(v -> showProposalDialog());
        proposalSummary = label("No proposals", 15, COLOR_TEXT, true);
        proposalBand.addView(proposalSummary);
        top.addView(proposalBand, margins(-1, -2, 0, 14, 0, 0));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        chatTab = tabButton("Chat", 0);
        calendarTab = tabButton("Calendar", 1);
        taskTab = tabButton("Tasks", 2);
        settingsTab = tabButton("Settings", 3);
        tabs.addView(chatTab, weightParams());
        tabs.addView(calendarTab, weightParams());
        tabs.addView(taskTab, weightParams());
        tabs.addView(settingsTab, weightParams());
        top.addView(tabs);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        chatPane = pane();
        calendarPane = pane();
        taskPane = pane();
        settingsPane = pane();
        content.addView(chatPane);
        content.addView(calendarPane);
        content.addView(taskPane);
        content.addView(settingsPane);
        buildChatPane();
        buildCalendarPane();
        buildTaskPane();
        buildSettingsPane();

        logView = label("", 12, COLOR_MUTED, false);
        logView.setMaxLines(4);
        root.addView(logView);
        selectPane(0);
        return root;
    }

    private void buildChatPane() {
        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        ScrollView historyScroll = new ScrollView(this);
        historyScroll.addView(chatList);
        chatPane.addView(historyScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        manualEdit = edit("Type a command", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        chatPane.addView(manualEdit);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        micButton = actionButton("Start mic", v -> toggleMic());
        actions.addView(micButton, weightParams());
        actions.addView(actionButton("Send", v -> sendManualCommand()), weightParams());
        actions.addView(actionButton("Refresh", v -> refreshSchedule()), weightParams());
        chatPane.addView(actions);
    }

    private void buildCalendarPane() {
        eventAdapter = new EventAdapter(this::deleteEvent);
        calendarRecycler = new RecyclerView(this);
        calendarRecycler.setLayoutManager(new LinearLayoutManager(this));
        calendarRecycler.setAdapter(eventAdapter);
        new ItemTouchHelper(eventSwipeCallback()).attachToRecyclerView(calendarRecycler);
        calendarPane.addView(calendarRecycler, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(actionButton("Add event", v -> showEventDialog()), weightParams());
        actions.addView(actionButton("Refresh", v -> refreshSchedule()), weightParams());
        calendarPane.addView(actions);
    }

    private void buildTaskPane() {
        taskAdapter = new TaskAdapter();
        taskRecycler = new RecyclerView(this);
        taskRecycler.setLayoutManager(new LinearLayoutManager(this));
        taskRecycler.setAdapter(taskAdapter);
        new ItemTouchHelper(taskSwipeCallback()).attachToRecyclerView(taskRecycler);
        taskPane.addView(taskRecycler, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(actionButton("Add task", v -> showTaskDialog()), weightParams());
        actions.addView(actionButton("Refresh", v -> refreshSchedule()), weightParams());
        taskPane.addView(actions);
    }

    private void buildSettingsPane() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        serverEdit = edit("Server URL", normalizeServerUrl(prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL)), InputType.TYPE_CLASS_TEXT);
        apiKeyEdit = edit("API Key", prefs.getString(PREF_API_KEY, ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        alarmMinutesEdit = edit("Reminder minutes", prefs.getString(PREF_ALARM_MINUTES, "15"), InputType.TYPE_CLASS_NUMBER);
        settingsPane.addView(serverEdit);
        settingsPane.addView(apiKeyEdit);
        settingsPane.addView(alarmMinutesEdit);

        ttsOffCheck = new CheckBox(this);
        ttsOffCheck.setText("Disable TTS");
        ttsOffCheck.setTextColor(COLOR_TEXT);
        ttsOffCheck.setTextSize(16);
        ttsOffCheck.setPadding(10, 12, 10, 12);
        ttsOffCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        ttsOffCheck.setBackground(box(COLOR_ROW, 8));
        ttsOffCheck.setChecked(!prefs.getBoolean(PREF_TTS_ENABLED, true));
        ttsOffCheck.setOnCheckedChangeListener((buttonView, isChecked) -> savePrefs());
        settingsPane.addView(ttsOffCheck, margins(-1, -2, 0, 0, 0, 10));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(actionButton("Save", v -> {
            savePrefs();
            log("Settings saved.");
        }), weightParams());
        actions.addView(actionButton("Refresh", v -> refreshSchedule()), weightParams());
        settingsPane.addView(actions);
    }

    private ItemTouchHelper.SimpleCallback taskSwipeCallback() {
        return new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            private final Paint backgroundPaint = new Paint();
            private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                backgroundPaint.setColor(COLOR_WARN);
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(dp(15));
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            }

            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                JSONObject removed = taskAdapter.getItem(position);
                String taskId = taskAdapter.getTaskId(position);
                taskAdapter.removeItem(position);
                queue.execute(() -> {
                    JSONObject response = deleteTaskRequest(taskId);
                    if (response == null || !response.optBoolean("applied", false)) {
                        runOnUiThread(() -> {
                            if (removed != null) {
                                taskAdapter.restoreItem(removed, position);
                            }
                        });
                    } else {
                        handleCommandResponse(response);
                    }
                });
            }

            @Override
            public void onChildDraw(Canvas canvas, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                                    float dX, float dY, int actionState, boolean isCurrentlyActive) {
                View itemView = viewHolder.itemView;
                if (dX < 0) {
                    canvas.drawRect(itemView.getRight() + dX, itemView.getTop(),
                            itemView.getRight(), itemView.getBottom(), backgroundPaint);
                    canvas.drawText("Delete", itemView.getRight() - dp(78),
                            itemView.getTop() + (itemView.getHeight() / 2f) + dp(5), textPaint);
                }
                super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        };
    }

    private ItemTouchHelper.SimpleCallback eventSwipeCallback() {
        return new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            private final Paint backgroundPaint = new Paint();
            private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                backgroundPaint.setColor(COLOR_WARN);
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(dp(15));
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            }

            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                if (position == RecyclerView.NO_POSITION) {
                    return;
                }
                JSONObject removed = eventAdapter.getItem(position);
                String eventId = eventAdapter.getEventId(position);
                if (eventId.isEmpty()) {
                    eventAdapter.notifyItemChanged(position);
                    return;
                }
                eventAdapter.removeItem(position);
                queue.execute(() -> {
                    JSONObject response = deleteEventRequest(eventId);
                    if (response == null || !response.optBoolean("applied", false)) {
                        runOnUiThread(() -> {
                            if (removed != null) {
                                eventAdapter.restoreItem(removed, position);
                            }
                        });
                    } else {
                        handleCommandResponse(response);
                    }
                });
            }

            @Override
            public void onChildDraw(Canvas canvas, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                                    float dX, float dY, int actionState, boolean isCurrentlyActive) {
                View itemView = viewHolder.itemView;
                if (dX < 0) {
                    canvas.drawRect(itemView.getRight() + dX, itemView.getTop(),
                            itemView.getRight(), itemView.getBottom(), backgroundPaint);
                    canvas.drawText("Delete", itemView.getRight() - dp(78),
                            itemView.getTop() + (itemView.getHeight() / 2f) + dp(5), textPaint);
                }
                super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        };
    }

    private LinearLayout pane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(0, 16, 0, 0);
        return pane;
    }

    private Button tabButton(String text, int index) {
        Button button = actionButton(text, v -> selectPane(index));
        button.setTextSize(14);
        return button;
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(8, 20, 18));
        button.setBackground(box(COLOR_ACCENT, 8));
        button.setOnClickListener(listener);
        return button;
    }

    private void selectPane(int index) {
        selectedPane = index;
        chatPane.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        calendarPane.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        taskPane.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        settingsPane.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        styleTab(chatTab, index == 0);
        styleTab(calendarTab, index == 1);
        styleTab(taskTab, index == 2);
        styleTab(settingsTab, index == 3);
    }

    private void styleTab(Button button, boolean selected) {
        button.setTextColor(selected ? Color.rgb(8, 20, 18) : COLOR_TEXT);
        button.setBackground(box(selected ? COLOR_ACCENT : COLOR_ROW, 8));
    }

    private EditText edit(String hint, String value, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(value);
        editText.setInputType(inputType);
        editText.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        editText.setTextColor(COLOR_TEXT);
        editText.setHintTextColor(COLOR_MUTED);
        editText.setBackground(box(Color.rgb(21, 27, 34), 8));
        editText.setPadding(18, 10, 18, 10);
        editText.setTextSize(15);
        LinearLayout.LayoutParams params = margins(-1, -2, 0, 10, 0, 0);
        editText.setLayoutParams(params);
        return editText;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(2, 1.05f);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private GradientDrawable box(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(4, 4, 4, 4);
        return params;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void savePrefs() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_SERVER_URL, normalizeServerUrl(serverEdit.getText().toString().trim()))
                .putString(PREF_API_KEY, apiKeyEdit.getText().toString().trim())
                .putString(PREF_ALARM_MINUTES, alarmMinutesEdit.getText().toString().trim())
                .putBoolean(PREF_TTS_ENABLED, ttsOffCheck == null || !ttsOffCheck.isChecked())
                .apply();
    }

    private String normalizeServerUrl(String value) {
        String url = value == null ? "" : value.trim();
        String legacyName = "bam" + "boo";
        String legacyDomain = "com" + "munity.mooo.com";
        String legacyTypoDomain = "com" + "unity.mooo.com";
        if (url.isEmpty()
                || url.contains(legacyName)
                || url.contains(legacyDomain)
                || url.contains(legacyTypoDomain)) {
            return DEFAULT_SERVER_URL;
        }
        return url;
    }

    private String getApiKey() {
        return apiKeyEdit.getText().toString().trim();
    }

    private void toggleMic() {
        savePrefs();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        if (recorder.isRecording()) {
            finishRecordingAndUpload();
        } else {
            beginRecording();
        }
    }

    private void beginRecording() {
        try {
            File dir = new File(getExternalFilesDir(null), "recordings");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("recordings directory create failed");
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            lastWav = new File(dir, "schedule_" + stamp + ".wav");
            recorder.start(lastWav);
            micButton.setText("Send mic");
            appendLocalChat("Mic", "Recording started.");
        } catch (Exception e) {
            log("Recording failed: " + e.getMessage());
        }
    }

    private void finishRecordingAndUpload() {
        recorder.stop();
        micButton.setText("Start mic");
        if (lastWav == null || !lastWav.isFile()) {
            log("No WAV to upload.");
            return;
        }
        appendLocalChat("Mic", "Uploading recorded command.");
        queue.execute(() -> handleCommandResponse(postWavToServer(lastWav)));
    }

    private void sendManualCommand() {
        savePrefs();
        String text = manualEdit.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        appendLocalChat("You", text);
        manualEdit.setText("");
        queue.execute(() -> handleCommandResponse(postCommand(text)));
    }

    private void refreshSchedule() {
        savePrefs();
        queue.execute(() -> {
            JSONObject response = getJson(endpointUrl("schedule"));
            JSONObject schedule = response == null ? null : response.optJSONObject("schedule");
            runOnUiThread(() -> renderSchedule(schedule));
        });
    }

    private void handleCommandResponse(JSONObject response) {
        if (response == null) {
            log("Server returned no response.");
            return;
        }
        String text = response.optString("response", "");
        JSONObject command = response.optJSONObject("structured_command");
        String rendered = command == null ? text : ChatMessageRenderer.render(command);
        if (rendered == null || rendered.trim().isEmpty()) {
            rendered = text;
        }
        String finalRendered = rendered;
        runOnUiThread(() -> {
            appendLocalChat("Assistant", finalRendered);
            renderSchedule(response.optJSONObject("schedule"));
        });
        speak(text);
    }

    private JSONObject postCommand(String command) {
        JSONObject body = new JSONObject();
        try {
            body.put("command", command);
        } catch (Exception ignored) {
        }
        return requestJson("POST", endpointUrl("command"), body);
    }

    private JSONObject postWavToServer(File wav) {
        String boundary = "HomeVoiceBoundary" + System.currentTimeMillis();
        String urlText = endpointUrl("stt-command");
        try {
            return postWavToServerUrl(wav, urlText, boundary);
        } catch (Exception e) {
            log("WAV upload failed: " + e.getMessage());
            return null;
        }
    }

    private JSONObject postWavToServerUrl(File wav, String urlText, String boundary) throws Exception {
        URL url = new URL(urlText);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(300000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        addAuthHeader(conn);
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
        return new JSONObject(readResponse(conn, code));
    }

    private JSONObject requestJson(String method, String urlText, JSONObject body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(300000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            addAuthHeader(conn);
            if (body != null) {
                conn.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            return new JSONObject(readResponse(conn, code));
        } catch (Exception e) {
            log(method + " failed: " + e.getMessage());
            return null;
        }
    }

    private JSONObject getJson(String urlText) {
        return requestJson("GET", urlText, null);
    }

    private void addAuthHeader(HttpURLConnection conn) {
        String key = getApiKey();
        if (!key.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }
    }

    private String endpointUrl(String endpoint) {
        String base = normalizeServerUrl(serverEdit.getText().toString().trim());
        String[] suffixes = {"/command", "/stt-command", "/schedule", "/proposal/accept", "/proposal/reject", "/proposal/accept-all"};
        for (String suffix : suffixes) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
            }
        }
        if (!base.endsWith("/")) {
            base += "/";
        }
        return base + endpoint;
    }

    private void renderSchedule(JSONObject schedule) {
        currentSchedule = schedule;
        renderChat(schedule == null ? null : schedule.optJSONArray("chat_history"));
        renderProposals(schedule == null ? null : schedule.optJSONArray("proposals"));
        renderCalendar(schedule);
        renderTasks(schedule == null ? null : schedule.optJSONArray("tasks"));
        if (schedule != null) {
            scheduleNextAlarm(schedule);
        }
    }

    private void renderChat(JSONArray history) {
        chatList.removeAllViews();
        if (history == null || history.length() == 0) {
            chatList.addView(emptyRow("No conversation yet."));
            return;
        }
        int start = Math.max(0, history.length() - 25);
        for (int i = start; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String role = item.optString("role", "message");
            String text = item.optString("text", "");
            JSONObject payload = item.optJSONObject("payload");
            if (payload != null) {
                text = ChatMessageRenderer.render(payload);
            }
            chatList.addView(chatRow(role, text));
        }
    }

    private void appendLocalChat(String role, String text) {
        if (chatList == null) {
            return;
        }
        chatList.addView(chatRow(role, text), 0);
    }

    private View chatRow(String role, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(16, 12, 16, 12);
        row.setBackground(box(COLOR_ROW, 8));
        TextView roleView = label(role, 12, COLOR_ACCENT, true);
        TextView textView = label(text == null || text.isEmpty() ? "(empty)" : text, 15, COLOR_TEXT, false);
        row.addView(roleView);
        row.addView(textView);
        row.setLayoutParams(margins(-1, -2, 0, 0, 0, 10));
        return row;
    }

    private View emptyRow(String text) {
        TextView view = label(text, 15, COLOR_MUTED, false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(16, 28, 16, 28);
        return view;
    }

    private void renderProposals(JSONArray proposals) {
        int count = proposals == null ? 0 : proposals.length();
        proposalBand.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        proposalSummary.setText(count == 0 ? "No proposals" : "Pending proposals: " + count + "  Tap to review");
    }

    private void renderCalendar(JSONObject schedule) {
        if (schedule == null) {
            eventAdapter.setEvents(new ArrayList<>());
            return;
        }
        JSONObject calendars = schedule.optJSONObject("calendars");
        List<JSONObject> events = new ArrayList<>();
        collectEvents(events, calendars == null ? null : calendars.optJSONArray("calendar_1"));
        collectEvents(events, calendars == null ? null : calendars.optJSONArray("calendar_2"));
        events.sort((a, b) -> eventSortText(a).compareTo(eventSortText(b)));
        eventAdapter.setEvents(events);
    }

    private void collectEvents(List<JSONObject> out, JSONArray events) {
        if (events == null) {
            return;
        }
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event != null) {
                out.add(event);
            }
        }
    }

    private String eventSortText(JSONObject event) {
        return event.optString("date", "9999-12-31") + " " + event.optString("start_time", "99:99");
    }

    private void renderTasks(JSONArray tasks) {
        List<JSONObject> nextTasks = new ArrayList<>();
        if (tasks == null || tasks.length() == 0) {
            taskAdapter.setTasks(nextTasks);
            return;
        }
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task != null) {
                nextTasks.add(task);
            }
        }
        taskAdapter.setTasks(nextTasks);
    }

    private void showProposalDialog() {
        JSONArray proposals = currentSchedule == null ? null : currentSchedule.optJSONArray("proposals");
        if (proposals == null || proposals.length() == 0) {
            return;
        }
        List<JSONObject> nextProposals = new ArrayList<>();
        for (int i = 0; i < proposals.length(); i++) {
            JSONObject proposal = proposals.optJSONObject(i);
            if (proposal == null) {
                continue;
            }
            nextProposals.add(proposal);
        }
        proposalAdapter = new ProposalAdapter(new ProposalAdapter.Listener() {
            @Override
            public void onAcceptProposal(String proposalId) {
                acceptProposal(proposalId);
            }

            @Override
            public void onRejectProposal(String proposalId) {
                rejectProposal(proposalId);
            }
        });
        proposalAdapter.setProposals(nextProposals);
        proposalRecycler = new RecyclerView(this);
        proposalRecycler.setLayoutManager(new LinearLayoutManager(this));
        proposalRecycler.setAdapter(proposalAdapter);
        proposalRecycler.setMinimumHeight(dp(220));
        new AlertDialog.Builder(this)
                .setTitle("Pending proposals")
                .setView(proposalRecycler)
                .setPositiveButton("Accept all", (dialog, which) -> acceptAllProposals())
                .setNegativeButton("Close", null)
                .show();
    }

    private void showEventDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText title = edit("Title", "", InputType.TYPE_CLASS_TEXT);
        EditText date = edit("YYYY-MM-DD", "", InputType.TYPE_CLASS_TEXT);
        EditText start = edit("HH:MM", "", InputType.TYPE_CLASS_TEXT);
        EditText memo = edit("Memo", "", InputType.TYPE_CLASS_TEXT);
        form.addView(title);
        form.addView(date);
        form.addView(start);
        form.addView(memo);
        new AlertDialog.Builder(this)
                .setTitle("Add event")
                .setView(form)
                .setPositiveButton("Add", (dialog, which) -> {
                    JSONObject body = new JSONObject();
                    try {
                        body.put("calendar", "calendar_1");
                        body.put("title", title.getText().toString());
                        body.put("date", date.getText().toString());
                        body.put("start_time", start.getText().toString());
                        body.put("memo", memo.getText().toString());
                    } catch (Exception ignored) {
                    }
                    queue.execute(() -> handleCommandResponse(requestJson("POST", endpointUrl("event"), body)));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTaskDialog() {
        final EditText title = edit("Task title", "", InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("Add task")
                .setView(title)
                .setPositiveButton("Add", (dialog, which) -> {
                    JSONObject body = new JSONObject();
                    try {
                        body.put("title", title.getText().toString());
                    } catch (Exception ignored) {
                    }
                    queue.execute(() -> handleCommandResponse(requestJson("POST", endpointUrl("task"), body)));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void acceptProposal(String proposalId) {
        JSONObject body = new JSONObject();
        try {
            body.put("proposal_id", proposalId);
        } catch (Exception ignored) {
        }
        queue.execute(() -> handleCommandResponse(requestJson("POST", endpointUrl("proposal/accept"), body)));
    }

    private void rejectProposal(String proposalId) {
        JSONObject body = new JSONObject();
        try {
            body.put("proposal_id", proposalId);
        } catch (Exception ignored) {
        }
        queue.execute(() -> handleCommandResponse(requestJson("POST", endpointUrl("proposal/reject"), body)));
    }

    private void acceptAllProposals() {
        queue.execute(() -> handleCommandResponse(requestJson("POST", endpointUrl("proposal/accept-all"), new JSONObject())));
    }

    private void deleteTask(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return;
        }
        queue.execute(() -> handleCommandResponse(deleteTaskRequest(taskId)));
    }

    private JSONObject deleteTaskRequest(String taskId) {
        return requestJson("DELETE", endpointUrl("task/" + encodePath(taskId)), null);
    }

    private void deleteEvent(String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            return;
        }
        queue.execute(() -> handleCommandResponse(deleteEventRequest(eventId)));
    }

    private JSONObject deleteEventRequest(String eventId) {
        return requestJson("DELETE", endpointUrl("event/" + encodePath(eventId)), null);
    }

    private String encodePath(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private void scheduleNextAlarm(JSONObject schedule) {
        JSONObject calendars = schedule.optJSONObject("calendars");
        if (calendars == null) {
            cancelScheduleAlarm();
            return;
        }
        JSONObject nextEvent = null;
        long nextMillis = Long.MAX_VALUE;
        JSONArray[] groups = new JSONArray[]{
                calendars.optJSONArray("calendar_1"),
                calendars.optJSONArray("calendar_2")
        };
        for (JSONArray events : groups) {
            if (events == null) {
                continue;
            }
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                long eventMillis = event == null ? -1 : eventStartMillis(event);
                if (eventMillis > System.currentTimeMillis() && eventMillis < nextMillis) {
                    nextMillis = eventMillis;
                    nextEvent = event;
                }
            }
        }
        if (nextEvent == null) {
            cancelScheduleAlarm();
            return;
        }
        int minutes = reminderMinutes();
        long triggerAt = nextMillis - minutes * 60_000L;
        if (triggerAt <= System.currentTimeMillis()) {
            triggerAt = System.currentTimeMillis() + 5_000L;
        }

        Intent intent = new Intent(this, ScheduleAlarmReceiver.class);
        intent.putExtra(ScheduleAlarmReceiver.EXTRA_TITLE, nextEvent.optString("title", "Schedule reminder"));
        intent.putExtra(ScheduleAlarmReceiver.EXTRA_TIME,
                nextEvent.optString("date", "") + " " + nextEvent.optString("start_time", ""));
        intent.putExtra(ScheduleAlarmReceiver.EXTRA_MEMO, nextEvent.optString("memo", ""));
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, 3001, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }

    private void cancelScheduleAlarm() {
        Intent intent = new Intent(this, ScheduleAlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, 3001, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(pendingIntent);
    }

    private long eventStartMillis(JSONObject event) {
        String date = event.optString("date", "");
        String start = event.optString("start_time", "");
        if (date.isEmpty() || start.isEmpty()) {
            return -1;
        }
        try {
            Date parsed = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(date + " " + start);
            return parsed == null ? -1 : parsed.getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    private int reminderMinutes() {
        try {
            return Math.max(0, Math.min(1440, Integer.parseInt(alarmMinutesEdit.getText().toString().trim())));
        } catch (NumberFormatException e) {
            return 15;
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
        } catch (Exception e) {
            log("Playback failed: " + e.getMessage());
        }
    }

    private String readResponse(HttpURLConnection conn, int code) throws java.io.IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        return response.toString();
    }

    private void writeAscii(OutputStream out, String value) throws java.io.IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private void speak(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (ttsOffCheck != null && ttsOffCheck.isChecked()) {
            return;
        }
        runOnUiThread(() -> {
            if (tts != null) {
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, "schedule-voice-" + System.currentTimeMillis());
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
        mainHandler.removeCallbacksAndMessages(null);
        recorder.stop();
        queue.shutdownNow();
        if (player != null) {
            player.release();
        }
        if (tts != null) {
            tts.shutdown();
        }
        try {
            unregisterReceiver(scheduleChangedReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }
}
