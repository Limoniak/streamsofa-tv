use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use crate::accounts::{Account, AccountManager, AccountsListener, AccountsSnapshot, DeviceLogin};
use crate::auth::AuthClient;
use crate::catalog::{Category, CategoryPage, LiveStream, StreamPage};
use crate::chat::{self, ChatListener, ChatSession};
use crate::error::CoreError;
use crate::gql::GqlClient;
use crate::helix::{HelixClient, HelixStream};
use crate::hls::{self, Playback};
use crate::http;
use crate::secret::Secret;
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
    pub gql: String,
    pub usher: String,
    pub chat: String,
    pub min_poll_interval: Duration,
}

impl Default for Endpoints {
    fn default() -> Self {
        Self {
            id: "https://id.twitch.tv".into(),
            helix: "https://api.twitch.tv/helix".into(),
            gql: "https://gql.twitch.tv/gql".into(),
            usher: "https://usher.ttvnw.net".into(),
            chat: "wss://irc-ws.chat.twitch.tv:443".into(),
            min_poll_interval: Duration::from_secs(1),
        }
    }
}

/// Taille des pages du catalogue : une rangée d'accueil plus de quoi défiler un peu.
const PAGE_SIZE: u32 = 30;
/// Au-delà, les suivis en live ne tiennent de toute façon plus dans une rangée.
const MAX_FOLLOWED_PAGES: usize = 5;

#[derive(uniffi::Object)]
pub struct Core {
    client_id: String,
    pub(crate) accounts: AccountManager,
    helix: HelixClient,
    gql: GqlClient,
    http: reqwest::Client,
    usher: String,
    chat_url: String,
    /// Sans ALPN : une socket WebSocket reste en HTTP/1.1.
    chat_tls: Arc<rustls::ClientConfig>,
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
            HelixClient::new(http.clone(), endpoints.helix.clone(), client_id.clone()),
            endpoints.min_poll_interval,
        );
        Ok(Arc::new(Self {
            helix: HelixClient::new(http.clone(), endpoints.helix, client_id.clone()),
            gql: GqlClient::new(http.clone(), endpoints.gql, random_hex(32)),
            usher: endpoints.usher,
            chat_url: endpoints.chat,
            chat_tls: Arc::new(http::tls_config()?),
            http,
            client_id,
            accounts,
        }))
    }

    fn require_client_id(&self) -> Result<(), CoreError> {
        if self.client_id.is_empty() {
            Err(CoreError::NotConfigured)
        } else {
            Ok(())
        }
    }

    /// Exécute un appel Helix avec le jeton du compte actif.
    ///
    /// `Ok(None)` : pas de compte utilisable (invité, reconnexion requise, pas de Client ID) ;
    /// l'appelant bascule alors sur GQL. Un 401 déclenche un renouvellement puis un seul réessai.
    // `Fn -> Future` plutôt qu'`AsyncFn` : rustc ne sait pas encore prouver qu'un futur
    // d'`AsyncFn` est `Send`, ce qu'exige le runtime d'UniFFI.
    async fn with_helix<T, F, Fut>(&self, call: F) -> Result<Option<T>, CoreError>
    where
        F: Fn(Secret, String) -> Fut,
        Fut: std::future::Future<Output = Result<T, CoreError>>,
    {
        if self.client_id.is_empty() {
            return Ok(None);
        }
        let Some(account) = self.accounts.active_usable_account()? else { return Ok(None) };
        let token = match self.accounts.access_token(&account.id).await {
            Ok(token) => token,
            Err(CoreError::ReauthRequired { .. }) => return Ok(None),
            Err(e) => return Err(e),
        };
        match call(token.clone(), account.id.clone()).await {
            Err(CoreError::Api { status: 401, .. }) => match self.accounts.token_rejected(&account.id, &token).await {
                Ok(fresh) => call(fresh, account.id).await.map(Some),
                Err(CoreError::ReauthRequired { .. }) => Ok(None),
                Err(e) => Err(e),
            },
            other => other.map(Some),
        }
    }

    /// Complète des lives Helix avec l'avatar de chaque chaîne (un appel groupé).
    /// Les avatars sont décoratifs : un échec laisse simplement les initiales.
    async fn with_avatars(&self, token: &Secret, streams: Vec<HelixStream>) -> Vec<LiveStream> {
        let ids: Vec<&str> = streams.iter().map(|s| s.user_id.as_str()).collect();
        let avatars: HashMap<String, String> = match self.helix.users(token, &ids).await {
            Ok(users) => users.into_iter().map(|u| (u.id, u.profile_image_url)).collect(),
            Err(_) => HashMap::new(),
        };
        streams
            .into_iter()
            .map(|s| {
                let avatar = avatars.get(&s.user_id).cloned().unwrap_or_default();
                s.into_live(avatar)
            })
            .collect()
    }
}

/// Identifiant aléatoire en hexadécimal (identifiant d'appareil GQL, nonce usher).
fn random_hex(len: usize) -> String {
    use std::hash::{BuildHasher, Hasher};
    let mut out = String::with_capacity(len);
    while out.len() < len {
        // Chaque RandomState est initialisé avec une graine aléatoire du système.
        let mut hasher = std::collections::hash_map::RandomState::new().build_hasher();
        hasher.write_usize(out.len());
        out.push_str(&format!("{:016x}", hasher.finish()));
    }
    out.truncate(len);
    out
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

    // --- Catalogue : Helix avec un compte, GQL en invité ---------------------------------

    /// Lives suivis par le compte actif, du plus regardé au moins regardé.
    pub async fn followed_streams(&self) -> Result<Vec<LiveStream>, CoreError> {
        let result = self
            .with_helix(|token: Secret, user_id: String| async move {
                let mut streams = Vec::new();
                let mut cursor: Option<String> = None;
                for _ in 0..MAX_FOLLOWED_PAGES {
                    let page = self.helix.followed_streams(&token, &user_id, cursor.as_deref()).await?;
                    cursor = page.next_cursor();
                    streams.extend(page.data);
                    if cursor.is_none() {
                        break;
                    }
                }
                let mut live = self.with_avatars(&token, streams).await;
                live.sort_by(|a, b| b.viewers.cmp(&a.viewers));
                Ok(live)
            })
            .await?;
        result.ok_or(CoreError::NotSignedIn)
    }

    pub async fn top_streams(&self, cursor: Option<String>) -> Result<StreamPage, CoreError> {
        let after = cursor.as_deref();
        let helix = self
            .with_helix(|token: Secret, _| async move {
                let page = self.helix.streams(&token, None, after, PAGE_SIZE).await?;
                let next = page.next_cursor();
                Ok(StreamPage { items: self.with_avatars(&token, page.data).await, cursor: next })
            })
            .await?;
        match helix {
            Some(page) => Ok(page),
            None => self.gql.top_streams(cursor.as_deref(), PAGE_SIZE).await,
        }
    }

    pub async fn category_streams(&self, category_id: String, cursor: Option<String>) -> Result<StreamPage, CoreError> {
        let (after, category) = (cursor.as_deref(), category_id.as_str());
        let helix = self
            .with_helix(|token: Secret, _| async move {
                let page = self.helix.streams(&token, Some(category), after, PAGE_SIZE).await?;
                let next = page.next_cursor();
                Ok(StreamPage { items: self.with_avatars(&token, page.data).await, cursor: next })
            })
            .await?;
        match helix {
            Some(page) => Ok(page),
            None => self.gql.category_streams(&category_id, cursor.as_deref(), PAGE_SIZE).await,
        }
    }

    pub async fn top_categories(&self, cursor: Option<String>) -> Result<CategoryPage, CoreError> {
        let after = cursor.as_deref();
        let helix = self
            .with_helix(|token: Secret, _| async move {
                let page = self.helix.top_categories(&token, after, PAGE_SIZE).await?;
                let next = page.next_cursor();
                Ok(CategoryPage { items: page.data.into_iter().map(Category::from).collect(), cursor: next })
            })
            .await?;
        match helix {
            Some(page) => Ok(page),
            None => self.gql.top_categories(cursor.as_deref(), PAGE_SIZE).await,
        }
    }

    /// Infos du live d'une chaîne (titre, catégorie, spectateurs) ; `None` si hors ligne.
    pub async fn live_stream(&self, login: String) -> Result<Option<LiveStream>, CoreError> {
        let channel = login.as_str();
        let helix = self
            .with_helix(|token: Secret, _| async move {
                let Some(stream) = self.helix.stream_by_login(&token, channel).await? else { return Ok(None) };
                Ok(self.with_avatars(&token, vec![stream]).await.into_iter().next())
            })
            .await?;
        match helix {
            Some(stream) => Ok(stream),
            None => self.gql.channel_stream(&login).await,
        }
    }

    // --- Lecture -------------------------------------------------------------------------

    /// Playlist d'un live et ses qualités. Lecture toujours anonyme (jeton GQL du site web) :
    /// les pubs de Twitch sont diffusées telles quelles et Turbo ne s'applique pas.
    pub async fn live_playback(&self, login: String) -> Result<Playback, CoreError> {
        let token = self.gql.live_playback_token(&login).await?.ok_or(CoreError::Offline)?;
        if let Some(auth) = &token.authorization {
            if auth.is_forbidden {
                let detail = auth.forbidden_reason_code.clone().unwrap_or_else(|| "lecture refusée".into());
                return Err(CoreError::Unavailable { detail });
            }
        }
        let nonce = u32::from_str_radix(&random_hex(6), 16).unwrap_or(0);
        let master_url = hls::live_master_url(&self.usher, &login, &token.value, &token.signature, nonce);
        let response = self.http.get(&master_url).send().await?;
        match response.status().as_u16() {
            200..=299 => {}
            404 => return Err(CoreError::Offline),
            403 => {
                let body = response.text().await.unwrap_or_default();
                return Err(CoreError::Unavailable { detail: usher_reason(&body) });
            }
            status => return Err(CoreError::Api { status, detail: "playlist du live".into() }),
        }
        let body = response.text().await?;
        let variants = hls::parse_master(&body, &master_url);
        if variants.is_empty() {
            return Err(CoreError::GqlChanged { operation: "usher".into(), detail: "playlist sans qualité".into() });
        }
        Ok(Playback { master_url, variants })
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Core {
    // --- Chat ----------------------------------------------------------------------------

    /// Rejoint le salon d'une chaîne en lecture seule (anonyme, y compris avec un compte).
    /// `async` pour s'exécuter dans le runtime du cœur, qui porte la connexion.
    pub async fn join_chat(&self, channel_login: String, listener: Arc<dyn ChatListener>) -> Arc<ChatSession> {
        Arc::new(chat::spawn(self.chat_url.clone(), self.chat_tls.clone(), channel_login, listener))
    }

    /// Envoie un message avec le compte actif, via Helix pour connaître la raison d'un refus.
    pub async fn send_chat(&self, broadcaster_id: String, text: String) -> Result<(), CoreError> {
        let text = text.trim();
        if text.is_empty() {
            return Ok(());
        }
        // Limite de Twitch, en caractères.
        let text: String = text.chars().take(500).collect();
        let (channel, message) = (broadcaster_id.as_str(), text.as_str());
        self.with_helix(|token: Secret, sender: String| async move {
            self.helix.send_chat_message(&token, channel, &sender, message).await
        })
        .await?
        .ok_or(CoreError::NotSignedIn)
    }
}

/// Raison lisible d'un refus d'usher (corps JSON `[{"error": "..."}]`), sans recopier l'URL.
fn usher_reason(body: &str) -> String {
    #[derive(serde::Deserialize)]
    struct UsherError {
        #[serde(default)]
        error: String,
    }
    serde_json::from_str::<Vec<UsherError>>(body)
        .ok()
        .and_then(|errors| errors.into_iter().map(|e| e.error).find(|e| !e.is_empty()))
        .unwrap_or_else(|| "lecture refusée par Twitch".into())
}

#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
