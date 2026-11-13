use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use crate::accounts::{Account, AccountManager, AccountsListener, AccountsSnapshot, DeviceLogin};
use crate::auth::AuthClient;
use crate::error::CoreError;
use crate::helix::HelixClient;
use crate::http;
use crate::store::Db;

/// Configuration fournie par l'application au démarrage.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CoreConfig {
    /// Client ID Twitch de l'app (type « Public »). Vide tant qu'il n'est pas configuré.
    pub client_id: String,
    /// Répertoire privé de l'app : base SQLite et caches.
    pub data_dir: String,
    pub app_version: String,
}

/// Stockage des secrets, implémenté côté Kotlin (Android Keystore).
/// Rust ne voit que des blobs opaques et ne les écrit jamais sur disque.
#[uniffi::export(with_foreign)]
pub trait SecretStore: Send + Sync {
    fn load(&self, key: String) -> Result<Option<Vec<u8>>, CoreError>;
    fn save(&self, key: String, blob: Vec<u8>) -> Result<(), CoreError>;
    fn delete(&self, key: String) -> Result<(), CoreError>;
}

/// Adresses des services Twitch, remplaçables par un faux serveur dans les tests.
pub(crate) struct Endpoints {
    pub id: String,
    pub helix: String,
    pub min_poll_interval: Duration,
}

impl Default for Endpoints {
    fn default() -> Self {
        Self {
            id: "https://id.twitch.tv".into(),
            helix: "https://api.twitch.tv/helix".into(),
            min_poll_interval: Duration::from_secs(1),
        }
    }
}

#[derive(uniffi::Object)]
pub struct Core {
    client_id: String,
    pub(crate) accounts: AccountManager,
}

impl Core {
    pub(crate) fn with_endpoints(
        config: CoreConfig,
        secrets: Arc<dyn SecretStore>,
        endpoints: Endpoints,
    ) -> Result<Arc<Self>, CoreError> {
        let client_id = config.client_id.trim().to_owned();
        let http = http::build_client(&config.app_version)?;
        let db = Db::open(&Path::new(&config.data_dir).join("lumen.db"))?;
        let accounts = AccountManager::new(
            db,
            secrets,
            AuthClient::new(http.clone(), endpoints.id, client_id.clone()),
            HelixClient::new(http, endpoints.helix, client_id.clone()),
            endpoints.min_poll_interval,
        );
        Ok(Arc::new(Self { client_id, accounts }))
    }

    fn require_client_id(&self) -> Result<(), CoreError> {
        if self.client_id.is_empty() {
            Err(CoreError::NotConfigured)
        } else {
            Ok(())
        }
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Core {
    /// Ouvre la base locale : à appeler hors du thread principal.
    #[uniffi::constructor]
    pub fn new(config: CoreConfig, secrets: Arc<dyn SecretStore>) -> Result<Arc<Self>, CoreError> {
        Self::with_endpoints(config, secrets, Endpoints::default())
    }

    pub fn version(&self) -> String {
        core_version()
    }

    pub fn has_client_id(&self) -> bool {
        !self.client_id.is_empty()
    }

    // --- Comptes -------------------------------------------------------------------------

    pub fn set_accounts_listener(&self, listener: Option<Arc<dyn AccountsListener>>) {
        self.accounts.set_listener(listener);
    }

    pub fn accounts(&self) -> Result<AccountsSnapshot, CoreError> {
        self.accounts.snapshot()
    }

    /// `None` bascule en mode invité.
    pub fn set_active_account(&self, account_id: Option<String>) -> Result<(), CoreError> {
        self.accounts.set_active(account_id)
    }

    /// Demande à Twitch un code à afficher (texte + QR).
    pub async fn start_device_login(&self) -> Result<DeviceLogin, CoreError> {
        self.require_client_id()?;
        self.accounts.start_device_login().await
    }

    /// Attend la validation du code, puis enregistre le compte et le rend actif.
    /// Annuler la coroutine appelante interrompt l'attente.
    pub async fn complete_device_login(&self, user_code: String) -> Result<Account, CoreError> {
        self.require_client_id()?;
        self.accounts.complete_device_login(&user_code).await
    }

    /// Vérifie les jetons de tous les comptes ; ceux que Twitch refuse passent en
    /// « reconnexion requise ». À appeler au démarrage puis toutes les heures.
    pub async fn validate_accounts(&self) -> Result<(), CoreError> {
        if self.client_id.is_empty() {
            return Ok(());
        }
        self.accounts.validate_all().await
    }

    /// Retire le compte de l'appareil et révoque son jeton si le réseau le permet.
    pub async fn remove_account(&self, account_id: String) -> Result<(), CoreError> {
        self.accounts.remove(&account_id).await
    }
}

#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
