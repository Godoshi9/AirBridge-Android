pub mod auth;
pub mod clock;
pub mod crypto;
pub mod rtp;
pub mod rtsp;
pub mod session;
pub mod audio_buffer;

#[cfg(windows)]
pub mod wasapi;

#[cfg(windows)]
pub mod live;

#[cfg(windows)]
pub mod credentials;

#[cfg(not(windows))]
#[path = "credentials_portable.rs"]
pub mod credentials;

#[cfg(target_os = "android")]
pub mod android;

pub mod transport;
pub mod volume;
