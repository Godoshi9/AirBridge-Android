package com.airbridge.mobile;

public final class NativeBridge {
    static { System.loadLibrary("airbridge_engine"); }
    private NativeBridge() {}
    public static native boolean nativeStart(String optionsJson);
    public static native boolean nativePushPcm(byte[] pcm);
    public static native void nativeStop();
    public static native void nativeSetGain(float gain);
    public static native void nativeSetDeviceVolume(int percent);
    public static native String nativeStatus();
}
