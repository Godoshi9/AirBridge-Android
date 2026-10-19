package com.airbridge.mobile;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.net.*;
import java.util.*;

/** Runs on the signed release APK. No live HomePod or microphone is used. */
public final class BufferSettingsTest extends Instrumentation {
    private MainActivity screen;
    private final List<Spinner> spinners = new ArrayList<>();
    private SeekBar buffer;
    private void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void visit(View view) {
        if (view instanceof SeekBar && "缓冲时长".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) buffer = (SeekBar) view;
        if (view instanceof Spinner) spinners.add((Spinner) view);
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) visit(group.getChildAt(i)); }
    }
    private void launch() {
        screen = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync(); spinners.clear();
        runOnMainSync(() -> visit(screen.getWindow().getDecorView()));
    }
    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            getTargetContext().getSharedPreferences("audio_settings", Context.MODE_PRIVATE).edit().clear().commit();
            launch();
            check(buffer != null && buffer.getProgress() == 8, "default 500 ms missing");
            check(spinners.size() == 2, "audio format selectors missing");
            runOnMainSync(() -> { buffer.setProgress(38); spinners.get(0).setSelection(1); spinners.get(1).setSelection(2); });
            waitForIdleSync();
            AirPlayDevice peer = new AirPlayDevice("Loopback test", InetAddress.getByName("127.0.0.1"), 7000, Collections.emptyMap());
            JSONObject config = screen.streamOptions(Collections.singletonList(peer));
            check(config.getInt("buffer_ms") == 2000 && config.getInt("latency_ms") == 2000, "buffer not passed to both local queue and receiver request");
            check(config.getInt("sample_rate") == 44100 && config.getString("codec").equals("alac"), "format not passed to engine");
            runOnMainSync(() -> screen.finish()); waitForIdleSync(); launch();
            check(buffer.getProgress() == 38 && spinners.get(0).getSelectedItemPosition() == 1 && spinners.get(1).getSelectedItemPosition() == 2, "settings did not survive activity restart");
            // Hold a loopback connection during authentication to inspect the real JNI queue.
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                config.getJSONArray("peers").getJSONObject(0).put("port", server.getLocalPort());
                check(NativeBridge.nativeStart(config.toString()), "JNI rejected valid settings");
                check(NativeBridge.nativePushPcm(new byte[44100 * 4]), "JNI rejected one second of stereo PCM");
                JSONObject status = new JSONObject(NativeBridge.nativeStatus());
                check(status.getJSONObject("buffer").getInt("target_ms") == 2000, "native queue target differs from UI");
                check(status.getJSONObject("buffer").getInt("queued_ms") == 1000, "native queue duration is wrong at 44.1 kHz");
                check(status.getInt("sample_rate") == 44100, "native sample rate differs from UI");
                check(!NativeBridge.nativePushPcm(new byte[3]), "partial stereo frame accepted");
                NativeBridge.nativeStop();
            }
            config.put("buffer_ms", 0);
            check(!NativeBridge.nativeStart(config.toString()), "invalid zero buffer accepted");
            config.put("buffer_ms", 2050);
            check(!NativeBridge.nativeStart(config.toString()), "oversized buffer accepted");
            runOnMainSync(() -> { buffer.setProgress(8); spinners.get(0).setSelection(0); spinners.get(1).setSelection(0); });
            waitForIdleSync();
            report.putString("stream", "PASS: defaults, settings controls, persistence, UI-to-engine settings, JNI queue duration, malformed frames, validation\n");
            finish(Activity.RESULT_OK, report);
        } catch (Throwable failure) {
            report.putString("stream", android.util.Log.getStackTraceString(failure)); finish(Activity.RESULT_CANCELED, report);
        } finally { NativeBridge.nativeStop(); }
    }
}
