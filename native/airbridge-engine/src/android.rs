use crate::{
    rtsp::Cancellation,
    session::{ProbeOptions, stream_external},
    volume::{Command, Control},
};
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JString},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jfloat, jint, jstring},
};
use serde_json::{Value, json};
use std::sync::{Arc, Mutex, OnceLock, atomic::{AtomicU32, AtomicU64, Ordering}};
use crate::audio_buffer::AudioBuffer;

struct Running {
    cancel: Cancellation,
    buffer: Arc<Mutex<AudioBuffer>>,
    status: Arc<Mutex<Value>>,
    gain: Arc<AtomicU32>,
    volume: Control,
    worker: Option<std::thread::JoinHandle<()>>,
}

static RUNNING: OnceLock<Mutex<Option<Running>>> = OnceLock::new();
static VOLUME_SEQUENCE: AtomicU64 = AtomicU64::new(1);

fn slot() -> &'static Mutex<Option<Running>> { RUNNING.get_or_init(|| Mutex::new(None)) }

fn stop_running(mut running: Running) {
    running.cancel.cancel();
    if let Some(worker) = running.worker.take() { let _ = worker.join(); }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_airbridge_mobile_NativeBridge_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    options: JString,
) -> jboolean {
    let text: String = match env.get_string(&options) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };
    let mut parsed: ProbeOptions = match serde_json::from_str(&text) {
        Ok(value) => value,
        Err(_) => return JNI_FALSE,
    };
    parsed.source = "external".into();
    parsed.duration_ms = 0;
    if parsed.validate_external().is_err() { return JNI_FALSE; }

    let old = slot().lock().unwrap().take();
    if let Some(old) = old { stop_running(old); }
    let cancel = Cancellation::default();
    let child_cancel = cancel.clone();
    let buffer = Arc::new(Mutex::new(AudioBuffer::new(parsed.sample_rate, parsed.buffer_ms)));
    let receiver = buffer.clone();
    let gain = Arc::new(AtomicU32::new(parsed.gain.to_bits()));
    let child_gain = gain.clone();
    let volume = Control::default();
    let child_volume = volume.clone();
    let status = Arc::new(Mutex::new(json!({"state":"connecting", "sample_rate": parsed.sample_rate,
        "buffer_ms": parsed.buffer_ms, "requested_latency_ms": parsed.latency_ms, "codec": parsed.codec,
        "pcm_kbps": parsed.sample_rate * 16 * 2 / 1000, "receivers": {}})));
    let child_status = status.clone();
    let worker = std::thread::spawn(move || {
        let events = |event: Value| {
            let mut snapshot = child_status.lock().unwrap();
            if event["event"] == "negotiated" {
                if let Some(host) = event["host"].as_str() { snapshot["receivers"][host] = event.clone(); }
            }
            let state = match event.get("event").and_then(Value::as_str) {
                Some("streaming") => "streaming",
                Some("stopped") => "stopped",
                Some("error") => "error",
                _ => "connecting",
            };
            if matches!(event.get("event").and_then(Value::as_str), Some("streaming" | "stopped" | "error")) {
                snapshot["state"] = json!(state);
                snapshot["event"] = event;
            }
        };
        if let Err(error) = stream_external(parsed, child_cancel, child_gain, child_volume, receiver, events) {
            let mut snapshot = child_status.lock().unwrap();
            snapshot["state"] = json!("error");
            snapshot["message"] = json!(error.to_string());
        }
    });
    *slot().lock().unwrap() = Some(Running { cancel, buffer, status, gain, volume, worker: Some(worker) });
    JNI_TRUE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_airbridge_mobile_NativeBridge_nativePushPcm(
    env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jboolean {
    let bytes = match env.convert_byte_array(data) { Ok(value) => value, Err(_) => return JNI_FALSE };
    let guard = slot().lock().unwrap();
    match guard.as_ref().map(|running| running.buffer.lock().unwrap().push(&bytes)) {
        Some(true) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_airbridge_mobile_NativeBridge_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    let running = slot().lock().unwrap().take();
    if let Some(running) = running { stop_running(running); }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_airbridge_mobile_NativeBridge_nativeSetGain(
    _env: JNIEnv,
    _class: JClass,
    gain: jfloat,
) {
    if (0.0..=1.0).contains(&gain) {
        if let Some(running) = slot().lock().unwrap().as_ref() {
            running.gain.store(gain.to_bits(), Ordering::Release);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_airbridge_mobile_NativeBridge_nativeSetDeviceVolume(
    _env: JNIEnv,
    _class: JClass,
    percent: jint,
) {
    if (0..=100).contains(&percent) {
        if let Some(running) = slot().lock().unwrap().as_ref() {
            running.volume.set(Command {
                sequence: VOLUME_SEQUENCE.fetch_add(1, Ordering::Relaxed),
                percent: percent as u8,
            });
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_airbridge_mobile_NativeBridge_nativeStatus(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let value = slot().lock().unwrap().as_ref()
        .map(|running| {
            let mut value = running.status.lock().unwrap().clone();
            value["buffer"] = running.buffer.lock().unwrap().metrics();
            value.to_string()
        })
        .unwrap_or_else(|| "{\"state\":\"stopped\"}".into());
    env.new_string(value).map(|value| value.into_raw()).unwrap_or(std::ptr::null_mut())
}
