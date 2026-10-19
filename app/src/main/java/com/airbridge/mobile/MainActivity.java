package com.airbridge.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 44;
    private static final int AUDIO_PERMISSION = 45;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, AirPlayDevice> devices = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock discoveryLock;
    private LinearLayout deviceList;
    private TextView scanLabel, statusChip, primaryButton, sourceSystem, sourceTest;
    private SeekBar volume;
    private DeviceGroup selected;
    private String selectedKey;
    private String sourceMode;
    private SharedPreferences preferences;
    private int bufferMs, sampleRate;
    private String codec;
    private TextView audioDetails;

    private static final class DeviceGroup {
        final String key, name, model;
        final List<AirPlayDevice> members;
        DeviceGroup(String key, String name, String model, List<AirPlayDevice> members) {
            this.key = key; this.name = name; this.model = model; this.members = members;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(11, 16, 32));
        getWindow().setNavigationBarColor(Color.rgb(11, 16, 32));
        sourceMode = Build.VERSION.SDK_INT >= 29 ? "system" : "microphone";
        preferences = getSharedPreferences("audio_settings", MODE_PRIVATE);
        bufferMs = Math.max(100, Math.min(2000, preferences.getInt("buffer_ms", 500)));
        sampleRate = preferences.getInt("sample_rate", 48000) == 44100 ? 44100 : 48000;
        codec = preferences.getString("codec", "auto");
        buildUi();
        beginDiscovery();
        handler.post(statusPoll);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 46);
    }

    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(Color.rgb(11, 16, 32));
        root.setPadding(dp(20), dp(18), dp(20), dp(28));

        LinearLayout top = row();
        TextView logo = text("≋", 28, Color.rgb(174, 139, 255), true);
        logo.setGravity(Gravity.CENTER); logo.setBackground(round(Color.rgb(42, 36, 76), 16, Color.rgb(97, 78, 157)));
        top.addView(logo, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout brand = column(); brand.setPadding(dp(12), 0, 0, 0);
        brand.addView(text("AirBridge", 22, Color.WHITE, true));
        brand.addView(text("HomePod 音频桥", 13, Color.rgb(133, 146, 177), false));
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        statusChip = chip("未连接", false); top.addView(statusChip);
        root.addView(top);

        TextView heading = text("附近的设备", 28, Color.WHITE, true);
        LinearLayout.LayoutParams hp = margins(-1, -2, 0, 30, 0, 4); root.addView(heading, hp);
        LinearLayout scanRow = row();
        scanLabel = text("正在查找同一 Wi‑Fi 下的 HomePod…", 14, Color.rgb(133, 146, 177), false);
        scanRow.addView(scanLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView refresh = text("重新扫描", 14, Color.rgb(174, 139, 255), true);
        refresh.setPadding(dp(14), dp(9), dp(14), dp(9)); refresh.setBackground(round(Color.rgb(28, 34, 54), 14, Color.rgb(54, 65, 94)));
        refresh.setOnClickListener(v -> beginDiscovery()); scanRow.addView(refresh);
        root.addView(scanRow);

        deviceList = column();
        LinearLayout.LayoutParams dpList = margins(-1, -2, 0, 14, 0, 0); root.addView(deviceList, dpList);
        renderDevices();

        root.addView(sectionTitle("音频来源"), margins(-1, -2, 0, 24, 0, 10));
        LinearLayout sources = row();
        sourceSystem = sourceButton(Build.VERSION.SDK_INT >= 29 ? "系统音频" : "麦克风", true);
        sourceTest = sourceButton("仅测试音频", false);
        sources.addView(sourceSystem, new LinearLayout.LayoutParams(0, dp(54), 1));
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(0, dp(54), 1); testParams.leftMargin = dp(10);
        sources.addView(sourceTest, testParams); root.addView(sources);
        TextView sourceNote = text(Build.VERSION.SDK_INT >= 29
                ? "连接时 Android 会显示一次系统录音授权；受保护内容和禁止捕获的应用不会被录制。"
                : "Android 9 不支持系统播放捕获，因此使用麦克风；测试音频不需要录音权限。",
                12, Color.rgb(112, 125, 154), false);
        root.addView(sourceNote, margins(-1, -2, 0, 8, 0, 22));

        root.addView(sectionTitle("缓冲与音质"));
        TextView bufferValue = text(bufferLabel(), 15, Color.rgb(190, 162, 255), true);
        root.addView(bufferValue, margins(-1, -2, 0, 12, 0, 0));
        SeekBar bufferBar = new SeekBar(this);
        bufferBar.setContentDescription("缓冲时长");
        bufferBar.setMax(38); bufferBar.setProgress((bufferMs - 100) / 50);
        bufferBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(156, 111, 255)));
        bufferBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.rgb(190, 162, 255)));
        root.addView(bufferBar, new LinearLayout.LayoutParams(-1, dp(48)));
        bufferBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                bufferMs = 100 + progress * 50;
                bufferValue.setText(bufferLabel());
                preferences.edit().putInt("buffer_ms", bufferMs).apply();
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        root.addView(text("100–2000 ms · 默认 500 ms。更长的缓冲可容忍更大的音频抖动，也会增加延迟。修改后下次连接生效。", 12, Color.rgb(133, 146, 177), false));
        root.addView(text("采样率 · 16 位双声道", 13, Color.rgb(162, 173, 200), true), margins(-1, -2, 0, 14, 0, 6));
        root.addView(choice(new String[]{"48 kHz · PCM 1536 kbps", "44.1 kHz · PCM 1411.2 kbps"},
                sampleRate == 48000 ? 0 : 1, index -> {
                    sampleRate = index == 0 ? 48000 : 44100;
                    preferences.edit().putInt("sample_rate", sampleRate).apply();
                }));
        root.addView(text("传输格式", 13, Color.rgb(162, 173, 200), true), margins(-1, -2, 0, 12, 0, 6));
        String[] codecs = {"auto", "pcm", "alac"};
        root.addView(choice(new String[]{"自动 · 无损", "PCM · 不压缩", "ALAC · 无损压缩"},
                "pcm".equals(codec) ? 1 : "alac".equals(codec) ? 2 : 0, index -> {
                    codec = codecs[index]; preferences.edit().putString("codec", codec).apply();
                }));
        root.addView(text("以上为 PCM 原始音频数据率，不含网络开销。ALAC 的传输码率随内容变化，解码后的音频精度相同。", 12, Color.rgb(133, 146, 177), false), margins(-1, -2, 0, 8, 0, 8));
        audioDetails = text("连接后显示实际格式、缓冲队列和欠载次数", 12, Color.rgb(144, 166, 180), false);
        audioDetails.setPadding(dp(12), dp(12), dp(12), dp(12));
        audioDetails.setBackground(round(Color.rgb(18, 24, 41), 14, Color.rgb(37, 46, 70)));
        root.addView(audioDetails, margins(-1, -2, 0, 0, 0, 22));

        root.addView(sectionTitle("HomePod 音量"));
        LinearLayout volumeRow = row();
        TextView low = text("◖", 18, Color.rgb(162, 173, 200), false); volumeRow.addView(low);
        volume = new SeekBar(this); volume.setMax(100); volume.setProgress(35);
        volume.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(156, 111, 255)));
        volumeRow.addView(volume, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView amount = text("35%", 14, Color.rgb(190, 162, 255), true); amount.setMinWidth(dp(46)); amount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        volumeRow.addView(amount); root.addView(volumeRow);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { amount.setText(String.format(Locale.getDefault(), "%d%%", value)); if (user) sendVolume(value); }
            public void onStartTrackingTouch(SeekBar bar) {} public void onStopTrackingTouch(SeekBar bar) {}
        });

        LinearLayout actions = row();
        TextView test = actionButton("播放测试音频", false); test.setOnClickListener(v -> sendService(AudioStreamingService.ACTION_TONE));
        actions.addView(test, new LinearLayout.LayoutParams(0, dp(54), 1));
        primaryButton = actionButton("连接并播放", true); primaryButton.setOnClickListener(v -> onPrimary());
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(0, dp(54), 1); primaryParams.leftMargin = dp(10);
        actions.addView(primaryButton, primaryParams); root.addView(actions, margins(-1, -2, 0, 18, 0, 0));

        TextView footer = text("AirBridge 1.1.0 · 音频与设置仅保存在本机", 12, Color.rgb(91, 105, 135), false);
        footer.setGravity(Gravity.CENTER); root.addView(footer, margins(-1, -2, 0, 22, 0, 0));

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(11, 16, 32)); scroll.addView(root);
        setContentView(scroll);

        sourceSystem.setOnClickListener(v -> chooseSource(Build.VERSION.SDK_INT >= 29 ? "system" : "microphone"));
        sourceTest.setOnClickListener(v -> chooseSource("test"));
    }

    private void chooseSource(String mode) {
        sourceMode = mode;
        boolean normal = !"test".equals(mode);
        sourceSystem.setBackground(sourceBackground(normal)); sourceSystem.setTextColor(normal ? Color.WHITE : Color.rgb(150, 163, 193));
        sourceTest.setBackground(sourceBackground(!normal)); sourceTest.setTextColor(!normal ? Color.WHITE : Color.rgb(150, 163, 193));
    }

    private void onPrimary() {
        String state = currentState();
        if ("streaming".equals(state) || "connecting".equals(state)) { sendService(AudioStreamingService.ACTION_STOP); return; }
        if (selected == null) { toast("请先选择一台 HomePod 或立体声组"); return; }
        if ("system".equals(sourceMode)) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            else requestSystemCapture();
        } else if ("microphone".equals(sourceMode) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
        } else startStreaming(null, Activity.RESULT_CANCELED);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAPTURE_REQUEST && resultCode == RESULT_OK && data != null) startStreaming(data, resultCode);
        else if (requestCode == CAPTURE_REQUEST) toast("已取消系统音频捕获");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == AUDIO_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if ("system".equals(sourceMode)) requestSystemCapture(); else startStreaming(null, RESULT_CANCELED);
        }
    }

    private void requestSystemCapture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST);
    }

    private void startStreaming(Intent projectionData, int resultCode) {
        try {
            JSONObject options = streamOptions(selected.members);
            Intent service = new Intent(this, AudioStreamingService.class).setAction(AudioStreamingService.ACTION_START)
                    .putExtra(AudioStreamingService.EXTRA_OPTIONS, options.toString())
                    .putExtra(AudioStreamingService.EXTRA_SOURCE, sourceMode)
                    .putExtra("volume", volume.getProgress())
                    .putExtra(AudioStreamingService.EXTRA_RESULT_CODE, resultCode);
            if (projectionData != null) service.putExtra(AudioStreamingService.EXTRA_RESULT_DATA, projectionData);
            startForegroundService(service);
        } catch (Exception error) { toast("无法开始播放：" + error.getMessage()); }
    }

    JSONObject streamOptions(List<AirPlayDevice> members) throws Exception {
        JSONObject options = new JSONObject(); JSONArray peers = new JSONArray();
        for (AirPlayDevice member : members) peers.put(member.peerJson());
        options.put("peers", peers); options.put("source", "external"); options.put("duration_ms", 0);
        options.put("buffer_ms", bufferMs); options.put("codec", codec);
        options.put("latency_ms", Math.max(150, bufferMs)); options.put("gain", 1.0); options.put("sample_rate", sampleRate);
        options.put("timing", members.size() > 1 ? "ptp" : "ntp");
        if (members.size() > 1) options.put("group_id", members.get(0).groupId());
        return options;
    }

    private void sendVolume(int value) {
        String state = currentState();
        if (!"streaming".equals(state) && !"connecting".equals(state)) return;
        Intent intent = new Intent(this, AudioStreamingService.class).setAction(AudioStreamingService.ACTION_VOLUME).putExtra("volume", value);
        startService(intent);
    }
    private void sendService(String action) { startService(new Intent(this, AudioStreamingService.class).setAction(action)); }

    private String currentState() {
        try { return new JSONObject(NativeBridge.nativeStatus()).optString("state", "stopped"); }
        catch (Throwable ignored) { return "stopped"; }
    }
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            String state = currentState();
            try {
                JSONObject snapshot = new JSONObject(NativeBridge.nativeStatus());
                JSONObject queue = snapshot.optJSONObject("buffer");
                JSONObject receivers = snapshot.optJSONObject("receivers");
                if (queue != null) {
                    String actual = "等待协商";
                    if (receivers != null && receivers.length() > 0) {
                        StringBuilder details = new StringBuilder();
                        Iterator<String> keys = receivers.keys();
                        while (keys.hasNext()) {
                            JSONObject item = receivers.getJSONObject(keys.next());
                            if (details.length() > 0) details.append(" / ");
                            details.append(item.optString("codec").toUpperCase(Locale.ROOT));
                        }
                        actual = details.toString();
                    }
                    audioDetails.setText(String.format(Locale.getDefault(),
                        "当前连接：%s · %d Hz / 16 位 / 双声道\n本机缓冲：%d / %d ms%s\n欠载 %d 次 · 队列溢出 %d 次 · HomePod 请求缓冲 %d ms",
                        actual, snapshot.optInt("sample_rate"), queue.optInt("queued_ms"), queue.optInt("target_ms"),
                        queue.optBoolean("buffering") ? " · 正在蓄积" : "", queue.optInt("underruns"), queue.optInt("overflows"), snapshot.optInt("requested_latency_ms")));
                    if ("error".equals(state)) audioDetails.append("\n" + snapshot.optString("message"));
                } else audioDetails.setText("连接后显示实际格式、缓冲队列和欠载次数");
            } catch (Exception ignored) {}
            if ("streaming".equals(state)) { statusChip.setText("正在播放"); statusChip.setBackground(round(Color.rgb(22, 64, 61), 18, Color.rgb(41, 117, 104))); primaryButton.setText("断开连接"); }
            else if ("connecting".equals(state)) { statusChip.setText("正在连接"); statusChip.setBackground(round(Color.rgb(64, 54, 32), 18, Color.rgb(120, 94, 45))); primaryButton.setText("取消"); }
            else if ("error".equals(state)) { statusChip.setText("连接失败"); statusChip.setBackground(round(Color.rgb(75, 36, 45), 18, Color.rgb(139, 58, 75))); primaryButton.setText("重新连接"); }
            else { statusChip.setText("未连接"); statusChip.setBackground(round(Color.rgb(28, 34, 54), 18, Color.rgb(54, 65, 94))); primaryButton.setText("连接并播放"); }
            handler.postDelayed(this, 800);
        }
    };

    private void beginDiscovery() {
        devices.clear(); resolveQueue.clear(); resolving = false; selected = null; selectedKey = null; renderDevices();
        if (discovery != null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (discoveryLock != null && discoveryLock.isHeld()) discoveryLock.release();
        discoveryLock = wifi.createMulticastLock("AirBridge-NSD"); discoveryLock.setReferenceCounted(false); discoveryLock.acquire();
        nsd = getSystemService(NsdManager.class);
        discovery = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) { runOnUiThread(() -> scanLabel.setText(R.string.scanning)); }
            public void onServiceFound(NsdServiceInfo service) { synchronized (resolveQueue) { resolveQueue.offer(service); } resolveNext(); }
            public void onServiceLost(NsdServiceInfo service) {}
            public void onDiscoveryStopped(String type) {}
            public void onStartDiscoveryFailed(String type, int code) { runOnUiThread(() -> scanLabel.setText("扫描启动失败，点此重试")); }
            public void onStopDiscoveryFailed(String type, int code) {}
        };
        try { nsd.discoverServices("_airplay._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (Exception error) { scanLabel.setText("无法启动局域网扫描"); }
    }

    private void resolveNext() {
        synchronized (resolveQueue) {
            if (resolving) return;
            NsdServiceInfo item = resolveQueue.poll(); if (item == null) return; resolving = true;
            try { nsd.resolveService(item, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo service, int code) { resolving = false; handler.postDelayed(() -> resolveNext(), 120); }
                public void onServiceResolved(NsdServiceInfo service) {
                    if (service.getHost() != null) {
                        AirPlayDevice device = new AirPlayDevice(service.getServiceName(), service.getHost(), service.getPort(), service.getAttributes());
                        synchronized (devices) { devices.put(device.host.getHostAddress() + ":" + device.port, device); }
                        runOnUiThread(() -> renderDevices());
                    }
                    resolving = false; handler.postDelayed(() -> resolveNext(), 120);
                }
            }); } catch (Exception error) { resolving = false; handler.postDelayed(() -> resolveNext(), 120); }
        }
    }

    private List<DeviceGroup> groups() {
        Map<String, List<AirPlayDevice>> grouped = new LinkedHashMap<>();
        synchronized (devices) {
            for (AirPlayDevice device : devices.values()) {
                String key = device.groupId().isEmpty() ? "device:" + device.host.getHostAddress() : "stereo:" + device.groupId();
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(device);
            }
        }
        List<DeviceGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<AirPlayDevice>> entry : grouped.entrySet()) {
            List<AirPlayDevice> members = entry.getValue(); members.sort((a, b) -> Boolean.compare(b.isLeader(), a.isLeader()));
            AirPlayDevice first = members.get(0); boolean stereo = entry.getKey().startsWith("stereo:") && members.size() > 1;
            result.add(new DeviceGroup(entry.getKey(), stereo ? first.groupName() : first.name,
                    stereo ? "HomePod 立体声组 · " + members.size() + " 台" : first.model(), members));
        }
        return result;
    }

    private void renderDevices() {
        if (deviceList == null) return; deviceList.removeAllViews(); List<DeviceGroup> groups = groups();
        scanLabel.setText(groups.isEmpty() ? "正在查找同一 Wi‑Fi 下的 HomePod…" : "发现 " + groups.size() + " 个可用输出");
        if (groups.isEmpty()) {
            TextView empty = text("还没有发现设备\n请确认手机和 HomePod 在同一个 Wi‑Fi", 15, Color.rgb(126, 140, 170), false);
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20), dp(34), dp(20), dp(34)); empty.setBackground(round(Color.rgb(18, 24, 41), 20, Color.rgb(37, 46, 70)));
            deviceList.addView(empty, margins(-1, -2, 0, 18, 0, 0)); return;
        }
        for (DeviceGroup group : groups) {
            if (group.key.equals(selectedKey)) selected = group;
            LinearLayout card = row(); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(18), dp(18), dp(18), dp(18));
            boolean active = group.key.equals(selectedKey); card.setBackground(round(active ? Color.rgb(41, 35, 67) : Color.rgb(18, 24, 41), 20, active ? Color.rgb(143, 101, 236) : Color.rgb(37, 46, 70)));
            TextView icon = text(group.members.size() > 1 ? "◉◉" : "◉", 21, Color.rgb(184, 158, 255), true); icon.setGravity(Gravity.CENTER); card.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));
            LinearLayout info = column(); info.addView(text(group.name, 17, Color.WHITE, true)); info.addView(text(group.model, 13, Color.rgb(126, 140, 170), false));
            card.addView(info, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(text(active ? "✓" : "›", 24, active ? Color.rgb(179, 145, 255) : Color.rgb(105, 119, 149), true));
            card.setOnClickListener(v -> { selectedKey = group.key; selected = group; renderDevices(); });
            deviceList.addView(card, margins(-1, -2, 0, 12, 0, 0));
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statusPoll);
        if (discovery != null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        if (discoveryLock != null && discoveryLock.isHeld()) discoveryLock.release();
        super.onDestroy();
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private String bufferLabel() { return String.format(Locale.getDefault(), "缓冲时长：%d ms", bufferMs); }
    private Spinner choice(String[] labels, int selectedIndex, java.util.function.IntConsumer changed) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convert, parent);
                view.setTextColor(Color.WHITE); view.setTextSize(14); view.setPadding(dp(14), dp(12), dp(14), dp(12)); return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(selectedIndex);
        spinner.setBackground(round(Color.rgb(25, 32, 52), 12, Color.rgb(48, 58, 84)));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int index, long id) { changed.accept(index); }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        return spinner;
    }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String value, int sp, int color, boolean bold) { TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }
    private TextView chip(String value, boolean active) { TextView view = text(value, 12, active ? Color.WHITE : Color.rgb(151, 164, 193), true); view.setPadding(dp(13), dp(8), dp(13), dp(8)); view.setBackground(round(Color.rgb(28, 34, 54), 18, Color.rgb(54, 65, 94))); return view; }
    private TextView sectionTitle(String value) { return text(value, 16, Color.rgb(218, 224, 239), true); }
    private TextView sourceButton(String value, boolean selected) { TextView view = text(value, 15, selected ? Color.WHITE : Color.rgb(150, 163, 193), true); view.setGravity(Gravity.CENTER); view.setBackground(sourceBackground(selected)); return view; }
    private GradientDrawable sourceBackground(boolean selected) { return round(selected ? Color.rgb(55, 43, 89) : Color.rgb(18, 24, 41), 17, selected ? Color.rgb(151, 106, 245) : Color.rgb(37, 46, 70)); }
    private TextView actionButton(String value, boolean primary) { TextView view = text(value, 15, primary ? Color.WHITE : Color.rgb(206, 215, 235), true); view.setGravity(Gravity.CENTER); view.setBackground(round(primary ? Color.rgb(126, 82, 230) : Color.rgb(25, 32, 52), 17, primary ? Color.rgb(165, 126, 248) : Color.rgb(48, 58, 84))); return view; }
    private GradientDrawable round(int color, int radius, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke); return d; }
    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
