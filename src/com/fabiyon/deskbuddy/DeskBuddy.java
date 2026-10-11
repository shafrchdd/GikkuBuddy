package com.fabiyon.deskbuddy;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.BlurMaskFilter;
import com.google.appinventor.components.runtime.util.YailList;
import android.graphics.BitmapFactory;
import android.hardware.*;
import android.media.*;
import android.os.*;
import android.util.Base64;
import android.util.LruCache;
import android.view.*;
import android.widget.*;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import com.google.appinventor.components.runtime.EventDispatcher;

import org.json.*;

import java.io.*;
import java.io.File;
import java.net.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** GikkuBuddy: lightweight native-only Kodular extension; remote assets, Gemini Live, sensors. */
@DesignerComponent(version = 9, description = "GikkuBuddy v4: procedural glowing-eye emotions drawn in Java; no frame downloads, Gemini Live audio and sensors", category = ComponentCategory.EXTENSION, nonVisible = true, iconName = "")
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.INTERNET,android.permission.RECORD_AUDIO,android.permission.MODIFY_AUDIO_SETTINGS,android.permission.VIBRATE")
public class DeskBuddy extends AndroidNonvisibleComponent implements SensorEventListener, OnDestroyListener {
    private static final String MANIFEST_URL = "https://raw.githubusercontent.com/shafrchdd/GikkuBuddy/main/manifest.json";
    private static final int MAX_ZIP_BYTES = 60 * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 120L * 1024 * 1024;
    private static final String[] EMOTIONS = {"Idle", "angry", "bored", "charged", "charging", "chilling", "curious", "curious_alt", "dizzy", "excited", "focus", "guilt", "lonely", "love", "low_battery", "proud", "sad", "scared", "shy", "sleeping", "thinking", "tired", "updating"};
    private static final Set<String> EMOTION_SET = new HashSet<String>(Arrays.asList(EMOTIONS));
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final ExecutorService audioQueue = Executors.newSingleThreadExecutor();
    private final SensorManager sensorsManager;
    private final Map<String, File[]> sequences = new HashMap<String, File[]>();
    private final LruCache<String, Bitmap> bitmaps;
    private FrameLayout stage;
    private ImageView image;
    private FaceView face;
    private volatile boolean disposed = false, downloading = false, connected = false, listening = false;
    private String emotion = "Idle", version = "", prompt = "You are GikkuBuddy, a warm, curious, playful desktop AI friend. React naturally. Call set_emotion to change face when a meaningful emotion changes, not on every sentence.", voice = "Puck", model = "gemini-3.8-live";
    private int fps = 15, frameNumber = 0, activePriority = 0;
    private long emotionExpires = 0, lastShake = 0, lastLightEvent = 0, lastProximityEvent = 0;
    private boolean loop = true, sensorEnabled = false;
    private volatile int batteryPercent = -1;
    private volatile boolean batteryCharging = false;
    private volatile boolean batteryKnown = false;
    private boolean batteryReceiverRegistered = false;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) updateBattery(intent);
        }
    };
    private float lastAx, lastAy, lastAz;
    private Thread micThread;
    private AudioRecord mic;
    private AudioTrack speaker;
    private final Object speakerLock = new Object();
    private volatile int audioEpoch = 0;
    private NativeWebSocket socket;
    private volatile int connectionId = 0;
    private boolean tickerRunning = false;
    private String lastApiKey = "";
    private int retryAttempt = 0;
    private boolean userDisconnected = false;
    private Runnable connectionTimeout;
    private Runnable retryTask;
    private String connectionStage = "idle";
    private static final int MAX_RETRIES = 3;
    private void status(final String stage, final String detail) {
        connectionStage = stage;
        main.post(new Runnable() { @Override public void run() {
            if (!disposed) ConnectionStatus(stage, detail);
        } });
    }
    private void cancelConnectionTimers() {
        if (connectionTimeout != null) main.removeCallbacks(connectionTimeout);
        if (retryTask != null) main.removeCallbacks(retryTask);
        connectionTimeout = null; retryTask = null;
    }
    private boolean retryable(String reason) {
        String x = reason == null ? "" : reason.toLowerCase(Locale.US);
        return !(x.contains("401") || x.contains("403") || x.contains("400")
          || x.contains("1002") || x.contains("1003") || x.contains("1008")
          || x.contains("invalid") || x.contains("unauthorized")
          || x.contains("permission") || x.contains("api key")
          || x.contains("quota") || x.contains("429") || x.contains("1009"));
    }
    private void scheduleRetry(final String why) {
        if (userDisconnected || disposed || !retryable(why) || retryAttempt >= MAX_RETRIES) {
            status("failed", why);
            error("Gemini connection failed [" + connectionStage + "]: " + why);
            return;
        }
        retryAttempt++;
        final int attempt = retryAttempt;
        final long delay = Math.min(8000, 1000L << (attempt - 1));
        status("retrying", "Attempt " + attempt + "/" + MAX_RETRIES + " in " + delay + " ms: " + why);
        retryTask = new Runnable() { @Override public void run() {
            if (!disposed && !userDisconnected && attempt == retryAttempt) connectAttempt();
        } };
        main.postDelayed(retryTask, delay);
    }

    public DeskBuddy(ComponentContainer container) {
        super(container.$form());
        activity = container.$context();
        container.$form().registerForOnDestroy(this);
        sensorsManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        int kb = (int) Math.min(24L * 1024, Math.max(4L * 1024, Runtime.getRuntime().maxMemory() / 1024 / 12));
        bitmaps = new LruCache<String, Bitmap>(kb) {
            @Override protected int sizeOf(String key, Bitmap bitmap) { return Math.max(1, bitmap.getByteCount() / 1024); }
        };
        // ACTION_BATTERY_CHANGED is sticky: registration immediately supplies current status,
        // then pushes changes when the cable is plugged/unplugged or charge level changes.
        try {
            activity.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            batteryReceiverRegistered = true;
        } catch (Exception ex) { error("Battery monitor: " + ex.getMessage()); }
    }

    private final Runnable animationTicker = new Runnable() {
        @Override public void run() {
            if (disposed) return;
            long now = SystemClock.uptimeMillis();
            if (emotionExpires > 0 && now >= emotionExpires) {
                emotionExpires = 0;
                activePriority = 0;
                changeEmotion("Idle", true, 0, 0);
            }
            if (face != null) face.invalidate();
            main.postDelayed(this, Math.max(16, 1000 / Math.max(1, fps)));
        }
    };

    @SimpleFunction(description = "Attach the procedurally drawn neon face to a Kodular arrangement.")
    public void AttachTo(final AndroidViewComponent arrangement) {
        main.post(new Runnable() { @Override public void run() {
            if (disposed) return;
            View target = arrangement.getView();
            if (!(target instanceof ViewGroup)) { error("AttachTo needs an Arrangement component"); return; }
            if (stage != null && stage.getParent() instanceof ViewGroup) ((ViewGroup) stage.getParent()).removeView(stage);
            FrameLayout host = new FrameLayout(activity);
            FaceView drawn = new FaceView(activity);
            host.addView(drawn, new FrameLayout.LayoutParams(-1, -1));
            ((ViewGroup) target).addView(host, new ViewGroup.LayoutParams(-1, -1));
            stage = host; face = drawn;
            stage.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        BuddyTouched(event.getX() / Math.max(1, v.getWidth()), event.getY() / Math.max(1, v.getHeight()));
                        return true;
                    }
                    return true;
                }
            });
            if (!tickerRunning) { tickerRunning = true; main.post(animationTicker); }
        }});
    }

    @SimpleFunction(description = "Compatibility: v4 animations are built in and require no ZIP. Fires AssetsReady immediately.")
    public void InitializeAssets(String ignoredUrl) {
        main.post(new Runnable() { @Override public void run() { AssetsReady("builtin://procedural", "4"); } });
    }

    private static HttpURLConnection connection(String src) throws IOException {
        URL url = new URL(src);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("HTTPS required");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "GikkuBuddy/1.0");
        return c;
    }
    private static String fetchString(String url, int limit) throws IOException {
        HttpURLConnection c = connection(url);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) != -1) { if (out.size() + n > limit) throw new IOException("Manifest too large"); out.write(buf, 0, n); }
            }
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
    private void downloadZip(String url, File file, String checksum) throws Exception {
        HttpURLConnection c = connection(url);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try {
            if (c.getResponseCode() != 200) throw new IOException("ZIP HTTP " + c.getResponseCode());
            long total = c.getContentLengthLong();
            if (total > MAX_ZIP_BYTES) throw new IOException("ZIP too large");
            long size = 0, lastReport = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[32768]; int n;
                while ((n = in.read(buf)) != -1) {
                    size += n;
                    if (size > MAX_ZIP_BYTES) throw new IOException("Download exceeds limit");
                    out.write(buf, 0, n); md.update(buf, 0, n);
                    if (size - lastReport > 160000) { lastReport = size; postProgress(size, total); }
                }
            }
            postProgress(size, total);
        } finally { c.disconnect(); }
        StringBuilder actual = new StringBuilder();
        for (byte b : md.digest()) actual.append(String.format(Locale.US, "%02x", b & 255));
        if (!actual.toString().equalsIgnoreCase(checksum)) throw new IOException("ZIP SHA-256 mismatch");
    }
    private void postProgress(final long bytes, final long total) {
        final int p = total > 0 ? (int) Math.min(100, bytes * 100L / total) : 0;
        main.post(new Runnable() { @Override public void run() { AssetsProgress(p, bytes, total); } });
    }
    private static void unpack(File zip, File folder) throws IOException {
        int entries = 0; long written = 0;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry; byte[] buf = new byte[32768];
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.matches("[A-Za-z_]+/[0-9]{2}\\.jpg")) throw new IOException("Unexpected ZIP entry " + name);
                entries++;
                if (entries > 345) throw new IOException("Too many frames");
                File dest = new File(folder, name);
                if (!dest.getCanonicalPath().startsWith(folder.getCanonicalPath() + File.separator)) throw new IOException("ZIP path traversal");
                if (!dest.getParentFile().isDirectory() && !dest.getParentFile().mkdirs()) throw new IOException("Directory creation failed");
                if (dest.exists()) throw new IOException("Duplicate ZIP entry");
                try (OutputStream out = new FileOutputStream(dest)) {
                    int n;
                    while ((n = zin.read(buf)) != -1) {
                        written += n;
                        if (written > MAX_EXTRACTED_BYTES) throw new IOException("Uncompressed pack exceeds limit");
                        out.write(buf, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
        if (entries != 345) throw new IOException("Incorrect frame count " + entries);
    }
    private static boolean validatePack(File folder) {
        File[] dirs = folder.listFiles();
        if (dirs == null || dirs.length != EMOTIONS.length) return false;
        for (String name : EMOTIONS) {
            File dir = new File(folder, name);
            if (!dir.isDirectory()) return false;
            File[] contents = dir.listFiles();
            if (contents == null || contents.length != 15) return false;
            for (int i = 1; i <= 15; i++) if (!new File(dir, String.format(Locale.US, "%02d.jpg", i)).isFile()) return false;
        }
        return true;
    }
    private boolean loadInstalled(File folder) {
        if (!validatePack(folder)) return false;
        Map<String, File[]> loaded = new HashMap<String, File[]>();
        for (String name : EMOTIONS) {
            File[] frames = new File[15];
            for (int j = 1; j <= 15; j++) frames[j - 1] = new File(new File(folder, name), String.format(Locale.US, "%02d.jpg", j));
            loaded.put(name, frames);
        }
        synchronized (sequences) { sequences.clear(); sequences.putAll(loaded); }
        main.post(new Runnable() { @Override public void run() {
            FramesLoaded(23, 345);
            changeEmotion("Idle", true, 0, 0);
        } });
        return true;
    }
    private void notifyAssetsReady(final File folder, final String rev) {
        version = rev;
        main.post(new Runnable() { @Override public void run() { AssetsReady(folder.getAbsolutePath(), rev); } });
    }
    private static String readVersion(File root) {
        File marker = new File(root, "version.txt");
        if (!marker.isFile()) return "";
        try { byte[] b = new byte[100]; int n; try (InputStream in = new FileInputStream(marker)) { n = in.read(b); } return n > 0 ? new String(b, 0, n, "UTF-8").trim() : ""; }
        catch (Exception e) { return ""; }
    }
    private static void writeString(File file, String value) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) { out.write(value.getBytes("UTF-8")); }
    }
    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] children = f.listFiles(); if (children != null) for (File child : children) deleteTree(child); }
        f.delete();
    }
    @SimpleFunction public void CheckForUpdates() { InitializeAssets(""); }
    @SimpleFunction public void ClearEmotionCache() { AssetsCleared(); }
    @SimpleFunction(description = "Return native Kodular list of built-in emotions.")
    public YailList AvailableEmotions() { return YailList.makeList(Arrays.asList(EMOTIONS)); }
    @SimpleFunction public void SetFPS(int requested) { fps = Math.max(1, Math.min(60, requested)); }
    @SimpleFunction public void SetEmotion(final String name, final boolean repeat, final int durationMs) {
        main.post(new Runnable() { @Override public void run() { changeEmotion(name, repeat, durationMs, 10); } });
    }
    private void changeEmotion(String name, boolean repeat, int duration, int newPriority) {
        if (!EMOTION_SET.contains(name)) { error("Unknown emotion: " + name); return; }
        long now = SystemClock.uptimeMillis();
        if (activePriority > newPriority && emotionExpires > now) return;
        emotion = name; frameNumber = 0; loop = repeat;
        activePriority = newPriority;
        emotionExpires = duration > 0 ? now + Math.min(duration, 120000) : 0;
        if (face != null) face.invalidate();
        EmotionChanged(name);
    }
    @SimpleFunction public String CurrentEmotion() { return emotion; }
    @SimpleFunction public boolean AssetsInstalled() { return true; }
    @SimpleFunction public void SetInstructions(String instructions) { if (instructions != null) prompt = instructions; }
    @SimpleFunction public void SetModel(String name) { if (name != null && name.matches("[A-Za-z0-9._-]+")) model = name; }
    @SimpleFunction public void SetVoice(String name) { if (name != null && name.matches("[A-Za-z0-9_-]+")) voice = name; }

    @SimpleFunction(description = "Connect to Gemini Live. Automatically retries transient failures up to three times.")
    public void Connect(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) { error("Gemini API key missing"); return; }
        userDisconnected = false;
        lastApiKey = apiKey.trim();
        retryAttempt = 0;
        cancelConnectionTimers();
        connectAttempt();
    }
    private void connectAttempt() {
        cancelConnectionTimers();
        ++connectionId;
        disconnectInternal();
        final int id = connectionId;
        status("connecting", "Opening secure WebSocket");
        connectionTimeout = new Runnable() { @Override public void run() {
            if (id != connectionId || connected || disposed || userDisconnected) return;
            status("timeout", "No setupComplete within 25 seconds");
            NativeWebSocket pending = socket;
            if (pending != null) pending.close();
        } };
        main.postDelayed(connectionTimeout, 25000);
        try {
            String endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key="
                + URLEncoder.encode(lastApiKey, "UTF-8");
            socket = new NativeWebSocket(endpoint, new NativeWebSocket.Listener() {
                @Override public void opened() {
                    if (id != connectionId) return;
                    status("configuring", "WebSocket connected; sending Gemini setup");
                    sendSetup();
                }
                @Override public void message(String text) { if (id == connectionId) onServerMessage(text); }
                @Override public void closed(final String reason) {
                    if (id != connectionId) return;
                    connected = false; listening = false;
                    main.post(new Runnable() { @Override public void run() {
                        if (id != connectionId || disposed) return;
                        cancelConnectionTimers();
                        String why = connectionStage.equals("timeout") ? "Connection/setup timeout (25s)" : reason;
                        Disconnected(why);
                        if (!userDisconnected) scheduleRetry(why);
                    } });
                }
            });
            socket.start();
        } catch (Exception e) {
            cancelConnectionTimers();
            scheduleRetry("Connection setup: " + e.getMessage());
        }
    }
    // Match the setup envelope used by the user's proven GeminiLive extension.
    // Keep advanced function calling opt-in so an unsupported tool schema cannot
    // prevent a basic audio session from establishing.
    private boolean enableEmotionTools = false;
    @SimpleFunction(description = "Enable Gemini emotion function calling on the next connection. Off by default for compatibility with the proven GeminiLive setup.")
    public void EnableEmotionTools(boolean enabled) { enableEmotionTools = enabled; }
    private void sendSetup() {
        try {
            JSONObject body = new JSONObject();
            body.put("model", "models/" + model);
            JSONObject generationConfig = new JSONObject();
            generationConfig.put("responseModalities", new JSONArray().put("AUDIO"));
            JSONObject prebuilt = new JSONObject().put("voiceName", voice);
            JSONObject speechConfig = new JSONObject().put("voiceConfig",
                new JSONObject().put("prebuiltVoiceConfig", prebuilt));
            generationConfig.put("speechConfig", speechConfig);
            body.put("generationConfig", generationConfig);
            if (prompt != null && !prompt.trim().isEmpty()) {
                body.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", prompt))));
            }
            if (enableEmotionTools) {
                JSONObject def = new JSONObject().put("name", "set_emotion")
                    .put("description", "Change GikkuBuddy's facial emotion.");
                JSONObject props = new JSONObject().put("emotion",
                    new JSONObject().put("type", "STRING").put("enum", new JSONArray(Arrays.asList(EMOTIONS))));
                props.put("duration_ms", new JSONObject().put("type", "INTEGER"));
                def.put("parameters", new JSONObject().put("type", "OBJECT")
                    .put("properties", props).put("required", new JSONArray().put("emotion")));
                body.put("tools", new JSONArray().put(new JSONObject()
                    .put("functionDeclarations", new JSONArray().put(def))));
            }
            body.put("inputAudioTranscription", new JSONObject());
            body.put("outputAudioTranscription", new JSONObject());
            NativeWebSocket ws = socket;
            if (ws == null || !ws.send(new JSONObject().put("setup", body).toString())) {
                String why = ws == null ? "Socket missing" : ws.sendError();
                status("send_failed", why);
                error("Gemini setup send failed: " + why);
                if (ws != null) ws.close();
            } else {
                status("awaiting_setup", "Setup frame sent using proven GeminiLive format");
            }
        } catch (Exception e) {
            status("setup_error", e.toString());
            error("Gemini setup: " + e.getMessage());
            if (socket != null) socket.close();
        }
    }
    private void onServerMessage(String text) {
        try {
            JSONObject reply = new JSONObject(text);
            if (reply.has("error")) {
                String detail = String.valueOf(reply.opt("error"));
                status("server_error", detail);
                error("Gemini API: " + detail);
                if (socket != null) socket.close();
                return;
            }
            if (reply.has("goAway")) status("server_notice", "Gemini session is ending: " + reply.opt("goAway"));
            if (reply.has("setupComplete")) {
                cancelConnectionTimers();
                retryAttempt = 0;
                status("connected", "Gemini setup completed");
                connected = true;
                openSpeaker();
                main.post(new Runnable() { @Override public void run() { Connected(); } });
            }
            if (reply.has("toolCall")) {
                JSONArray calls = reply.getJSONObject("toolCall").optJSONArray("functionCalls");
                JSONArray answers = new JSONArray();
                if (calls != null) for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.getJSONObject(i);
                    String fn = call.optString("name");
                    JSONObject args = call.optJSONObject("args");
                    if (args == null) args = new JSONObject();
                    final String selected = args.optString("emotion", "Idle");
                    final int duration = Math.min(10000, Math.max(500, args.optInt("duration_ms", 2000)));
                    final boolean valid = "set_emotion".equals(fn) && EMOTION_SET.contains(selected);
                    final String function = fn, argsJson = args.toString();
                    if (valid) main.post(new Runnable() { @Override public void run() { changeEmotion(selected, true, duration, 5); } });
                    main.post(new Runnable() { @Override public void run() { FunctionCalled(function, argsJson); } });
                    answers.put(new JSONObject().put("id", call.optString("id")).put("name", fn)
                            .put("response", new JSONObject().put("ok", valid)));
                }
                send(new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", answers)).toString());
            }
            JSONObject server = reply.optJSONObject("serverContent");
            if (server != null) {
                if (server.optBoolean("interrupted")) clearAudio();
                JSONObject turn = server.optJSONObject("modelTurn");
                if (turn != null) {
                    JSONArray parts = turn.optJSONArray("parts");
                    if (parts != null) for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.getJSONObject(i);
                        JSONObject blob = part.optJSONObject("inlineData");
                        if (blob != null && blob.optString("mimeType").startsWith("audio/pcm")) {
                            byte[] pcm = Base64.decode(blob.optString("data"), Base64.DEFAULT);
                            queueAudio(pcm);
                        }
                        if (part.has("text")) {
                            final String spoken = part.optString("text");
                            main.post(new Runnable() { @Override public void run() { Transcript(spoken); } });
                        }
                    }
                }
            }
        } catch (Exception e) { error("Gemini message: " + e.getMessage()); }
    }
    private void send(String json) { NativeWebSocket ws = socket; if (ws != null) ws.send(json); }
    private void openSpeaker() {
        synchronized (speakerLock) {
            if (speaker != null) return;
            try {
                int minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                speaker = new AudioTrack(AudioManager.STREAM_MUSIC, 24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(16384, minimum), AudioTrack.MODE_STREAM);
                speaker.play();
            } catch (Exception e) { error("Speaker: " + e.getMessage()); }
        }
    }
    private void queueAudio(final byte[] bytes) {
        final int generation = audioEpoch;
        audioQueue.execute(new Runnable() { @Override public void run() {
            if (generation != audioEpoch) return;
            synchronized (speakerLock) {
                if (speaker == null || generation != audioEpoch) return;
                int i = 0;
                while (i < bytes.length && generation == audioEpoch) {
                    int n = speaker.write(bytes, i, Math.min(4096, bytes.length - i));
                    if (n <= 0) break;
                    i += n;
                }
            }
        } });
    }
    private void clearAudio() {
        audioEpoch++;
        synchronized (speakerLock) { if (speaker != null) { try { speaker.pause(); speaker.flush(); speaker.play(); } catch (Exception ignored) {} } }
    }
    @SimpleFunction(description = "Begin streaming 16 kHz microphone PCM. Grant Android RECORD_AUDIO permission before calling.")
    public void StartListening() {
        if (!connected) { error("Gemini is not connected"); return; }
        if (android.os.Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error("Microphone permission missing. Use Screen.AskForPermission(android.permission.RECORD_AUDIO) and retry."); return;
        }
        if (listening) return;
        listening = true;
        micThread = new Thread(new Runnable() { @Override public void run() {
            try {
                int required = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (required < 0) throw new IOException("Microphone unsupported");
                mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(6400, required * 2));
                if (mic.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone could not initialize");
                mic.startRecording();
                byte[] chunk = new byte[3200];
                while (listening && connected && !disposed) {
                    int count = mic.read(chunk, 0, chunk.length);
                    if (count > 0) {
                        String encoded = Base64.encodeToString(chunk, 0, count, Base64.NO_WRAP);
                        JSONObject audio = new JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", encoded);
                        send(new JSONObject().put("realtimeInput", new JSONObject().put("audio", audio)).toString());
                    }
                }
            } catch (Exception e) { if (listening) error("Microphone: " + e.getMessage()); }
            finally {
                AudioRecord r = mic; mic = null;
                if (r != null) { try { r.stop(); } catch (Exception ignored) {} r.release(); }
                listening = false;
            }
        } }, "GikkuBuddy-Microphone");
        micThread.start();
    }
    @SimpleFunction public void StopListening() { listening = false; }
    @SimpleFunction public void SendText(String message) {
        if (!connected) { error("Gemini is not connected"); return; }
        try {
            JSONObject turn = new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", message)));
            send(new JSONObject().put("clientContent", new JSONObject().put("turns", new JSONArray().put(turn)).put("turnComplete", true)).toString());
        } catch (Exception e) { error("SendText: " + e.getMessage()); }
    }
    @SimpleFunction public void Disconnect() {
        userDisconnected = true;
        cancelConnectionTimers();
        ++connectionId;
        disconnectInternal();
        status("disconnected", "Disconnected by user");
    }
    private void disconnectInternal() {
        listening = false; connected = false;
        NativeWebSocket existing = socket; socket = null;
        if (existing != null) existing.close();
        clearAudio();
        synchronized (speakerLock) { if (speaker != null) { try { speaker.stop(); speaker.release(); } catch (Exception ignored) {} speaker = null; } }
    }

    @SimpleFunction(description = "Enable accelerometer, proximity and light sensor reactions when sensors exist on device.")
    public void EnableSensors(boolean enabled) {
        sensorEnabled = enabled;
        sensorsManager.unregisterListener(this);
        if (enabled) {
            for (int kind : new int[] { Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_PROXIMITY, Sensor.TYPE_LIGHT }) {
                Sensor s = sensorsManager.getDefaultSensor(kind);
                if (s != null) sensorsManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if (!sensorEnabled || disposed) return;
        long now = SystemClock.uptimeMillis();
        int kind = event.sensor.getType();
        if (kind == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            float magnitude = Math.abs(x - lastAx) + Math.abs(y - lastAy) + Math.abs(z - lastAz);
            lastAx = x; lastAy = y; lastAz = z;
            if (magnitude > 24 && now - lastShake > 2000) {
                lastShake = now; SensorTriggered("shake", magnitude);
                changeEmotion("dizzy", true, 1500, 8);
            }
        } else if (kind == Sensor.TYPE_PROXIMITY && now - lastProximityEvent > 1500) {
            lastProximityEvent = now; SensorTriggered("proximity", event.values[0]);
        } else if (kind == Sensor.TYPE_LIGHT && now - lastLightEvent > 2000) {
            lastLightEvent = now; SensorTriggered("light", event.values[0]);
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    @SimpleFunction public void RefreshBattery() {
        Intent i = activity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i != null) updateBattery(i);
    }
    private void updateBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return;
        final int pct = Math.max(0, Math.min(100, (int)(100.0 * level / scale)));
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        final boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        final boolean first = !batteryKnown;
        final boolean changed = !first && batteryCharging != charging;
        final boolean levelChanged = !first && batteryPercent != pct;
        batteryKnown = true;
        batteryPercent = pct;
        batteryCharging = charging;
        main.post(new Runnable() { @Override public void run() {
            if (disposed) return;
            if (first || changed || levelChanged) BatteryChanged(pct, charging);
            // A new charging connection briefly shows the charging expression.
            // The persistent charging/low-battery badge is independent of the emotion.
            if (changed && charging) changeEmotion("charging", true, 1800, 10);
            else if (changed && !charging && emotion.equals("charging")) changeEmotion("Idle", true, 0, 10);
            if (face != null) face.invalidate();
        } });
    }
    @Override public void onDestroy() {
        disposed = true;
        main.removeCallbacks(animationTicker);
        userDisconnected = true;
        cancelConnectionTimers();
        if (batteryReceiverRegistered) {
            try { activity.unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
            batteryReceiverRegistered = false;
        }
        EnableSensors(false);
        Disconnect();
        network.shutdownNow(); decoder.shutdownNow(); audioQueue.shutdownNow();
        bitmaps.evictAll();
    }
    private void error(final String message) {
        main.post(new Runnable() { @Override public void run() { if (!disposed) Error(message); } });
    }

    @SimpleEvent public void AssetsProgress(int percent, long downloaded, long total) { EventDispatcher.dispatchEvent(this, "AssetsProgress", percent, downloaded, total); }
    @SimpleEvent public void AssetsReady(String folder, String version) { EventDispatcher.dispatchEvent(this, "AssetsReady", folder, version); }
    @SimpleEvent public void AssetsError(String message) { EventDispatcher.dispatchEvent(this, "AssetsError", message); }
    @SimpleEvent public void AssetsCleared() { EventDispatcher.dispatchEvent(this, "AssetsCleared"); }
    @SimpleEvent public void FramesLoaded(int emotions, int frames) { EventDispatcher.dispatchEvent(this, "FramesLoaded", emotions, frames); }
    @SimpleEvent public void EmotionChanged(String value) { EventDispatcher.dispatchEvent(this, "EmotionChanged", value); }
    @SimpleEvent public void BuddyTouched(float x, float y) { EventDispatcher.dispatchEvent(this, "BuddyTouched", x, y); }
    @SimpleEvent(description = "Connection stage and diagnostic details: connecting, configuring, connected, retrying, failed, timeout, server_error, disconnected.")
    public void ConnectionStatus(String stage, String detail) { EventDispatcher.dispatchEvent(this, "ConnectionStatus", stage, detail); }
    @SimpleEvent public void Connected() { EventDispatcher.dispatchEvent(this, "Connected"); }
    @SimpleEvent public void Disconnected(String reason) { EventDispatcher.dispatchEvent(this, "Disconnected", reason); }
    @SimpleEvent public void Transcript(String text) { EventDispatcher.dispatchEvent(this, "Transcript", text); }
    @SimpleEvent public void FunctionCalled(String name, String args) { EventDispatcher.dispatchEvent(this, "FunctionCalled", name, args); }
    @SimpleEvent public void SensorTriggered(String name, float value) { EventDispatcher.dispatchEvent(this, "SensorTriggered", name, value); }
    @SimpleEvent public void BatteryChanged(int level, boolean charging) { EventDispatcher.dispatchEvent(this, "BatteryChanged", level, charging); }
    @SimpleEvent public void Error(String message) { EventDispatcher.dispatchEvent(this, "Error", message); }

    /** Procedural rendering based on the user's original black/cyan two-eye artwork. */
    private final class FaceView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final int cyan = 0xFF9BFFFF, cyanBright = 0xFFCCFFFF, red = 0xFFFF385A, pink = 0xFFFF4D9E;
        private long origin = SystemClock.uptimeMillis();
        FaceView(Context ctx) { super(ctx); setLayerType(View.LAYER_TYPE_SOFTWARE, null); setBackgroundColor(0xFF000000); }
        private void stroke(int color, float width, float glow) {
            p.reset(); p.setAntiAlias(true); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND); p.setStrokeWidth(width); p.setColor(color);
            if (glow > 0) p.setShadowLayer(glow, 0, 0, color);
        }
        private void fill(int color, float glow) {
            p.reset(); p.setAntiAlias(true); p.setStyle(Paint.Style.FILL); p.setColor(color);
            if (glow > 0) p.setShadowLayer(glow, 0, 0, color);
        }
        private void oval(Canvas c, float cx, float cy, float rx, float ry, int color, float glow) {
            fill(color, glow); rect.set(cx-rx, cy-ry, cx+rx, cy+ry); c.drawOval(rect,p);
        }
        private void arc(Canvas c, float cx, float cy, float rx, float ry, float start, float sweep, int color, float width, float glow) {
            stroke(color,width,glow); rect.set(cx-rx,cy-ry,cx+rx,cy+ry); c.drawArc(rect,start,sweep,false,p);
        }
        private void line(Canvas c,float x1,float y1,float x2,float y2,int color,float width,float glow){
            stroke(color,width,glow);c.drawLine(x1,y1,x2,y2,p);
        }
        private void heart(Canvas c,float x,float y,float scale,int color){
            c.save();c.translate(x,y);c.scale(scale,scale);
            path.reset();path.moveTo(0,13);path.cubicTo(-42,-13,-19,-36,0,-18);path.cubicTo(19,-36,42,-13,0,13);
            fill(color,9);c.drawPath(path,p);c.restore();
        }
        private void star(Canvas c,float x,float y,float radius,int color){
            stroke(color,2,12);c.drawLine(x-radius,y,x+radius,y,p);c.drawLine(x,y-radius,x,y+radius,p);
            oval(c,x,y,3,3,color,8);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xFF000000);
            float w=getWidth(),h=getHeight(); if(w<1||h<1)return;
            canvas.save();float scale=Math.min(w/400f,h/270f);canvas.translate(w/2f,h/2f);canvas.scale(scale,scale);
            float t=(SystemClock.uptimeMillis()-origin)/1000f;
            String e=emotion;
            // Low battery changes only the idle face into a sad expression;
            // explicit emotions from Gemini/Kodular still work normally.
            if (batteryKnown && !batteryCharging && batteryPercent < 30 && e.equals("Idle")) e="sad";
            int col=(e.equals("angry")||e.equals("love"))?(e.equals("angry")?red:pink):cyan;
            float bob=(float)Math.sin(t*2.0)*2.2f;
            if(e.equals("excited")||e.equals("charged"))bob=(float)Math.sin(t*7)*4;
            if(e.equals("dizzy"))canvas.rotate((float)Math.sin(t*3)*6);
            if(e.equals("scared"))canvas.translate((float)Math.sin(t*29)*3,0);
            canvas.translate(0,bob);
            float blink=(float)(t%4.3);float eyeH=(blink>4.05f&&blink<4.19f)?0.10f:1f;
            if(e.equals("sleeping"))eyeH=.15f;
            if(e.equals("tired"))eyeH=.60f;
            if(e.equals("shy"))eyeH=.78f;
            if(e.equals("focus"))eyeH=.8f;
            float gaze=(float)Math.sin(t*.8)*2.5f;
            for(int side=-1;side<=1;side+=2){
                float x=side*66;
                float ey=-1;
                if(e.equals("bored"))ey=3;
                if(e.equals("angry"))ey=2;
                float rx=(e.equals("excited")?31:32), ry=32*eyeH;
                if(e.equals("sleeping")){
                    arc(canvas,x,4,34,15,180,180,col,5,13);
                } else if(e.equals("dizzy")) {
                    arc(canvas,x,0,31,18,180,180,col,6,14);
                    for(int k=0;k<3;k++)arc(canvas,0,0,11+k*9,11+k*9,(t*95+k*120)%360,140,col,2,9);
                } else if(e.equals("love")){
                    heart(canvas,x,0,1.6f,col);
                } else {
                    oval(canvas,x,ey,rx,ry,col,19);
                    if(e.equals("curious_alt")||e.equals("sad")||e.equals("proud")||e.equals("updating")||e.equals("angry")||e.equals("guilt")){
                        if(e.equals("updating")){
                            line(canvas,x,ey-25,x,ey+25,0xFF003F4C,6,0);
                        } else if(e.equals("angry")){
                            oval(canvas,x-side*5,ey,11,13,0xFF350A13,0);
                            oval(canvas,x-side*5,ey,5,7,0xFFFFE0E4,0);
                        } else {
                            oval(canvas,x+gaze,ey+1,10,12,0xFF003D49,0);
                            if(e.equals("guilt"))oval(canvas,x+gaze-3,ey-3,3,4,cyanBright,2);
                        }
                    }
                    if(e.equals("charged")){
                        for(int k=-1;k<=1;k++)line(canvas,x-29,ey+k*12,x+29,ey+k*12,0xFF2A9FA9,2,3);
                    }
                    if(e.equals("chilling"))for(int k=0;k<3;k++)arc(canvas,x,ey,rx+7+k*6,ry+7+k*6,0,360,col,2,9);
                    if(e.equals("excited")){
                        for(int k=0;k<8;k++){
                            float a=(float)(k*Math.PI/4+t*.6);
                            float sx=x+(float)Math.cos(a)*50,sy=ey+(float)Math.sin(a)*50;
                            star(canvas,sx,sy,4+(k%3)*2,col);
                        }
                    }
                }
                if(!e.equals("sleeping")&&!e.equals("dizzy")&& !e.equals("chilling")){
                    float browY=-50;
                    if(e.equals("angry")||e.equals("focus")){
                        line(canvas,x-30,browY-side*12,x+30,browY+side*12,col,5,10);
                    }else if(e.equals("low_battery")||e.equals("sad")){
                        line(canvas,x-28,browY+side*10,x+27,browY-side*7,col,4,8);
                    }else if(e.equals("tired")){
                        line(canvas,x-27,browY+10,x+28,browY+13,col,3,6);
                    }else{
                        arc(canvas,x,browY+18,34,18,200,140,col,4,10);
                    }
                }
            }
            if(e.equals("charging")){
                for(int k=0;k<5;k++)arc(canvas,0,0,122+k*7,83+k*4,(t*75+k*60)%360,50,col,2,6);
                stroke(col,3,9);rect.set(147,-45,164,40);canvas.drawRoundRect(rect,3,3,p);
                fill(col,8);rect.set(150,32-70*(.5f+.5f*(float)Math.sin(t)),161,36);canvas.drawRect(rect,p);
            }
            if(e.equals("low_battery")){
                stroke(col,2,6);canvas.drawLine(-110,-20,-123,20,p);canvas.drawLine(110,-20,123,20,p);
            }
            if(e.equals("updating")){
                arc(canvas,0,0,157,92,(t*100)%360,75,col,3,12);
            }
            if(e.equals("thinking")){
                for(int i=0;i<3;i++)oval(canvas,115+i*14,-75-i*12,4+i*2,4+i*2,col,9);
            }
            if(e.equals("proud")){
                for(int i=0;i<4;i++)star(canvas,-155+i*103,80+(float)Math.sin(t+i)*10,6,col);
            }
            if(e.equals("shy")){
                for(int i=0;i<3;i++)oval(canvas,-130+i*14,53,3,3,pink,7);
                for(int i=0;i<3;i++)oval(canvas,102+i*14,53,3,3,pink,7);
            }
            // Always-on status badge, drawn over the avatar in the upper-right.
            if (batteryKnown && (batteryCharging || batteryPercent < 30)) {
                final int badgeColor = batteryCharging ? 0xFF36FFB0 : 0xFFFF667A;
                float bx=148f, by=-95f;
                stroke(badgeColor,4,10);
                rect.set(bx-21,by-14,bx+17,by+14);
                canvas.drawRoundRect(rect,4,4,p);
                fill(badgeColor,5);
                rect.set(bx+18,by-5,bx+23,by+5);
                canvas.drawRoundRect(rect,2,2,p);
                if (batteryCharging) {
                    path.reset();
                    path.moveTo(bx+2,by-11);path.lineTo(bx-9,by+3);
                    path.lineTo(bx,by+3);path.lineTo(bx-5,by+12);
                    path.lineTo(bx+12,by-4);path.lineTo(bx+3,by-4);path.close();
                    canvas.drawPath(path,p);
                } else {
                    rect.set(bx-17,by-10,bx-17+Math.max(2,30*batteryPercent/100),by+10);
                    canvas.drawRoundRect(rect,2,2,p);
                    p.setShadowLayer(0,0,0,0);
                    p.setColor(badgeColor);p.setStyle(Paint.Style.FILL);
                    p.setTextSize(16);p.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText("!",bx,by+6,p);
                }
            }
            canvas.restore();
        }
    }

    /** RFC6455 minimal binary-safe TLS websocket. No extra JARs required. */
    private static final class NativeWebSocket {
        interface Listener { void opened(); void message(String text); void closed(String reason); }
        private final String url;
        private final Listener listener;
        private final Object writeLock = new Object();
        private final SecureRandom random = new SecureRandom();
        private volatile boolean running = true;
        private volatile String sendFailure = "";
        private SSLSocket tls;
        private InputStream input;
        private OutputStream output;
        NativeWebSocket(String endpoint, Listener callbacks) { url = endpoint; listener = callbacks; }
        void start() {
            Thread thread = new Thread(new Runnable() { @Override public void run() { connectAndRead(); } }, "GikkuBuddy-WebSocket");
            thread.start();
        }
        boolean send(String text) {
            if (!running || output == null) { sendFailure = "WebSocket is not ready"; return false; }
            return writeFrame(1, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String sendError() { return sendFailure; }
        void close() {
            running = false;
            try { if (tls != null) tls.close(); } catch (Exception ignored) { }
        }
        private void connectAndRead() {
            String reason = "Disconnected";
            try {
                URI parsed = new URI(url);
                if (!"wss".equalsIgnoreCase(parsed.getScheme())) throw new IOException("Only wss:// is supported");
                String host = parsed.getHost(); if (host == null) throw new IOException("Invalid WebSocket host");
                int port = parsed.getPort() < 0 ? 443 : parsed.getPort();
                tls = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
                tls.setTcpNoDelay(true);
                tls.setSoTimeout(20000);
                tls.startHandshake();
                input = new BufferedInputStream(tls.getInputStream());
                output = new BufferedOutputStream(tls.getOutputStream());
                byte[] nonce = new byte[16]; random.nextBytes(nonce);
                String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
                String target = parsed.getRawPath(); if (target == null || target.isEmpty()) target = "/";
                if (parsed.getRawQuery() != null) target += "?" + parsed.getRawQuery();
                String handshake = "GET " + target + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
                output.write(handshake.getBytes("UTF-8")); output.flush();
                String headers = readHeaders(input);
                if (!headers.startsWith("HTTP/1.1 101") && !headers.startsWith("HTTP/1.0 101")) throw new IOException("WebSocket handshake rejected (" + headers.split("\\r?\\n")[0] + ")");
                String accept = "";
                for (String line : headers.split("\\r?\\n")) if (line.toLowerCase(Locale.US).startsWith("sec-websocket-accept:")) accept = line.substring(line.indexOf(':') + 1).trim();
                byte[] hash = MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8"));
                if (!accept.equals(Base64.encodeToString(hash, Base64.NO_WRAP))) throw new IOException("Invalid websocket handshake response");
                tls.setSoTimeout(0);
                listener.opened();
                ByteArrayOutputStream fragments = new ByteArrayOutputStream();
                int priorOpcode = -1;
                while (running) {
                    int first = input.read(); if (first < 0) break;
                    int second = input.read(); if (second < 0) break;
                    boolean fin = (first & 128) != 0;
                    int opcode = first & 15;
                    boolean masked = (second & 128) != 0;
                    long length = second & 127;
                    if (length == 126) length = ((long) readByte(input) << 8) | readByte(input);
                    if (length == 127) { length = 0; for (int i = 0; i < 8; i++) length = (length << 8) | readByte(input); }
                    if (length > 8L * 1024 * 1024) throw new IOException("Incoming websocket message exceeds 8 MB");
                    byte[] mask = masked ? readFully(input, 4) : null;
                    byte[] payload = readFully(input, (int) length);
                    if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                    if (opcode == 8) {
                        int code = payload.length >= 2 ? ((payload[0] & 255) << 8) | (payload[1] & 255) : 1005;
                        String detail = payload.length > 2 ? new String(payload, 2, payload.length - 2, "UTF-8") : "";
                        reason = "Gemini WebSocket closed (" + code + ")" + (detail.isEmpty() ? "" : ": " + detail);
                        break;
                    }
                    if (opcode == 9) { writeFrame(10, payload); continue; }
                    if (opcode == 10) continue;
                    if (opcode == 1 || opcode == 2) { fragments.reset(); priorOpcode = opcode; }
                    else if (opcode != 0 || priorOpcode < 0) throw new IOException("Unsupported websocket frame");
                    if (fragments.size() + payload.length > 8L * 1024 * 1024) throw new IOException("Message too large");
                    fragments.write(payload);
                    if (fin) {
                        if (priorOpcode == 1 || priorOpcode == 2) listener.message(new String(fragments.toByteArray(), "UTF-8"));
                        fragments.reset(); priorOpcode = -1;
                    }
                }
            } catch (Exception ex) {
                reason = ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "Socket error" : ex.getMessage());
            }
            finally { running = false; close(); listener.closed(reason); }
        }
        private boolean writeFrame(int opcode, byte[] payload) {
            synchronized (writeLock) {
                if (!running || output == null) return false;
                try {
                    output.write(0x80 | opcode);
                    int size = payload.length;
                    if (size < 126) output.write(0x80 | size);
                    else if (size <= 65535) { output.write(0x80 | 126); output.write((size >>> 8) & 255); output.write(size & 255); }
                    else { output.write(0x80 | 127); for (int i = 7; i >= 0; i--) output.write((int) (((long) size >>> (8 * i)) & 255L)); }
                    byte[] mask = new byte[4]; random.nextBytes(mask); output.write(mask);
                    byte[] data = new byte[size]; for (int i = 0; i < size; i++) data[i] = (byte) (payload[i] ^ mask[i & 3]);
                    output.write(data); output.flush();
                    return true;
                } catch (IOException ex) {
                    sendFailure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                    close();
                    return false;
                }
            }
        }
        private static int readByte(InputStream in) throws IOException { int b = in.read(); if (b < 0) throw new EOFException(); return b; }
        private static byte[] readFully(InputStream in, int size) throws IOException {
            byte[] result = new byte[size]; int off = 0;
            while (off < size) { int n = in.read(result, off, size - off); if (n < 0) throw new EOFException(); off += n; }
            return result;
        }
        private static String readHeaders(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); int matched = 0;
            while (out.size() < 16384) {
                int b = readByte(in); out.write(b);
                if (b == "\r\n\r\n".charAt(matched)) matched++; else matched = b == '\r' ? 1 : 0;
                if (matched == 4) return out.toString("UTF-8");
            }
            throw new IOException("HTTP handshake headers too large");
        }
    }
}