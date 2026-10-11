package com.fabiyon.deskbuddy;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.AndroidViewComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.OnDestroyListener;
import com.google.appinventor.components.runtime.EventDispatcher;

import org.json.*;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** GikkuBuddy: lightweight native-only Kodular extension; remote assets, Gemini Live, sensors. */
@DesignerComponent(version = 3, description = "GikkuBuddy AI Desk Buddy: remote native animations, Gemini Live audio, function-call emotions and sensors", category = ComponentCategory.EXTENSION, nonVisible = true, iconName = "")
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
    private volatile boolean disposed = false, downloading = false, connected = false, listening = false;
    private String emotion = "Idle", version = "", prompt = "You are GikkuBuddy, a warm, curious, playful desktop AI friend. React naturally. Call set_emotion to change face when a meaningful emotion changes, not on every sentence.", voice = "Puck", model = "gemini-2.5-flash-native-audio-preview-09-2025";
    private int fps = 15, frameNumber = 0, activePriority = 0;
    private long emotionExpires = 0, lastShake = 0, lastLightEvent = 0, lastProximityEvent = 0;
    private boolean loop = true, sensorEnabled = false;
    private float lastAx, lastAy, lastAz;
    private Thread micThread;
    private AudioRecord mic;
    private AudioTrack speaker;
    private final Object speakerLock = new Object();
    private volatile int audioEpoch = 0;
    private NativeWebSocket socket;
    private volatile int connectionId = 0;
    private boolean tickerRunning = false;

    public DeskBuddy(ComponentContainer container) {
        super(container.$form());
        activity = container.$context();
        container.$form().registerForOnDestroy(this);
        sensorsManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        int kb = (int) Math.min(24L * 1024, Math.max(4L * 1024, Runtime.getRuntime().maxMemory() / 1024 / 12));
        bitmaps = new LruCache<String, Bitmap>(kb) {
            @Override protected int sizeOf(String key, Bitmap bitmap) { return Math.max(1, bitmap.getByteCount() / 1024); }
        };
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
            File[] frames;
            synchronized (sequences) { frames = sequences.get(emotion); }
            if (image != null && frames != null && frames.length > 0) {
                final File target = frames[Math.min(frameNumber, frames.length - 1)];
                Bitmap cached = bitmaps.get(target.getAbsolutePath());
                if (cached != null) image.setImageBitmap(cached);
                else decoder.execute(new Runnable() {
                    @Override public void run() {
                        final String key = target.getAbsolutePath();
                        Bitmap loaded = bitmaps.get(key);
                        if (loaded == null) {
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inPreferredConfig = Bitmap.Config.RGB_565;
                            loaded = BitmapFactory.decodeFile(key, opts);
                            if (loaded != null) bitmaps.put(key, loaded);
                        }
                        final Bitmap result = loaded;
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (!disposed && image != null && result != null && emotion.equals(target.getParentFile().getName())) image.setImageBitmap(result);
                            }
                        });
                    }
                });
                if (frameNumber < frames.length - 1) frameNumber++;
                else if (loop) frameNumber = 0;
                final int next = Math.min(frameNumber, frames.length - 1);
                final File upcoming = frames[next];
                if (bitmaps.get(upcoming.getAbsolutePath()) == null) decoder.execute(new Runnable() {
                    @Override public void run() {
                        String k = upcoming.getAbsolutePath();
                        if (bitmaps.get(k) == null) {
                            BitmapFactory.Options o = new BitmapFactory.Options(); o.inPreferredConfig = Bitmap.Config.RGB_565;
                            Bitmap b = BitmapFactory.decodeFile(k, o);
                            if (b != null) bitmaps.put(k, b);
                        }
                    }
                });
            }
            main.postDelayed(this, Math.max(33, 1000 / Math.max(1, fps)));
        }
    };

    @SimpleFunction(description = "Attach buddy's native animation view inside a Kodular arrangement.")
    public void AttachTo(final AndroidViewComponent arrangement) {
        main.post(new Runnable() { @Override public void run() {
            if (disposed) return;
            View target = arrangement.getView();
            if (!(target instanceof ViewGroup)) { error("AttachTo needs an Arrangement component"); return; }
            if (stage != null && stage.getParent() instanceof ViewGroup) ((ViewGroup) stage.getParent()).removeView(stage);
            FrameLayout host = new FrameLayout(activity);
            ImageView sprite = new ImageView(activity);
            sprite.setScaleType(ImageView.ScaleType.FIT_CENTER);
            host.addView(sprite, new FrameLayout.LayoutParams(-1, -1));
            ((ViewGroup) target).addView(host, new ViewGroup.LayoutParams(-1, -1));
            stage = host; image = sprite;
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

    @SimpleFunction(description = "Download the GitHub Release emotion ZIP on first run; reuse existing cached pack offline.")
    public void InitializeAssets(String url) {
        if (downloading || disposed) return;
        downloading = true;
        final String manifestUrl = url == null || url.trim().length() == 0 ? MANIFEST_URL : url.trim();
        network.execute(new Runnable() { @Override public void run() {
            File root = new File(activity.getFilesDir(), "gikku_buddy");
            File active = new File(root, "emotions");
            File staging = new File(root, "staging");
            File backup = new File(root, "backup");
            File zip = new File(root, "download.tmp");
            boolean usedCache = false;
            try {
                if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create storage folder");
                JSONObject meta;
                try { meta = new JSONObject(fetchString(manifestUrl, 65536)); }
                catch (Exception fetchFailure) {
                    if (loadInstalled(active)) { notifyAssetsReady(active, readVersion(root)); usedCache = true; return; }
                    throw new IOException("Manifest unavailable: " + fetchFailure.getMessage());
                }
                String remoteVersion = meta.getString("version");
                String expectedSha = meta.getString("sha256");
                String packUrl = meta.getString("url");
                if (!packUrl.startsWith("https://") || !expectedSha.matches("[0-9a-fA-F]{64}")) throw new IOException("Unsafe manifest");
                if (remoteVersion.equals(readVersion(root)) && loadInstalled(active)) { notifyAssetsReady(active, remoteVersion); usedCache = true; return; }
                downloadZip(packUrl, zip, expectedSha);
                deleteTree(staging);
                if (!staging.mkdirs()) throw new IOException("Cannot create staging folder");
                unpack(zip, staging);
                if (!validatePack(staging)) throw new IOException("Incorrect emotion folders or frames");
                deleteTree(backup);
                if (active.exists() && !active.renameTo(backup)) throw new IOException("Cannot back up existing pack");
                if (!staging.renameTo(active)) {
                    if (backup.exists()) backup.renameTo(active);
                    throw new IOException("Cannot activate downloaded pack");
                }
                if (!loadInstalled(active)) throw new IOException("Pack activation invalid");
                version = remoteVersion;
                writeString(new File(root, "version.txt"), remoteVersion);
                deleteTree(backup);
                bitmaps.evictAll();
                notifyAssetsReady(active, remoteVersion);
            } catch (Exception ex) {
                if (!usedCache && loadInstalled(active)) {
                    final String msg = "Update failed; using offline cache: " + ex.getMessage();
                    main.post(new Runnable() { @Override public void run() { AssetsError(msg); } });
                    notifyAssetsReady(active, readVersion(root));
                } else if (!usedCache) {
                    final String msg = ex.getMessage();
                    main.post(new Runnable() { @Override public void run() { AssetsError(msg); } });
                }
            } finally {
                if (zip.exists()) zip.delete();
                downloading = false;
            }
        }});
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
    @SimpleFunction public void ClearEmotionCache() {
        network.execute(new Runnable() { @Override public void run() {
            synchronized (sequences) { sequences.clear(); }
            bitmaps.evictAll();
            deleteTree(new File(activity.getFilesDir(), "gikku_buddy"));
            main.post(new Runnable() { @Override public void run() { AssetsCleared(); } });
        } });
    }
    @SimpleFunction public String AvailableEmotions() { return new JSONArray(Arrays.asList(EMOTIONS)).toString(); }
    @SimpleFunction public void SetFPS(int requested) { fps = Math.max(1, Math.min(30, requested)); }
    @SimpleFunction public void SetEmotion(final String name, final boolean repeat, final int durationMs) {
        main.post(new Runnable() { @Override public void run() { changeEmotion(name, repeat, durationMs, 10); } });
    }
    private void changeEmotion(String name, boolean repeat, int duration, int newPriority) {
        if (!EMOTION_SET.contains(name)) { error("Unknown emotion: " + name); return; }
        synchronized (sequences) { if (!sequences.containsKey(name)) { error("Assets have not finished loading"); return; } }
        long now = SystemClock.uptimeMillis();
        if (activePriority > newPriority && emotionExpires > now) return;
        emotion = name; frameNumber = 0; loop = repeat;
        activePriority = newPriority;
        emotionExpires = duration > 0 ? now + Math.min(duration, 120000) : 0;
        EmotionChanged(name);
    }
    @SimpleFunction public String CurrentEmotion() { return emotion; }
    @SimpleFunction public boolean AssetsInstalled() { return validatePack(new File(activity.getFilesDir(), "gikku_buddy/emotions")); }
    @SimpleFunction public void SetInstructions(String instructions) { if (instructions != null) prompt = instructions; }
    @SimpleFunction public void SetModel(String name) { if (name != null && name.matches("[A-Za-z0-9._-]+")) model = name; }
    @SimpleFunction public void SetVoice(String name) { if (name != null && name.matches("[A-Za-z0-9_-]+")) voice = name; }

    @SimpleFunction(description = "Connect to Gemini Live with an API key (prototype; use ephemeral credentials in production).")
    public void Connect(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) { error("Gemini API key missing"); return; }
        disconnectInternal();
        final int id = ++connectionId;
        try {
            String endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + URLEncoder.encode(apiKey.trim(), "UTF-8");
            socket = new NativeWebSocket(endpoint, new NativeWebSocket.Listener() {
                @Override public void opened() { if (id == connectionId) sendSetup(); }
                @Override public void message(String text) { if (id == connectionId) onServerMessage(text); }
                @Override public void closed(String reason) {
                    if (id == connectionId) {
                        connected = false; listening = false;
                        final String why = reason;
                        main.post(new Runnable() { @Override public void run() { Disconnected(why); } });
                    }
                }
            });
            socket.start();
        } catch (Exception e) { error("Connection setup: " + e.getMessage()); }
    }
    private void sendSetup() {
        try {
            JSONObject config = new JSONObject().put("responseModalities", new JSONArray().put("AUDIO"));
            JSONObject speech = new JSONObject().put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice)));
            config.put("speechConfig", speech);
            JSONObject def = new JSONObject().put("name", "set_emotion").put("description", "Change GikkuBuddy's facial emotion to fit the conversation; use only when appropriate.");
            JSONObject props = new JSONObject().put("emotion", new JSONObject().put("type", "STRING").put("enum", new JSONArray(Arrays.asList(EMOTIONS))));
            props.put("duration_ms", new JSONObject().put("type", "INTEGER").put("description", "How long to play this emotion (500 to 10000 milliseconds)"));
            def.put("parameters", new JSONObject().put("type", "OBJECT").put("properties", props).put("required", new JSONArray().put("emotion")));
            JSONObject setup = new JSONObject().put("model", "models/" + model).put("generationConfig", config);
            setup.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt))));
            setup.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", new JSONArray().put(def))));
            send(new JSONObject().put("setup", setup).toString());
        } catch (Exception e) { error("Gemini setup: " + e.getMessage()); }
    }
    private void onServerMessage(String text) {
        try {
            JSONObject reply = new JSONObject(text);
            if (reply.has("setupComplete")) {
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
    @SimpleFunction public void Disconnect() { ++connectionId; disconnectInternal(); }
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
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = Math.max(1, i.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int pct = (int) (100.0 * level / scale);
        int state = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL;
        BatteryChanged(pct, charging);
        if (charging) changeEmotion("charging", true, 1200, 2);
        else if (pct < 15) changeEmotion("low_battery", true, 2000, 2);
    }
    @Override public void onDestroy() {
        disposed = true;
        main.removeCallbacks(animationTicker);
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
    @SimpleEvent public void Connected() { EventDispatcher.dispatchEvent(this, "Connected"); }
    @SimpleEvent public void Disconnected(String reason) { EventDispatcher.dispatchEvent(this, "Disconnected", reason); }
    @SimpleEvent public void Transcript(String text) { EventDispatcher.dispatchEvent(this, "Transcript", text); }
    @SimpleEvent public void FunctionCalled(String name, String args) { EventDispatcher.dispatchEvent(this, "FunctionCalled", name, args); }
    @SimpleEvent public void SensorTriggered(String name, float value) { EventDispatcher.dispatchEvent(this, "SensorTriggered", name, value); }
    @SimpleEvent public void BatteryChanged(int level, boolean charging) { EventDispatcher.dispatchEvent(this, "BatteryChanged", level, charging); }
    @SimpleEvent public void Error(String message) { EventDispatcher.dispatchEvent(this, "Error", message); }

    /** RFC6455 minimal binary-safe TLS websocket. No extra JARs required. */
    private static final class NativeWebSocket {
        interface Listener { void opened(); void message(String text); void closed(String reason); }
        private final String url;
        private final Listener listener;
        private final Object writeLock = new Object();
        private final SecureRandom random = new SecureRandom();
        private volatile boolean running = true;
        private SSLSocket tls;
        private InputStream input;
        private OutputStream output;
        NativeWebSocket(String endpoint, Listener callbacks) { url = endpoint; listener = callbacks; }
        void start() {
            Thread thread = new Thread(new Runnable() { @Override public void run() { connectAndRead(); } }, "GikkuBuddy-WebSocket");
            thread.start();
        }
        void send(String text) { if (!running) return; writeFrame(1, text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
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
                    if (opcode == 8) { reason = "Server closed socket"; break; }
                    if (opcode == 9) { writeFrame(10, payload); continue; }
                    if (opcode == 10) continue;
                    if (opcode == 1 || opcode == 2) { fragments.reset(); priorOpcode = opcode; }
                    else if (opcode != 0 || priorOpcode < 0) throw new IOException("Unsupported websocket frame");
                    if (fragments.size() + payload.length > 8L * 1024 * 1024) throw new IOException("Message too large");
                    fragments.write(payload);
                    if (fin) {
                        if (priorOpcode == 1) listener.message(new String(fragments.toByteArray(), "UTF-8"));
                        fragments.reset(); priorOpcode = -1;
                    }
                }
            } catch (Exception ex) { reason = ex.getMessage() == null ? "Socket error" : ex.getMessage(); }
            finally { running = false; close(); listener.closed(reason); }
        }
        private void writeFrame(int opcode, byte[] payload) {
            synchronized (writeLock) {
                if (!running || output == null) return;
                try {
                    output.write(0x80 | opcode);
                    int size = payload.length;
                    if (size < 126) output.write(0x80 | size);
                    else if (size <= 65535) { output.write(0x80 | 126); output.write((size >>> 8) & 255); output.write(size & 255); }
                    else { output.write(0x80 | 127); for (int i = 7; i >= 0; i--) output.write((int) (((long) size >>> (8 * i)) & 255L)); }
                    byte[] mask = new byte[4]; random.nextBytes(mask); output.write(mask);
                    byte[] data = new byte[size]; for (int i = 0; i < size; i++) data[i] = (byte) (payload[i] ^ mask[i & 3]);
                    output.write(data); output.flush();
                } catch (IOException ex) { close(); }
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