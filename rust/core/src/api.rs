use std::collections::HashMap;
use std::path::Path;
use std::sync::{Arc, RwLock};
use std::time::Duration;

use crate::accounts::{Account, AccountManager, AccountsListener, AccountsSnapshot, DeviceLogin};
use crate::auth::AuthClient;
use crate::catalog::{Category, CategoryPage, Channel, LiveStream, ReplayComment, StreamPage, Video, VideoPage};
use crate::chat::decor::{Decor, THIRD_PARTY_EMOTES};
use crate::chat::{self, ChatListener, ChatSession};
use crate::emotes::{self, EmoteMap};
use crate::error::CoreError;
use crate::gql::GqlClient;
use crate::helix::{HelixClient, HelixStream};
use crate::hls::{self, Playback};
use crate::http;
use crate::secret::Secret;
use crate::store::Db;
use crate::update::{self, AppUpdate};

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
    pub github: String,
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
            github: "https://api.github.com".into(),
            min_poll_interval: Duration::from_secs(1),
        }
    }
}

/// Taille des pages du catalogue : une rangée d'accueil plus de quoi défiler un peu.
const PAGE_SIZE: u32 = 30;
/// Au-delà, les suivis en live ne tiennent de toute façon plus dans une rangée.
const MAX_FOLLOWED_PAGES: usize = 5;
/// VOD d'une chaîne en invité : une seule page, donc la plus grande que GQL accepte.
const GUEST_VIDEOS: u32 = 100;

#[derive(uniffi::Object)]
pub struct Core {
    client_id: String,
    pub(crate) accounts: AccountManager,
    helix: HelixClient,
    gql: GqlClient,
    http: reqwest::Client,
    usher: String,
    chat_url: String,
    github: String,
    /// Sans ALPN : une socket WebSocket reste en HTTP/1.1.
    chat_tls: Arc<rustls::ClientConfig>,
    /// Communs à toutes les chaînes : chargés une fois par lancement, réessayés après un échec.
    global_badges: tokio::sync::OnceCell<HashMap<String, String>>,
    global_emotes: tokio::sync::OnceCell<EmoteMap>,
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
            github: endpoints.github,
            chat_tls: Arc::new(http::tls_config()?),
            global_badges: tokio::sync::OnceCell::new(),
            global_emotes: tokio::sync::OnceCell::new(),
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

    /// Badges, émotes tierces et blocages du compte, ajoutés au fil de l'eau : tout est
    /// décoratif, un échec laisse simplement le chat tel que Twitch l'envoie.
    async fn load_decor(self: Arc<Self>, login: String, third_party: bool, decor: Arc<RwLock<Decor>>) {
        let write = |f: &dyn Fn(&mut Decor)| f(&mut decor.write().unwrap_or_else(|e| e.into_inner()));
        if let Ok(blocked) = self.with_helix(|token, user_id| {
            let helix = &self.helix;
            async move { helix.blocked_logins(&token, &user_id).await }
        }).await {
            write(&|d| d.hidden_users.extend(blocked.iter().flatten().cloned()));
        }
        if let Ok(global) = self.global_badges.get_or_try_init(|| self.gql.global_badges()).await {
            // Les badges de la chaîne, déjà présents, priment.
            write(&|d| global.iter().for_each(|(k, v)| { d.badges.entry(k.clone()).or_insert_with(|| v.clone()); }));
        }
        let Ok(Some((channel_id, badges))) = self.gql.channel_badges(&login).await else { return };
        write(&|d| d.badges.extend(badges.iter().map(|(k, v)| (k.clone(), v.clone()))));
        if !third_party {
            return;
        }
        let global = self
            .global_emotes
            .get_or_try_init(|| async {
                let map = emotes::global(&self.http).await;
                if map.is_empty() { Err(()) } else { Ok(map) }
            })
            .await
            .cloned()
            .unwrap_or_default();
        let mut all = global;
        all.extend(emotes::channel(&self.http, &channel_id).await);
        write(&|d| d.emotes = all.clone());
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

impl Core {
    /// Les reprises de VOD sont propres à chaque profil, invité compris.
    fn progress_scope(&self) -> Result<String, CoreError> {
        Ok(self.accounts.db.active_account_id()?.unwrap_or_else(|| "guest".into()))
    }

    fn with_progress(&self, videos: &mut [Video]) -> Result<(), CoreError> {
        let ids: Vec<&str> = videos.iter().map(|v| v.id.as_str()).collect();
        let progress = self.accounts.db.vod_progress(&self.progress_scope()?, &ids)?;
        for video in videos.iter_mut() {
            if let Some(&(position_s, finished)) = progress.get(&video.id) {
                (video.position_s, video.finished) = (position_s, finished);
            }
        }
        Ok(())
    }

    /// Télécharge une playlist maîtresse d'usher ; `missing` est l'erreur d'un 404.
    async fn master_playlist(&self, master_url: String, missing: CoreError) -> Result<Playback, CoreError> {
        let response = self.http.get(&master_url).send().await?;
        match response.status().as_u16() {
            200..=299 => {}
            404 => return Err(missing),
            403 => {
                let body = response.text().await.unwrap_or_default();
                return Err(CoreError::Unavailable { detail: usher_reason(&body) });
            }
            status => return Err(CoreError::Api { status, detail: "playlist maîtresse".into() }),
        }
        let body = response.text().await?;
        let variants = hls::parse_master(&body, &master_url);
        if variants.is_empty() {
            return Err(CoreError::GqlChanged { operation: "usher".into(), detail: "playlist sans qualité".into() });
        }
        Ok(Playback { master_url, variants })
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

    // --- Chaînes et VOD -------------------------------------------------------------------

    /// Toutes les chaînes suivies, en live ou non, de la plus récemment suivie à la plus ancienne.
    pub async fn followed_channels(&self) -> Result<Vec<Channel>, CoreError> {
        let result = self
            .with_helix(|token: Secret, user_id: String| async move {
                let mut follows = Vec::new();
                let mut cursor: Option<String> = None;
                for _ in 0..MAX_FOLLOWED_PAGES {
                    let page = self.helix.followed_channels(&token, &user_id, cursor.as_deref()).await?;
                    cursor = page.next_cursor();
                    follows.extend(page.data);
                    if cursor.is_none() {
                        break;
                    }
                }
                // Avatars par lots de 100 ; décoratifs, un échec laisse les initiales.
                let mut avatars: HashMap<String, String> = HashMap::new();
                for chunk in follows.chunks(100) {
                    let ids: Vec<&str> = chunk.iter().map(|f| f.broadcaster_id.as_str()).collect();
                    if let Ok(users) = self.helix.users(&token, &ids).await {
                        avatars.extend(users.into_iter().map(|u| (u.id, u.profile_image_url)));
                    }
                }
                Ok(follows
                    .into_iter()
                    .map(|f| Channel {
                        avatar_url: avatars.remove(&f.broadcaster_id).unwrap_or_default(),
                        id: f.broadcaster_id,
                        login: f.broadcaster_login,
                        display_name: f.broadcaster_name,
                        description: String::new(),
                    })
                    .collect())
            })
            .await?;
        result.ok_or(CoreError::NotSignedIn)
    }

    /// `None` : la chaîne n'existe pas.
    pub async fn channel(&self, login: String) -> Result<Option<Channel>, CoreError> {
        let channel = login.as_str();
        let helix = self
            .with_helix(|token: Secret, _| async move { Ok(self.helix.user_by_login(&token, channel).await?.map(Channel::from)) })
            .await?;
        match helix {
            Some(channel) => Ok(channel),
            None => self.gql.channel(&login).await,
        }
    }

    /// VOD d'une chaîne, des plus récentes aux plus anciennes, avec la reprise du profil actif.
    pub async fn channel_videos(&self, channel_id: String, cursor: Option<String>) -> Result<VideoPage, CoreError> {
        let (after, channel) = (cursor.as_deref(), channel_id.as_str());
        let helix = self
            .with_helix(|token: Secret, _| async move {
                let page = self.helix.channel_videos(&token, channel, after, PAGE_SIZE).await?;
                let next = page.next_cursor();
                Ok(VideoPage { items: page.data.into_iter().map(Video::from).collect(), cursor: next })
            })
            .await?;
        let mut page = match helix {
            Some(page) => page,
            // shortcut: en invité, une seule page (GQL exige un jeton d'intégrité pour la suite) ;
            // un compte connecté pagine via Helix.
            None if cursor.is_none() => VideoPage { items: self.gql.channel_videos(&channel_id, GUEST_VIDEOS).await?, cursor: None },
            None => VideoPage { items: Vec::new(), cursor: None },
        };
        self.with_progress(&mut page.items)?;
        Ok(page)
    }

    /// `None` : VOD supprimée ou inconnue.
    pub async fn video(&self, video_id: String) -> Result<Option<Video>, CoreError> {
        let id = video_id.as_str();
        let helix = self
            .with_helix(|token: Secret, _| async move { Ok(self.helix.video(&token, id).await?.map(Video::from)) })
            .await?;
        let mut video = match helix {
            Some(video) => video,
            None => self.gql.video(&video_id).await?,
        };
        self.with_progress(video.as_mut_slice())?;
        Ok(video)
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
        self.master_playlist(master_url, CoreError::Offline).await
    }

    /// Playlist d'une VOD, en anonyme comme le live : une VOD réservée aux abonnés reste refusée.
    pub async fn vod_playback(&self, video_id: String) -> Result<Playback, CoreError> {
        // L'identifiant finit dans un chemin d'URL : uniquement des chiffres.
        if video_id.is_empty() || !video_id.bytes().all(|b| b.is_ascii_digit()) {
            return Err(CoreError::Unavailable { detail: "identifiant de VOD invalide".into() });
        }
        let missing = || CoreError::Unavailable { detail: "VOD introuvable".into() };
        let token = self.gql.vod_playback_token(&video_id).await?.ok_or_else(missing)?;
        let nonce = u32::from_str_radix(&random_hex(6), 16).unwrap_or(0);
        let master_url = hls::vod_master_url(&self.usher, &video_id, &token.value, &token.signature, nonce);
        self.master_playlist(master_url, missing()).await
    }

    /// Mémorise où en est la VOD pour le profil actif (à appeler hors du thread principal).
    /// Renvoie `true` si elle compte désormais comme terminée.
    pub fn save_vod_progress(&self, video_id: String, position_s: u32, duration_s: u32) -> Result<bool, CoreError> {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
        self.accounts.db.save_vod_progress(&self.progress_scope()?, &video_id, position_s, duration_s, now)
    }

    /// Chat d'une VOD à partir de `offset_s`, en ordre chronologique. Une liste vide
    /// signifie la fin du chat, ou une VOD sans chat.
    pub async fn vod_chat(&self, video_id: String, offset_s: u32) -> Result<Vec<ReplayComment>, CoreError> {
        let mut comments = self.gql.vod_comments(&video_id, offset_s).await?;
        let filters = Decor::with_filters(&self.settings()?);
        // Twitch renvoie aussi quelques messages juste avant l'instant demandé.
        comments.retain(|c| c.offset_s >= offset_s && !filters.hides(&c.message));
        Ok(comments)
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Core {
    // --- Chat ----------------------------------------------------------------------------

    /// Rejoint le salon d'une chaîne en lecture seule (anonyme, y compris avec un compte).
    /// `async` pour s'exécuter dans le runtime du cœur, qui porte la connexion.
    pub async fn join_chat(self: Arc<Self>, channel_login: String, listener: Arc<dyn ChatListener>) -> Arc<ChatSession> {
        let settings = self.settings().unwrap_or_default();
        let third_party = settings.get(THIRD_PARTY_EMOTES).map(String::as_str) != Some("false");
        let decor = Arc::new(RwLock::new(Decor::with_filters(&settings)));
        let loader = self.clone().load_decor(channel_login.to_lowercase(), third_party, decor.clone());
        Arc::new(chat::spawn(self.chat_url.clone(), self.chat_tls.clone(), channel_login, listener, decor, loader))
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

/// Préférences (portée `global`) et mise à jour de l'app.
#[uniffi::export(async_runtime = "tokio")]
impl Core {
    /// Toutes les préférences enregistrées ; une clé absente prend la valeur par défaut de l'app.
    pub fn settings(&self) -> Result<HashMap<String, String>, CoreError> {
        self.accounts.db.settings(SETTINGS_SCOPE)
    }

    pub fn set_setting(&self, key: String, value: String) -> Result<(), CoreError> {
        self.accounts.db.set_setting(SETTINGS_SCOPE, &key, &value)
    }

    /// Release GitHub plus récente que `current_version`, avec l'APK de la première ABI de `abis`.
    pub async fn check_update(&self, current_version: String, abis: Vec<String>) -> Result<Option<AppUpdate>, CoreError> {
        update::check(&self.http, &self.github, &current_version, &abis).await
    }

    /// Télécharge l'APK d'une mise à jour vers `dest_path` (remplacé s'il existe).
    pub async fn download_update(&self, apk_url: String, dest_path: String) -> Result<(), CoreError> {
        update::download(&self.http, &apk_url, Path::new(&dest_path)).await
    }
}

const SETTINGS_SCOPE: &str = "global";

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
        .map(|e| if e == "Manifest is restricted" { "Vidéo réservée aux abonnés de la chaîne.".into() } else { e })
        .unwrap_or_else(|| "lecture refusée par Twitch".into())
}

#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
