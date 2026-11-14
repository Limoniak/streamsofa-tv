use std::collections::HashMap;
use std::path::Path;
use std::sync::{Arc, RwLock};
use std::time::Duration;

use crate::accounts::{Account, AccountManager, AccountsListener, AccountsSnapshot, DeviceLogin};
use crate::auth::AuthClient;
use crate::catalog::{
    Category, CategoryPage, Channel, ChannelAbout, Clip, ClipQuality, LiveStream, ReplayComment, SearchChannel, SearchResults, StreamPage,
    Video, VideoPage,
};
use crate::eventsub::{self, RaidListener, RaidWatch};
use crate::chat::decor::{Decor, THIRD_PARTY_EMOTES};
use crate::chat::{self, ChatListener, ChatSession, PickerEmote};
use crate::emotes::{self, EmoteMap};
use crate::error::CoreError;
use crate::gql::GqlClient;
use crate::helix::{HelixClient, HelixStream};
use crate::hls::{self, Playback};
use crate::http;
use crate::secret::Secret;
use crate::store::{GLOBAL_SETTINGS, KIDS_PROFILE, PIN_HASH};
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
    pub eventsub: String,
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
            eventsub: "wss://eventsub.wss.twitch.tv/ws".into(),
            github: "https://api.github.com".into(),
            min_poll_interval: Duration::from_secs(1),
        }
    }
}

/// Taille des pages du catalogue : une rangée d'accueil plus de quoi défiler un peu.
const PAGE_SIZE: u32 = 30;
/// Au-delà, les suivis en live ne tiennent de toute façon plus dans une rangée.
const MAX_FOLLOWED_PAGES: usize = 5;
/// Chaînes interrogées pour la rangée « clips des suivis » : une requête GQL chacune.
const FOLLOWED_CLIP_CHANNELS: usize = 12;
/// VOD d'une chaîne en invité : une seule page, donc la plus grande que GQL accepte.
const GUEST_VIDEOS: u32 = 100;
/// Résultats de recherche par type : de quoi remplir une rangée.
const SEARCH_SIZE: u32 = 20;
/// Catégories explorées pour les recommandations (une requête chacune).
const RECOMMENDED_CATEGORIES: usize = 3;

/// Catégories les plus diffusées par les suivis en live, et leur langue la plus courante.
fn recommendation_seeds(followed: &[HelixStream]) -> (Vec<String>, Option<String>) {
    let mut categories: HashMap<&str, usize> = HashMap::new();
    let mut languages: HashMap<&str, usize> = HashMap::new();
    for stream in followed {
        if !stream.game_id.is_empty() {
            *categories.entry(&stream.game_id).or_default() += 1;
        }
        if !stream.language.is_empty() && stream.language != "other" {
            *languages.entry(&stream.language).or_default() += 1;
        }
    }
    let mut categories: Vec<(&str, usize)> = categories.into_iter().collect();
    // À égalité, l'ordre d'identifiant garde un résultat stable.
    categories.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(b.0)));
    let language = languages.into_iter().max_by(|a, b| a.1.cmp(&b.1).then(b.0.cmp(a.0))).map(|(l, _)| l.to_owned());
    (categories.into_iter().map(|(id, _)| id.to_owned()).collect(), language)
}

#[derive(uniffi::Object)]
pub struct Core {
    client_id: String,
    pub(crate) accounts: AccountManager,
    helix: HelixClient,
    gql: GqlClient,
    http: reqwest::Client,
    usher: String,
    chat_url: String,
    eventsub_url: String,
    github: String,
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
            eventsub_url: endpoints.eventsub,
            github: endpoints.github,
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

    /// Badges, émotes tierces et blocages du compte, ajoutés au fil de l'eau : tout est
    /// décoratif, un échec laisse simplement le chat tel que Twitch l'envoie.
    async fn load_decor(self: Arc<Self>, login: String, third_party: bool, decor: Arc<RwLock<Decor>>) {
        let write = |f: &mut dyn FnMut(&mut Decor)| f(&mut decor.write().unwrap_or_else(|e| e.into_inner()));
        if let Ok(Some(blocked)) = self
            .with_helix(|token, user_id| {
                let helix = &self.helix;
                async move { helix.blocked_logins(&token, &user_id).await }
            })
            .await
        {
            write(&mut |d| d.hidden_users.extend(blocked.iter().cloned()));
        }
        let global: Option<HashMap<String, String>> =
            self.cached("badges:global", DAY_S, async { self.gql.global_badges().await.ok().filter(|m| !m.is_empty()) }).await;
        let channel: Option<(String, HashMap<String, String>)> =
            self.cached(&format!("badges:{login}"), HOUR_S, async { self.gql.channel_badges(&login).await.ok().flatten() }).await;
        write(&mut |d| {
            // Les badges de la chaîne priment sur les globaux du même nom.
            d.badges.extend(global.iter().flatten().map(|(k, v)| (k.clone(), v.clone())));
            d.badges.extend(channel.iter().flat_map(|(_, m)| m).map(|(k, v)| (k.clone(), v.clone())));
        });
        let Some((channel_id, _)) = channel.filter(|_| third_party) else { return };
        let mut emotes: EmoteMap = self
            .cached("emotes:global", DAY_S, async { Some(emotes::global(&self.http).await).filter(|m| !m.is_empty()) })
            .await
            .unwrap_or_default();
        // shortcut: un fournisseur en panne donne une liste partielle gardée une heure ; à affiner si gênant.
        let channel_emotes: Option<EmoteMap> =
            self.cached(&format!("emotes:{channel_id}"), HOUR_S, async { Some(emotes::channel(&self.http, &channel_id).await) }).await;
        emotes.extend(channel_emotes.unwrap_or_default());
        write(&mut |d| d.emotes = std::mem::take(&mut emotes));
    }

    async fn raid_loop(self: Arc<Self>, broadcaster_id: String, listener: Arc<dyn RaidListener>) {
        let mut backoff = Duration::from_secs(2);
        loop {
            let mut url = self.eventsub_url.clone();
            let mut subscribed = false;
            // Une session suit les `session_reconnect` de Twitch sans se réabonner.
            'session: loop {
                let connector = Some(tokio_tungstenite::Connector::Rustls(self.chat_tls.clone()));
                let Ok(Ok((mut socket, _))) = tokio::time::timeout(
                    Duration::from_secs(10),
                    tokio_tungstenite::connect_async_tls_with_config(url.as_str(), None, true, connector),
                )
                .await
                else {
                    break 'session;
                };
                while let Some(text) = eventsub::next_text(&mut socket).await {
                    match eventsub::parse(&text) {
                        eventsub::Frame::Welcome { session_id } if !subscribed => {
                            let condition = serde_json::json!({ "from_broadcaster_user_id": broadcaster_id });
                            let result = self
                                .with_helix(|token: Secret, _| {
                                    let (helix, condition, session_id) = (&self.helix, condition.clone(), session_id.clone());
                                    async move { helix.subscribe_eventsub(&token, "channel.raid", condition, &session_id).await }
                                })
                                .await;
                            match result {
                                Ok(Some(())) => {
                                    subscribed = true;
                                    backoff = Duration::from_secs(2);
                                }
                                // Compte déconnecté entre-temps : plus rien à surveiller.
                                Ok(None) => return,
                                Err(_) => break 'session,
                            }
                        }
                        eventsub::Frame::Reconnect { url: next } => {
                            url = next;
                            continue 'session;
                        }
                        eventsub::Frame::Raid(raid) => listener.on_raid(raid),
                        _ => {}
                    }
                }
                break 'session;
            }
            tokio::time::sleep(backoff).await;
            backoff = (backoff * 2).min(Duration::from_secs(60));
        }
    }

    /// Réponse décorative gardée `ttl_s` dans `meta_cache` ; `fetch` rend `None` pour ne rien garder.
    async fn cached<T, F>(&self, key: &str, ttl_s: i64, fetch: F) -> Option<T>
    where
        T: serde::Serialize + serde::de::DeserializeOwned,
        F: std::future::Future<Output = Option<T>>,
    {
        let now = unix_now();
        let db = &self.accounts.db;
        if let Some(value) = db.meta_get(key, now).ok().flatten().and_then(|body| serde_json::from_str(&body).ok()) {
            return Some(value);
        }
        let value = fetch.await?;
        if let Ok(body) = serde_json::to_string(&value) {
            // Un cache non écrit sera simplement rechargé la prochaine fois.
            let _ = db.meta_put(key, &body, now, ttl_s);
        }
        Some(value)
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
        Ok(self.accounts.db.active_account_id()?.unwrap_or_else(|| crate::accounts::GUEST.into()))
    }

    fn kids(&self) -> Result<bool, CoreError> {
        Ok(self.accounts.db.settings(&self.progress_scope()?)?.contains_key(KIDS_PROFILE))
    }

    /// Mode enfants : ne garde que les éléments des chaînes tout public ; sinon `items` tel quel.
    async fn kids_filter<T>(&self, mut items: Vec<T>, login: impl Fn(&T) -> &str) -> Result<Vec<T>, CoreError> {
        if items.is_empty() || !self.kids()? {
            return Ok(items);
        }
        let logins: Vec<String> = items.iter().map(|i| login(i).to_lowercase()).collect();
        // Échec réseau : rien n'est montré plutôt que tout.
        let safe = self.gql.safe_channels(&logins, false).await.unwrap_or_default();
        items.retain(|i| safe.contains(&login(i).to_lowercase()));
        Ok(items)
    }

    /// Refuse la lecture d'une chaîne non tout public en mode enfants.
    async fn kids_guard(&self, key: &str, by_id: bool) -> Result<(), CoreError> {
        if self.kids()? && !self.gql.safe_channels(&[key.to_owned()], by_id).await.unwrap_or_default().contains(key) {
            return Err(CoreError::Unavailable { detail: KIDS_REFUSAL.into() });
        }
        Ok(())
    }

    async fn kids_page(&self, mut page: StreamPage) -> Result<StreamPage, CoreError> {
        page.items = self.kids_filter(page.items, |s| &s.login).await?;
        Ok(page)
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
    async fn master_playlist(&self, master_url: String, expires_at: i64, missing: CoreError) -> Result<Playback, CoreError> {
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
        Ok(Playback { master_url, variants, expires_at })
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
                live.sort_by_key(|s| std::cmp::Reverse(s.viewers));
                Ok(live)
            })
            .await?;
        self.kids_filter(result.ok_or(CoreError::NotSignedIn)?, |s| &s.login).await
    }

    pub async fn top_streams(&self, cursor: Option<String>) -> Result<StreamPage, CoreError> {
        let after = cursor.as_deref();
        let helix = self
            .with_helix(|token: Secret, _| async move {
                let page = self.helix.streams(&token, None, None, after, PAGE_SIZE).await?;
                let next = page.next_cursor();
                Ok(StreamPage { items: self.with_avatars(&token, page.data).await, cursor: next })
            })
            .await?;
        let page = match helix {
            Some(page) => page,
            None => self.gql.top_streams(cursor.as_deref(), PAGE_SIZE).await?,
        };
        self.kids_page(page).await
    }

    /// Lives les plus regardés dans `language` (code ISO 639-1 : `fr`), toutes catégories : une page.
    /// Rangée « Les plus regardés dans votre langue » de l'accueil.
    pub async fn language_streams(&self, language: String) -> Result<Vec<LiveStream>, CoreError> {
        let lang = language.to_lowercase();
        if lang.len() != 2 || !lang.bytes().all(|b| b.is_ascii_lowercase()) {
            return Err(CoreError::Internal { reason: format!("code de langue invalide : {language}") });
        }
        let lang = lang.as_str();
        let helix = self
            .with_helix(|token: Secret, _| async move {
                let page = self.helix.streams(&token, None, Some(lang), None, PAGE_SIZE).await?;
                Ok(self.with_avatars(&token, page.data).await)
            })
            .await?;
        let streams = match helix {
            Some(streams) => streams,
            None => self.gql.language_streams(lang, PAGE_SIZE).await?.items,
        };
        self.kids_filter(streams, |s| &s.login).await
    }

    /// `language` : code ISO 639-1 (`fr`) pour ne garder que cette langue, sinon toutes.
    pub async fn category_streams(
        &self,
        category_id: String,
        cursor: Option<String>,
        language: Option<String>,
    ) -> Result<StreamPage, CoreError> {
        let (after, category, lang) = (cursor.as_deref(), category_id.as_str(), language.as_deref());
        let helix = self
            .with_helix(|token: Secret, _| async move {
                let page = self.helix.streams(&token, Some(category), lang, after, PAGE_SIZE).await?;
                let next = page.next_cursor();
                Ok(StreamPage { items: self.with_avatars(&token, page.data).await, cursor: next })
            })
            .await?;
        let page = match helix {
            Some(page) => page,
            None => self.gql.category_streams(&category_id, lang, cursor.as_deref(), PAGE_SIZE).await?,
        };
        self.kids_page(page).await
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

    /// Lives conseillés au compte actif : les plus regardés des catégories que ses suivis
    /// diffusent en ce moment, dans leur langue la plus courante, sans les chaînes déjà suivies.
    /// Vide en invité (rien sur quoi s'appuyer) ; Twitch n'ouvre pas ses recommandations à l'API.
    pub async fn recommended_streams(&self) -> Result<Vec<LiveStream>, CoreError> {
        let result = self
            .with_helix(|token: Secret, user_id: String| async move {
                let followed = self.helix.followed_streams(&token, &user_id, None).await?.data;
                let (categories, language) = recommendation_seeds(&followed);
                let mut picked: Vec<HelixStream> = Vec::new();
                for category in categories.iter().take(RECOMMENDED_CATEGORIES) {
                    // Une catégorie en échec n'empêche pas les autres.
                    if let Ok(page) = self.helix.streams(&token, Some(category), language.as_deref(), None, 20).await {
                        picked.extend(page.data);
                    }
                }
                let mut seen: std::collections::HashSet<String> = followed.into_iter().map(|s| s.user_id).collect();
                picked.retain(|s| seen.insert(s.user_id.clone()));
                picked.sort_by_key(|s| std::cmp::Reverse(s.viewer_count));
                picked.truncate(PAGE_SIZE as usize);
                Ok(self.with_avatars(&token, picked).await)
            })
            .await?;
        self.kids_filter(result.unwrap_or_default(), |s| &s.login).await
    }

    /// Chaînes et catégories correspondant à `query`.
    pub async fn search(&self, query: String) -> Result<SearchResults, CoreError> {
        let query = query.trim().to_owned();
        if query.is_empty() {
            return Ok(SearchResults { channels: Vec::new(), categories: Vec::new() });
        }
        let q = query.as_str();
        let helix = self
            .with_helix(|token: Secret, _| async move {
                let (channels, categories) =
                    tokio::try_join!(self.helix.search_channels(&token, q, SEARCH_SIZE), self.helix.search_categories(&token, q, SEARCH_SIZE))?;
                let mut channels: Vec<SearchChannel> = channels.into_iter().map(SearchChannel::from).collect();
                // Les chaînes en live d'abord, l'ordre de pertinence de Twitch ensuite.
                channels.sort_by_key(|c| !c.live);
                Ok(SearchResults { channels, categories: categories.into_iter().map(Category::from).collect() })
            })
            .await?;
        let mut results = match helix {
            Some(results) => results,
            None => {
                let mut results = self.gql.search(&query).await?;
                results.channels.sort_by_key(|c| !c.live);
                results
            }
        };
        results.channels = self.kids_filter(results.channels, |c| &c.login).await?;
        Ok(results)
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
        let stream = match helix {
            Some(stream) => stream,
            None => self.gql.channel_stream(&login).await?,
        };
        Ok(self.kids_filter(stream.into_iter().collect(), |s| &s.login).await?.pop())
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

    /// Planning à venir et panneaux « À propos » ; `None` si la chaîne n'existe pas.
    pub async fn channel_about(&self, login: String) -> Result<Option<ChannelAbout>, CoreError> {
        self.gql.channel_about(&login, unix_now()).await
    }

    /// Clips les plus vus de la semaine, ou de tous les temps si la semaine n'en a pas.
    pub async fn channel_clips(&self, login: String) -> Result<Vec<Clip>, CoreError> {
        let mut clips = self.gql.clips(&login, false, PAGE_SIZE).await?;
        if clips.is_empty() {
            clips = self.gql.clips(&login, true, PAGE_SIZE).await?;
        }
        self.kids_filter(clips, |c| &c.channel_login).await
    }

    /// Clips les plus vus de la semaine dans une catégorie.
    pub async fn category_clips(&self, category_id: String, language: Option<String>) -> Result<Vec<Clip>, CoreError> {
        let clips = self.gql.category_clips(&category_id, language.as_deref().filter(|l| !l.is_empty()), PAGE_SIZE).await?;
        self.kids_filter(clips, |c| &c.channel_login).await
    }

    /// Clips de la semaine des chaînes suivies : celles en live d'abord, puis les derniers suivis,
    /// [`FOLLOWED_CLIP_CHANNELS`] chaînes au plus, du plus vu au moins vu.
    pub async fn followed_clips(&self) -> Result<Vec<Clip>, CoreError> {
        let live = self.followed_streams().await?;
        let follows = self
            .with_helix(|token: Secret, user_id: String| async move { self.helix.followed_channels(&token, &user_id, None).await })
            .await?
            .map(|page| page.data)
            .unwrap_or_default();
        let mut logins: Vec<String> = live.into_iter().map(|s| s.login).chain(follows.into_iter().map(|f| f.broadcaster_login)).collect();
        let mut seen = std::collections::HashSet::new();
        logins.retain(|l| seen.insert(l.clone()));
        logins.truncate(FOLLOWED_CLIP_CHANNELS);
        // Une chaîne en erreur est simplement absente de la rangée.
        let lists = futures_util::future::join_all(logins.iter().map(|login| self.gql.clips(login, false, 5))).await;
        let mut clips: Vec<Clip> = lists.into_iter().flatten().flatten().collect();
        clips.sort_by_key(|c| std::cmp::Reverse(c.views));
        clips.truncate(PAGE_SIZE as usize);
        self.kids_filter(clips, |c| &c.channel_login).await
    }

    /// VOD d'une chaîne, des plus récentes aux plus anciennes, avec la reprise du profil actif.
    pub async fn channel_videos(&self, channel_id: String, cursor: Option<String>) -> Result<VideoPage, CoreError> {
        if self.kids_guard(&channel_id, true).await.is_err() {
            return Ok(VideoPage { items: Vec::new(), cursor: None });
        }
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
        self.kids_guard(&login.to_lowercase(), false).await?;
        let token = self.gql.live_playback_token(&login).await?.ok_or(CoreError::Offline)?;
        if let Some(auth) = &token.authorization {
            if auth.is_forbidden {
                let detail = auth.forbidden_reason_code.clone().unwrap_or_else(|| "lecture refusée".into());
                return Err(CoreError::Unavailable { detail });
            }
        }
        let nonce = u32::from_str_radix(&random_hex(6), 16).unwrap_or(0);
        let master_url = hls::live_master_url(&self.usher, &login, &token.value, &token.signature, nonce);
        self.master_playlist(master_url, token.expires_at(), CoreError::Offline).await
    }

    /// VOD enregistrée pendant le live de `login` (« Depuis le début ») ; `None` si la chaîne
    /// n'archive pas ses lives. Sa lecture passe par `vod_playback`, avec les mêmes contrôles.
    pub async fn live_archive(&self, login: String) -> Result<Option<String>, CoreError> {
        self.gql.live_archive(&login).await
    }

    /// Playlist d'une VOD, en anonyme comme le live : une VOD réservée aux abonnés reste refusée.
    /// Fichiers MP4 d'un clip, du meilleur au plus bas ; vide si le clip n'existe plus.
    // shortcut: pas de contrôle enfants ici, les clips n'arrivent que par des listes déjà filtrées ;
    // à revoir si un lien externe vers un clip est un jour accepté.
    pub async fn clip_playback(&self, slug: String) -> Result<Vec<ClipQuality>, CoreError> {
        self.gql.clip_playback(&slug).await
    }

    pub async fn vod_playback(&self, video_id: String) -> Result<Playback, CoreError> {
        // L'identifiant finit dans un chemin d'URL : uniquement des chiffres.
        if video_id.is_empty() || !video_id.bytes().all(|b| b.is_ascii_digit()) {
            return Err(CoreError::Unavailable { detail: "identifiant de VOD invalide".into() });
        }
        let missing = || CoreError::Unavailable { detail: "VOD introuvable".into() };
        if self.kids()? {
            let video = self.video(video_id.clone()).await?.ok_or_else(missing)?;
            self.kids_guard(&video.channel_id, true).await?;
        }
        let token = self.gql.vod_playback_token(&video_id).await?.ok_or_else(missing)?;
        let nonce = u32::from_str_radix(&random_hex(6), 16).unwrap_or(0);
        let master_url = hls::vod_master_url(&self.usher, &video_id, &token.value, &token.signature, nonce);
        self.master_playlist(master_url, token.expires_at(), missing()).await
    }

    /// Mémorise où en est la VOD pour le profil actif (à appeler hors du thread principal).
    /// Renvoie `true` si elle compte désormais comme terminée.
    /// Rangée « Continuer à regarder » de l'accueil : VOD commencées et pas finies du profil, la plus
    /// récente d'abord, `limit` au plus (une requête chacune, en parallèle). Une VOD supprimée est ignorée.
    pub async fn continue_watching(&self, limit: u32) -> Result<Vec<Video>, CoreError> {
        let ids = self.accounts.db.unfinished_vods(&self.progress_scope()?, limit.min(20))?;
        let found = futures_util::future::join_all(ids.into_iter().map(|id| self.video(id))).await;
        let videos = found.into_iter().filter_map(|v| v.ok().flatten()).collect();
        self.kids_filter(videos, |v: &Video| &v.channel_login).await
    }

    pub fn save_vod_progress(&self, video_id: String, position_s: u32, duration_s: u32) -> Result<bool, CoreError> {
        let now = unix_now();
        self.accounts.db.save_vod_progress(&self.progress_scope()?, &video_id, position_s, duration_s, now)
    }

    /// Chat d'une VOD à partir de `offset_s`, en ordre chronologique. Une liste vide
    /// signifie la fin du chat, ou une VOD sans chat.
    /// Chapitres (changements de jeu) d'une VOD ; vide si elle n'en a pas.
    /// Sondage et prédiction de la chaîne, en lecture seule (GQL anonyme, à relire périodiquement).
    pub async fn channel_activity(&self, login: String) -> Result<crate::catalog::ChannelActivity, CoreError> {
        self.gql.channel_activity(&login).await
    }

    pub async fn vod_chapters(&self, video_id: String) -> Result<Vec<crate::catalog::Chapter>, CoreError> {
        self.gql.video_chapters(&video_id).await
    }

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

    /// Signale les raids lancés par la chaîne `broadcaster_id`. Sans compte connecté, ne fait rien :
    /// EventSub exige un jeton utilisateur.
    pub async fn watch_raids(self: Arc<Self>, broadcaster_id: String, listener: Arc<dyn RaidListener>) -> Arc<RaidWatch> {
        if self.accounts.active_usable_account().ok().flatten().is_none() || self.client_id.is_empty() {
            return Arc::new(RaidWatch { task: None });
        }
        let task = tokio::spawn(self.clone().raid_loop(broadcaster_id, listener));
        Arc::new(RaidWatch { task: Some(task.abort_handle()) })
    }

    /// Émotes que le compte actif peut envoyer dans la chaîne, pour le sélecteur. Vide sans compte.
    pub async fn chat_emotes(&self, broadcaster_id: String) -> Result<Vec<PickerEmote>, CoreError> {
        let channel = broadcaster_id.as_str();
        let twitch = self
            .with_helix(|token: Secret, user_id: String| async move { self.helix.user_emotes(&token, &user_id, channel).await })
            .await?
            .ok_or(CoreError::NotSignedIn)?;
        let third_party = self.settings().unwrap_or_default().get(THIRD_PARTY_EMOTES).map(String::as_str) != Some("false");
        // Même cache que l'habillage du chat : déjà rempli si le chat est ouvert.
        let channel_emotes: EmoteMap = if third_party {
            self.cached(&format!("emotes:{channel}"), HOUR_S, async { Some(emotes::channel(&self.http, channel).await) })
                .await
                .unwrap_or_default()
        } else {
            EmoteMap::new()
        };
        let mut third_party: Vec<(String, String)> = channel_emotes.into_iter().collect();
        third_party.sort_by_cached_key(|e| e.0.to_lowercase());
        Ok(chat::picker_order(channel, twitch, third_party))
    }

    /// Envoie un message avec le compte actif, via Helix pour connaître la raison d'un refus.
    /// `reply_to` : identifiant du message auquel on répond.
    pub async fn send_chat(&self, broadcaster_id: String, text: String, reply_to: Option<String>) -> Result<(), CoreError> {
        let text = text.trim();
        if text.is_empty() {
            return Ok(());
        }
        // Limite de Twitch, en caractères.
        let text: String = text.chars().take(500).collect();
        let (channel, message, reply_to) = (broadcaster_id.as_str(), text.as_str(), reply_to.as_deref());
        self.with_helix(|token: Secret, sender: String| async move {
            self.helix.send_chat_message(&token, channel, &sender, message, reply_to).await
        })
        .await?
        .ok_or(CoreError::NotSignedIn)
    }
}

/// Préférences (portée `global`) et mise à jour de l'app.
#[uniffi::export(async_runtime = "tokio")]
impl Core {
    /// Préférences de l'appareil et du profil actif (`kids_profile` compris, jamais l'empreinte du PIN) ;
    /// une clé absente prend la valeur par défaut de l'app.
    pub fn settings(&self) -> Result<HashMap<String, String>, CoreError> {
        let mut all = self.accounts.db.settings(SETTINGS_SCOPE)?;
        all.retain(|k, _| GLOBAL_SETTINGS.contains(&k.as_str()));
        all.extend(self.accounts.db.settings(&self.progress_scope()?)?);
        all.remove(PIN_HASH);
        Ok(all)
    }

    /// Les préférences de l'appareil sont communes, les autres propres au profil actif.
    pub fn set_setting(&self, key: String, value: String) -> Result<(), CoreError> {
        if key == PIN_HASH || key == KIDS_PROFILE {
            return Err(CoreError::Internal { reason: format!("{key} passe par les fonctions de profil") });
        }
        let scope = if GLOBAL_SETTINGS.contains(&key.as_str()) { SETTINGS_SCOPE.to_owned() } else { self.progress_scope()? };
        self.accounts.db.set_setting(&scope, &key, &value)
    }

    // --- PIN et mode enfants (`profile_id` `None` = l'invité) ---------------------------

    /// `pin` : 4 chiffres, `None` pour retirer le PIN.
    pub fn set_profile_pin(&self, profile_id: Option<String>, pin: Option<String>) -> Result<(), CoreError> {
        self.accounts.set_profile_pin(profile_id.as_deref(), pin.as_deref())
    }

    pub fn check_profile_pin(&self, profile_id: Option<String>, pin: String) -> Result<bool, CoreError> {
        self.accounts.check_profile_pin(profile_id.as_deref(), &pin)
    }

    pub fn set_kids_profile(&self, profile_id: Option<String>, kids: bool) -> Result<(), CoreError> {
        self.accounts.set_kids_profile(profile_id.as_deref(), kids)
    }

    pub fn reset_guest_profile(&self) -> Result<(), CoreError> {
        self.accounts.reset_guest_profile()
    }

    /// Release GitHub plus récente que `current_version`, avec l'APK de la première ABI de `abis`.
    pub async fn check_update(&self, current_version: String, abis: Vec<String>, beta: bool) -> Result<Option<AppUpdate>, CoreError> {
        update::check(&self.http, &self.github, &current_version, &abis, beta).await
    }

    /// Télécharge l'APK d'une mise à jour vers `dest_path` (remplacé s'il existe).
    pub async fn download_update(&self, apk_url: String, dest_path: String) -> Result<(), CoreError> {
        update::download(&self.http, &apk_url, Path::new(&dest_path)).await
    }
}

const SETTINGS_SCOPE: &str = "global";
/// `detail` de `Unavailable` quand le mode enfants refuse une lecture : Kotlin le traduit.
const KIDS_REFUSAL: &str = "kids_profile";
/// Durées de `meta_cache` : les badges et émotes globaux bougent rarement, ceux d'une chaîne un peu plus.
const DAY_S: i64 = 24 * 3600;
const HOUR_S: i64 = 3600;

fn unix_now() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// Raison brute d'un refus d'usher (corps JSON `[{"error": "..."}]`), sans recopier l'URL ;
/// traduite côté Kotlin (`unavailableDetail`). Vide si Twitch n'en donne pas.
fn usher_reason(body: &str) -> String {
    #[derive(serde::Deserialize)]
    struct UsherError {
        #[serde(default)]
        error: String,
    }
    serde_json::from_str::<Vec<UsherError>>(body)
        .ok()
        .and_then(|errors| errors.into_iter().map(|e| e.error).find(|e| !e.is_empty()))
        .unwrap_or_default()
}

/// QR code d'un texte court (lien à ouvrir sur le téléphone).
#[uniffi::export]
pub fn qr_code(text: String) -> Result<crate::qr::QrMatrix, CoreError> {
    crate::qr::encode(&text)
}

#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recommendation_seeds_rank_categories_and_language() {
        let followed: Vec<HelixStream> = serde_json::from_str(
            r#"[{"user_id":"1","user_login":"a","user_name":"A","game_id":"20","language":"fr"},
                {"user_id":"2","user_login":"b","user_name":"B","game_id":"10","language":"fr"},
                {"user_id":"3","user_login":"c","user_name":"C","game_id":"20","language":"en"},
                {"user_id":"4","user_login":"d","user_name":"D","game_id":"","language":"other"}]"#,
        )
        .unwrap();
        assert_eq!(recommendation_seeds(&followed), (vec!["20".to_owned(), "10".to_owned()], Some("fr".to_owned())));
        assert_eq!(recommendation_seeds(&[]), (Vec::new(), None));
    }
}
