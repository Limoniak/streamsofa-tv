//! Cœur métier de Lumen, partagé entre Android TV (via UniFFI) et, plus tard, le desktop.
//!
//! `api` est la seule surface exposée à Kotlin ; tout le reste est interne.

mod accounts;
mod api;
mod auth;
mod error;
mod helix;
mod http;
mod qr;
mod secret;
mod store;

pub use accounts::{Account, AccountState, AccountsListener, AccountsSnapshot};
pub use api::*;
pub use error::CoreError;
pub use qr::QrMatrix;

uniffi::setup_scaffolding!();
