//! Bounded, frame-aligned PCM queue with prefill and click-free recovery.
use std::collections::VecDeque;
use serde_json::{Value, json};

pub struct AudioBuffer {
    frames: VecDeque<[i16; 2]>,
    rate: usize,
    target: usize,
    capacity: usize,
    playing: bool,
    envelope: f32,
    last: [f32; 2],
    underruns: u64,
    overflows: u64,
    dropped: u64,
}

impl AudioBuffer {
    pub fn new(rate: u32, buffer_ms: u32) -> Self {
        let rate = rate as usize;
        let target = rate * buffer_ms as usize / 1000;
        let capacity = target + rate / 2;
        Self { frames: VecDeque::with_capacity(capacity), rate, target, capacity,
            playing: false, envelope: 0.0, last: [0.0; 2], underruns: 0, overflows: 0, dropped: 0 }
    }

    pub fn push(&mut self, pcm: &[u8]) -> bool {
        // Reject incomplete stereo frames instead of swapping L/R or sample bytes.
        if pcm.is_empty() || pcm.len() % 4 != 0 || pcm.len() / 4 > self.capacity { return false; }
        let incoming = pcm.len() / 4;
        if self.frames.len() + incoming > self.capacity {
            // A long sender stall must not accumulate unlimited stale audio. Recover
            // to the selected target, and crossfade at the next packet boundary.
            let discard = (self.frames.len() + incoming).saturating_sub(self.target.max(incoming));
            self.frames.drain(..discard.min(self.frames.len()));
            self.dropped += discard as u64;
            self.overflows += 1;
            self.playing = false;
            self.envelope = 0.0;
        }
        self.frames.extend(pcm.chunks_exact(4).map(|b| [
            i16::from_le_bytes([b[0], b[1]]), i16::from_le_bytes([b[2], b[3]])]));
        true
    }

    pub fn packet(&mut self, out: &mut [u8], gain: f32) {
        let count = out.len() / 4;
        if !self.playing && self.frames.len() >= self.target.max(count) {
            self.playing = true;
            self.envelope = 0.0;
        }
        if self.playing && self.frames.len() < count {
            self.playing = false;
            self.envelope = 0.0;
            self.underruns += 1;
        }
        let fade_frames = (self.rate / 200).max(1); // 5 ms, shared by both channels
        let initial = self.last;
        for (i, bytes) in out.chunks_exact_mut(4).enumerate() {
            let mut frame = [0.0f32; 2];
            if self.playing {
                let sample = self.frames.pop_front().unwrap();
                self.envelope = (self.envelope + 1.0 / fade_frames as f32).min(1.0);
                for c in 0..2 {
                    frame[c] = sample[c] as f32 * gain * self.envelope + initial[c] * (1.0 - self.envelope);
                }
            } else {
                let fade = (1.0 - (i + 1) as f32 / fade_frames as f32).max(0.0);
                for c in 0..2 { frame[c] = initial[c] * fade; }
            }
            for c in 0..2 {
                let value = frame[c].round().clamp(i16::MIN as f32, i16::MAX as f32) as i16;
                bytes[c * 2..c * 2 + 2].copy_from_slice(&value.to_be_bytes());
                self.last[c] = value as f32;
            }
        }
    }

    pub fn metrics(&self) -> Value {
        json!({"target_ms": self.target * 1000 / self.rate,
            "queued_ms": self.frames.len() * 1000 / self.rate,
            "buffering": !self.playing, "underruns": self.underruns,
            "overflows": self.overflows, "dropped_frames": self.dropped})
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn data(frames: usize) -> Vec<u8> {
        (0..frames).flat_map(|_| [0x10, 0x27, 0xf0, 0xd8]).collect() // +10000 L, -10000 R
    }
    #[test]
    fn chosen_duration_gates_playback_at_both_rates() {
        for rate in [44100, 48000] { for ms in [100, 500, 1000, 2000] {
            let mut b = AudioBuffer::new(rate, ms);
            let target = (rate * ms / 1000) as usize;
            b.push(&data(target - 1));
            let mut out = vec![0; 352 * 4];
            b.packet(&mut out, 1.0);
            assert!(out.iter().all(|v| *v == 0));
            assert_eq!(b.frames.len(), target - 1);
            b.push(&data(1)); b.packet(&mut out, 1.0);
            assert!(b.playing);
            assert_eq!(&out[out.len()-4..], &[0x27, 0x10, 0xd8, 0xf0]);
        }}
    }
    #[test]
    fn underrun_refills_and_fades_without_losing_stereo_alignment() {
        let mut b = AudioBuffer::new(48000, 100);
        b.push(&data(4800));
        let mut out = vec![0; 352 * 4];
        for _ in 0..14 { b.packet(&mut out, 1.0); }
        assert_eq!(b.underruns, 1);
        assert!(!b.playing);
        assert_eq!(&out[out.len()-4..], &[0; 4]);
        b.push(&data(4800 - b.frames.len())); b.packet(&mut out, 1.0);
        assert!(b.playing);
        assert_eq!(&out[out.len()-4..], &[0x27, 0x10, 0xd8, 0xf0]);
    }
    #[test]
    fn overflow_is_bounded_and_observable_and_invalid_frames_rejected() {
        let mut b = AudioBuffer::new(48000, 500);
        assert!(!b.push(&[1, 2, 3]));
        for _ in 0..100 { b.push(&data(960)); }
        assert!(b.frames.len() <= b.capacity);
        assert!(b.overflows > 0 && b.dropped > 0);
    }
    #[test]
    fn steady_jitter_remains_bit_exact_after_initial_fade() {
        let mut b = AudioBuffer::new(48000, 500);
        b.push(&data(24000));
        let mut out = vec![0; 352 * 4];
        for i in 0..1000 {
            if i % 3 == 0 { b.push(&data(352 * 3)); }
            b.packet(&mut out, 1.0);
            if i > 0 { for f in out.chunks_exact(4) { assert_eq!(f, [0x27, 0x10, 0xd8, 0xf0]); } }
        }
        assert_eq!(b.underruns, 0); assert_eq!(b.overflows, 0);
    }
}
