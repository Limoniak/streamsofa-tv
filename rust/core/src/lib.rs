//! Cœur métier de Lumen, partagé entre Android TV (via UniFFI) et, plus tard, le desktop.
//!
//! `api` est la seule surface exposée à Kotlin ; tout le reste est interne.

mod accounts;
mod api;
mod auth;
mod catalog;
mod chat;
mod error;
mod gql;
mod helix;
mod hls;
mod http;
mod qr;
mod secret;
mod store;

#[cfg(test)]
mod catalog_tests;

pub use accounts::{Account, AccountState, AccountsListener, AccountsSnapshot};
pub use api::*;
pub use catalog::{Category, CategoryPage, LiveStream, StreamPage};
pub use chat::{ChatEvent, ChatFragment, ChatListener, ChatMessage, ChatSession, ChatState};
pub use error::CoreError;
pub use hls::{Playback, Variant};
pub use qr::QrMatrix;

uniffi::setup_scaffolding!();
