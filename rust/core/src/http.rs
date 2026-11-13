use std::sync::Arc;
use std::time::Duration;

use crate::error::CoreError;

/// Client HTTP partagé : rustls + ring, racines Mozilla embarquées.
pub fn build_client(app_version: &str) -> Result<reqwest::Client, CoreError> {
    let internal = |e: &dyn std::fmt::Display| CoreError::Internal { reason: e.to_string() };

    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut tls = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| internal(&e))?
        .with_root_certificates(roots)
        .with_no_client_auth();
    // reqwest utilise une configuration préconfigurée telle quelle : l'ALPN est à notre charge.
    tls.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];

    reqwest::Client::builder()
        .tls_backend_preconfigured(tls)
        .user_agent(format!("Lumen/{app_version}"))
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(20))
        .build()
        .map_err(|e| internal(&e))
}
