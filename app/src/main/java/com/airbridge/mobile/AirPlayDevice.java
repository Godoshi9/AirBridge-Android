package com.airbridge.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class AirPlayDevice {
    String name;
    InetAddress host;
    int port;
    final Map<String, byte[]> attributes;

    AirPlayDevice(String name, InetAddress host, int port, Map<String, byte[]> attributes) {
        this.name = name.replace("._airplay._tcp.local.", "");
        this.host = host;
        this.port = port == 0 ? 7000 : port;
        this.attributes = new HashMap<>(attributes);
    }

    String text(String key) {
        byte[] value = attributes.get(key);
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }

    String groupId() { return text("tsid"); }
    String groupName() { String value = text("gpn"); return value.isEmpty() ? name : value; }
    boolean isLeader() { return "1".equals(text("igl")); }
    String model() { String value = text("model"); return value.isEmpty() ? "AirPlay 音响" : value; }

    JSONObject peerJson() throws Exception {
        JSONObject peer = new JSONObject();
        peer.put("host", host.getHostAddress());
        peer.put("port", port);
        JSONArray codecs = new JSONArray();
        String list = text("cn");
        if (!list.isEmpty()) for (String token : list.split(",")) {
            try { codecs.put(Integer.parseInt(token.trim())); } catch (NumberFormatException ignored) {}
        }
        peer.put("codecs", codecs);
        return peer;
    }
}
