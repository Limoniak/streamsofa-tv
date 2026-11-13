//! Cœur métier de Lumen, partagé entre Android TV (via UniFFI) et, plus tard, le desktop.
//!
//! `api` est la seule surface exposée à Kotlin ; tout le reste est interne.

mod api;
mod error;

pub use api::*;
pub use error::CoreError;

uniffi::setup_scaffolding!();
