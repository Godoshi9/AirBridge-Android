package com.airbridge.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class AudioStreamingService extends Service {
    public static final String ACTION_START = "com.airbridge.mobile.START";
    public static final String ACTION_STOP = "com.airbridge.mobile.STOP";
    public static final String ACTION_TONE = "com.airbridge.mobile.TONE";
    public static final String ACTION_VOLUME = "com.airbridge.mobile.VOLUME";
    public static final String EXTRA_OPTIONS = "options";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    private static final String CHANNEL = "airbridge_playback";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread captureThread;
    private AudioRecord recorder;
    private MediaProjection projection;
    private WifiManager.MulticastLock multicastLock;
    private int recorderChannels = 2;
    private int sampleRate = 48000, bufferMs = 500;
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean toneRequested = new AtomicBoolean();
    private volatile String captureError;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) { stopStreaming(); stopSelf(); return START_NOT_STICKY; }
        if (ACTION_TONE.equals(action)) { if (running.get()) toneRequested.set(true); else stopSelf(); return START_NOT_STICKY; }
        if (ACTION_VOLUME.equals(action)) { NativeBridge.nativeSetDeviceVolume(intent.getIntExtra("volume", 50)); return START_STICKY; }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        String source = intent.getStringExtra(EXTRA_SOURCE);
        if (Build.VERSION.SDK_INT >= 29) {
            int type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if ("system".equals(source)) type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if ("microphone".equals(source) && Build.VERSION.SDK_INT >= 30) type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(19, notification("正在连接 HomePod…"), type);
        } else startForeground(19, notification("正在连接 HomePod…"));
        stopStreaming();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("AirBridge-discovery");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();

        String options = intent.getStringExtra(EXTRA_OPTIONS);
        try {
            JSONObject config = new JSONObject(options);
            sampleRate = config.getInt("sample_rate"); bufferMs = config.getInt("buffer_ms");
            if ((sampleRate != 44100 && sampleRate != 48000) || bufferMs < 100 || bufferMs > 2000)
                throw new IllegalArgumentException("无效的采样率或缓冲时长");
        } catch (Exception error) {
            updateNotification("音频设置无效"); stopStreaming(); stopSelf(); return START_NOT_STICKY;
        }
        if (options == null || !NativeBridge.nativeStart(options)) {
            updateNotification("连接参数无效");
            return START_NOT_STICKY;
        }
        NativeBridge.nativeSetDeviceVolume(intent.getIntExtra("volume", 35));
        running.set(true);
        captureError = null;
        try {
            if ("system".equals(source) && Build.VERSION.SDK_INT >= 29) startPlaybackCapture(intent);
            else if ("microphone".equals(source)) startMicrophoneCapture();
            else { toneRequested.set(true); startRecorder(); }
        } catch (Exception error) {
            captureError = "无法启动捕获：" + error.getMessage();
            updateNotification(captureError); stopStreaming(); stopSelf();
        }
        return START_NOT_STICKY;
    }

    @android.annotation.TargetApi(29)
    private void startPlaybackCapture(Intent intent) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("没有录音权限");
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        else data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (data == null || resultCode != Activity.RESULT_OK) throw new SecurityException("没有系统音频捕获权限");
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, data);
        final MediaProjection activeProjection = projection;
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                if (projection == activeProjection) { stopStreaming(); stopSelf(); }
            }
        }, new Handler(Looper.getMainLooper()));
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        AudioFormat format = format();
        int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        recorder = new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(captureCapacity(min, 2))
                .setAudioPlaybackCaptureConfig(config).build();
        recorderChannels = 2;
        startRecorder();
    }

    private void startMicrophoneCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("没有麦克风权限");
        }
        int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        recorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRate, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, captureCapacity(min, 2));
        recorderChannels = 2;
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, captureCapacity(min, 1));
            recorderChannels = 1;
        }
        startRecorder();
    }

    private AudioFormat format() {
        return new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
    }

    private int captureCapacity(int minimum, int channels) {
        return Math.max(Math.max(minimum * 2, 16384), sampleRate * channels * 2 * bufferMs / 1000);
    }

    private void startRecorder() {
        final AudioRecord input = recorder;
        if (input != null && input.getState() != AudioRecord.STATE_INITIALIZED)
            throw new IllegalStateException("设备不支持所选采样率，请尝试另一采样率");
        final int token = generation.get(), rate = sampleRate, channels = recorderChannels;
        captureThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            try {
                // Do not fill the queue with stale capture while network authentication runs.
                long timeout = SystemClock.elapsedRealtime() + 30000;
                while (running.get() && token == generation.get()) {
                    String state = new JSONObject(NativeBridge.nativeStatus()).optString("state");
                    if ("streaming".equals(state)) break;
                    if ("error".equals(state) || "stopped".equals(state)) return;
                    if (SystemClock.elapsedRealtime() >= timeout) throw new IllegalStateException("连接超时");
                    Thread.sleep(30);
                }
                if (!running.get() || token != generation.get()) return;
                if (input != null) input.startRecording();
                updateNotification("正在向 HomePod 播放 · 缓冲 " + bufferMs + " ms");
                byte[] bytes = new byte[rate / 50 * (input == null ? 2 : channels) * 2];
                int toneCursor = rate * 3;
                long next = System.nanoTime();
                while (running.get() && token == generation.get()) {
                    int count = input == null ? bytes.length : input.read(bytes, 0, bytes.length, AudioRecord.READ_BLOCKING);
                    if (!running.get() || token != generation.get()) break;
                    if (count < 0) throw new IllegalStateException("音频捕获失败（" + count + "）");
                    if (count == 0) { Thread.sleep(2); continue; }
                    int inputChannels = input == null ? 2 : channels;
                    count -= count % (inputChannels * 2);
                    byte[] packet = new byte[count / inputChannels * 2];
                    if (input != null) {
                        if (channels == 1) {
                            for (int s = 0, d = 0; s < count; s += 2) {
                                packet[d++] = bytes[s]; packet[d++] = bytes[s+1];
                                packet[d++] = bytes[s]; packet[d++] = bytes[s+1];
                            }
                        } else System.arraycopy(bytes, 0, packet, 0, count);
                    }
                    if (toneRequested.getAndSet(false)) toneCursor = 0;
                    // Exactly one producer: mix a fading test tone into the same frames.
                    // Never append extra frames to a live capture timeline.
                    for (int p = 0; p < packet.length && toneCursor < rate * 3; p += 4, toneCursor++) {
                        double fade = Math.min(1.0, Math.min(toneCursor / (rate * 0.05), (rate * 3 - toneCursor) / (rate * 0.1)));
                        double wave = (Math.sin(2 * Math.PI * 523.25 * toneCursor / rate) * 0.12
                                + Math.sin(2 * Math.PI * 659.25 * toneCursor / rate) * 0.08) * fade * 32767;
                        for (int c = 0; c < 2; c++) {
                            int pos = p + c * 2;
                            short original = (short) ((packet[pos] & 255) | (packet[pos+1] << 8));
                            short mixed = (short) Math.max(-32768, Math.min(32767, original * (1 - fade * 0.3) + wave));
                            packet[pos] = (byte) mixed; packet[pos+1] = (byte) (mixed >> 8);
                        }
                    }
                    if (!NativeBridge.nativePushPcm(packet)) throw new IllegalStateException("音频队列已关闭");
                    if (input == null) {
                        next += packet.length / 4L * 1_000_000_000L / rate;
                        long wait = next - System.nanoTime();
                        if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                        else if (wait < -100_000_000L) next = System.nanoTime();
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                if (token == generation.get() && running.get()) {
                    captureError = error.getMessage();
                    updateNotification("播放已停止：" + captureError);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (token == generation.get()) { stopStreaming(); stopSelf(); }
                    });
                }
            }
        }, "AirBridge-capture");
        captureThread.start();
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, AudioStreamingService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("AirBridge").setContentText(text).setOngoing(true).setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "断开", stopAction).build()).build();
    }

    private void updateNotification(String text) { getSystemService(NotificationManager.class).notify(19, notification(text)); }

    private void stopStreaming() {
        running.set(false);
        generation.incrementAndGet();
        toneRequested.set(false);
        AudioRecord oldRecorder = recorder; recorder = null;
        if (oldRecorder != null) { try { oldRecorder.stop(); } catch (Exception ignored) {} }
        Thread oldThread = captureThread; captureThread = null;
        if (oldThread != null && oldThread != Thread.currentThread()) {
            oldThread.interrupt();
            try { oldThread.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (oldRecorder != null) oldRecorder.release();
        MediaProjection oldProjection = projection; projection = null;
        if (oldProjection != null) oldProjection.stop();
        NativeBridge.nativeStop();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        multicastLock = null;
    }

    @Override public void onDestroy() { stopStreaming(); super.onDestroy(); }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
