use crate::auth::Credentials;
use anyhow::Result;
use std::path::{Path, PathBuf};

pub fn directory() -> PathBuf { PathBuf::new() }
pub fn load(_root: &Path, _id: &str) -> Result<Option<Credentials>> { Ok(None) }
pub fn save(_root: &Path, _id: &str, _credentials: &Credentials) -> Result<()> { Ok(()) }
