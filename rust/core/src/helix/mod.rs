//! API officielle Twitch (Helix). Chaque appel porte le Client ID de l'app et un jeton utilisateur.

use std::time::Duration;

use serde::de::DeserializeOwned;
use serde::Deserialize;

use crate::auth::api_error;
use crate::catalog::{
    box_art_template, parse_duration, parse_utc, thumbnail_template, video_thumbnail, Category, Channel, LiveStream, SearchChannel,
    Video,
};
use crate::error::CoreError;
use crate::secret::Secret;

/// Un 429 n'est réessayé que si Twitch promet une réinitialisation proche.
const MAX_RATE_LIMIT_WAIT: Duration = Duration::from_secs(5);

#[derive(Debug, Deserialize)]
pub struct HelixUser {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub login: String,
    pub display_name: String,
    #[serde(default)]
    pub profile_image_url: String,
    #[serde(default)]
    pub description: String,
}

impl From<HelixUser> for Channel {
    fn from(user: HelixUser) -> Self {
        Channel {
            id: user.id,
            login: user.login,
            display_name: user.display_name,
            avatar_url: user.profile_image_url,
            description: user.description,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct HelixVideo {
    pub id: String,
    #[serde(default)]
    pub user_id: String,
    #[serde(default)]
    pub user_login: String,
    #[serde(default)]
    pub user_name: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub published_at: String,
    #[serde(default)]
    pub thumbnail_url: String,
    #[serde(default)]
    pub view_count: u32,
    #[serde(default)]
    pub duration: String,
}

impl From<HelixVideo> for Video {
    fn from(v: HelixVideo) -> Self {
        Video {
            thumbnail_url: video_thumbnail(&v.thumbnail_url),
            published_at: parse_utc(&v.published_at).unwrap_or(0),
            duration_s: parse_duration(&v.duration),
            id: v.id,
            title: v.title,
            channel_id: v.user_id,
            channel_login: v.user_login,
            channel_name: v.user_name,
            views: v.view_count,
            position_s: 0,
            finished: false,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct HelixFollow {
    pub broadcaster_id: String,
    pub broadcaster_login: String,
    pub broadcaster_name: String,
}

#[derive(Debug, Deserialize)]
pub struct HelixStream {
    pub user_id: String,
    pub user_login: String,
    pub user_name: String,
    #[serde(default)]
    pub game_id: String,
    #[serde(default)]
    pub game_name: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub viewer_count: u32,
    #[serde(default)]
    pub started_at: String,
    /// Code ISO 639-1 (`fr`, `en`…).
    #[serde(default)]
    pub language: String,
}

impl HelixStream {
    pub fn into_live(self, avatar_url: String) -> LiveStream {
        LiveStream {
            thumbnail_url: thumbnail_template(&self.user_login),
            started_at: parse_utc(&self.started_at).unwrap_or(0),
            channel_id: self.user_id,
            login: self.user_login,
            display_name: self.user_name,
            avatar_url,
            title: self.title,
            category_id: self.game_id,
            category_name: self.game_name,
            viewers: self.viewer_count,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct HelixSearchChannel {
    pub id: String,
    pub broadcaster_login: String,
    pub display_name: String,
    #[serde(default)]
    pub thumbnail_url: String,
    #[serde(default)]
    pub is_live: bool,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub game_name: String,
}

impl From<HelixSearchChannel> for SearchChannel {
    fn from(c: HelixSearchChannel) -> Self {
        SearchChannel {
            // Hors ligne, Helix renvoie le titre du dernier live : sans intérêt ici.
            title: if c.is_live { c.title } else { String::new() },
            category_name: if c.is_live { c.game_name } else { String::new() },
            id: c.id,
            login: c.broadcaster_login,
            display_name: c.display_name,
            avatar_url: c.thumbnail_url,
            live: c.is_live,
            viewers: 0,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct HelixGame {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub box_art_url: String,
}

impl From<HelixGame> for Category {
    fn from(game: HelixGame) -> Self {
        let box_art_url = if game.box_art_url.contains("{width}") {
            game.box_art_url
        } else {
            box_art_template(&game.id)
        };
        Category { id: game.id, name: game.name, viewers: 0, box_art_url }
    }
}

#[derive(Debug, Default, Deserialize)]
pub struct Pagination {
    pub cursor: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct Page<T> {
    pub data: Vec<T>,
    #[serde(default)]
    pub pagination: Pagination,
}

impl<T> Page<T> {
    /// Twitch renvoie parfois un curseur avec une page vide : on le traite comme la fin.
    pub fn next_cursor(&self) -> Option<String> {
        self.pagination.cursor.clone().filter(|c| !c.is_empty() && !self.data.is_empty())
    }
}

pub struct HelixClient {
    http: reqwest::Client,
    base: String,
    client_id: String,
}

impl HelixClient {
    pub fn new(http: reqwest::Client, base: String, client_id: String) -> Self {
        Self { http, base, client_id }
    }

    /// Un 401 remonte en `CoreError::Api { status: 401 }` : l'appelant renouvelle le jeton.
    async fn get<T: DeserializeOwned>(&self, path: &str, query: &[(&str, &str)], token: &Secret) -> Result<T, CoreError> {
        let mut retried = false;
        loop {
            let response = self
                .http
                .get(format!("{}{}", self.base, path))
                .header("Client-Id", &self.client_id)
                .bearer_auth(token.expose())
                .query(query)
                .send()
                .await?;
            if response.status().as_u16() == 429 && !retried {
                if let Some(wait) = rate_limit_wait(&response) {
                    retried = true;
                    tokio::time::sleep(wait).await;
                    continue;
                }
            }
            if !response.status().is_success() {
                return Err(api_error(response).await);
            }
            return Ok(response.json().await?);
        }
    }

    /// Envoie un message au nom du compte. Un refus de Twitch (mode abonnés, AutoMod…)
    /// arrive en 200 avec `is_sent: false` et une raison lisible.
    pub async fn send_chat_message(
        &self,
        token: &Secret,
        broadcaster_id: &str,
        sender_id: &str,
        message: &str,
    ) -> Result<(), CoreError> {
        #[derive(Deserialize)]
        struct DropReason {
            #[serde(default)]
            message: String,
        }
        #[derive(Deserialize)]
        struct Sent {
            is_sent: bool,
            drop_reason: Option<DropReason>,
        }
        let response = self
            .http
            .post(format!("{}/chat/messages", self.base))
            .header("Client-Id", &self.client_id)
            .bearer_auth(token.expose())
            .json(&serde_json::json!({ "broadcaster_id": broadcaster_id, "sender_id": sender_id, "message": message }))
            .send()
            .await?;
        if !response.status().is_success() {
            return Err(api_error(response).await);
        }
        let page: Page<Sent> = response.json().await?;
        match page.data.into_iter().next() {
            Some(Sent { is_sent: true, .. }) => Ok(()),
            Some(Sent { drop_reason, .. }) => Err(CoreError::ChatRejected {
                detail: drop_reason.map(|r| r.message).filter(|m| !m.is_empty()).unwrap_or_else(|| "refusé par Twitch".into()),
            }),
            None => Err(CoreError::Api { status: 200, detail: "réponse d'envoi vide".into() }),
        }
    }

    /// Profil du propriétaire du jeton.
    pub async fn current_user(&self, token: &Secret) -> Result<HelixUser, CoreError> {
        let page: Page<HelixUser> = self.get("/users", &[], token).await?;
        page.data
            .into_iter()
            .next()
            .ok_or(CoreError::Api { status: 200, detail: "profil absent de la réponse".into() })
    }

    /// Profils par identifiant (100 au plus par appel).
    pub async fn users(&self, token: &Secret, ids: &[&str]) -> Result<Vec<HelixUser>, CoreError> {
        if ids.is_empty() {
            return Ok(Vec::new());
        }
        let query: Vec<(&str, &str)> = ids.iter().take(100).map(|id| ("id", *id)).collect();
        let page: Page<HelixUser> = self.get("/users", &query, token).await?;
        Ok(page.data)
    }

    pub async fn user_by_login(&self, token: &Secret, login: &str) -> Result<Option<HelixUser>, CoreError> {
        let page: Page<HelixUser> = self.get("/users", &[("login", &login.to_lowercase())], token).await?;
        Ok(page.data.into_iter().next())
    }

    /// Pseudos (minuscules) des utilisateurs bloqués par le compte, toutes pages confondues.
    pub async fn blocked_logins(&self, token: &Secret, user_id: &str) -> Result<Vec<String>, CoreError> {
        #[derive(Deserialize)]
        struct Blocked {
            user_login: String,
        }
        let mut logins = Vec::new();
        let mut after: Option<String> = None;
        // shortcut: 10 pages (1000 comptes) au plus, largement assez pour une liste de blocage.
        for _ in 0..10 {
            let mut query = vec![("broadcaster_id", user_id), ("first", "100")];
            if let Some(after) = after.as_deref() {
                query.push(("after", after));
            }
            let page: Page<Blocked> = self.get("/users/blocks", &query, token).await?;
            after = page.next_cursor();
            logins.extend(page.data.into_iter().map(|b| b.user_login.to_lowercase()));
            if after.is_none() {
                break;
            }
        }
        Ok(logins)
    }

    pub async fn search_channels(&self, token: &Secret, query: &str, first: u32) -> Result<Vec<HelixSearchChannel>, CoreError> {
        let first = first.clamp(1, 100).to_string();
        let page: Page<HelixSearchChannel> = self.get("/search/channels", &[("query", query), ("first", &first)], token).await?;
        Ok(page.data)
    }

    pub async fn search_categories(&self, token: &Secret, query: &str, first: u32) -> Result<Vec<HelixGame>, CoreError> {
        let first = first.clamp(1, 100).to_string();
        let page: Page<HelixGame> = self.get("/search/categories", &[("query", query), ("first", &first)], token).await?;
        Ok(page.data)
    }

    /// Abonne une session EventSub WebSocket à un événement (aucun scope requis pour `channel.raid`).
    pub async fn subscribe_eventsub(&self, token: &Secret, kind: &str, condition: serde_json::Value, session_id: &str) -> Result<(), CoreError> {
        let response = self
            .http
            .post(format!("{}/eventsub/subscriptions", self.base))
            .header("Client-Id", &self.client_id)
            .bearer_auth(token.expose())
            .json(&serde_json::json!({
                "type": kind,
                "version": "1",
                "condition": condition,
                "transport": { "method": "websocket", "session_id": session_id },
            }))
            .send()
            .await?;
        if !response.status().is_success() {
            return Err(api_error(response).await);
        }
        Ok(())
    }

    /// Chaînes suivies, en live ou non, de la plus récemment suivie à la plus ancienne.
    pub async fn followed_channels(&self, token: &Secret, user_id: &str, after: Option<&str>) -> Result<Page<HelixFollow>, CoreError> {
        let mut query = vec![("user_id", user_id), ("first", "100")];
        if let Some(after) = after {
            query.push(("after", after));
        }
        self.get("/channels/followed", &query, token).await
    }

    /// VOD d'une chaîne, des plus récentes aux plus anciennes.
    pub async fn channel_videos(&self, token: &Secret, user_id: &str, after: Option<&str>, first: u32) -> Result<Page<HelixVideo>, CoreError> {
        let first = first.clamp(1, 100).to_string();
        let mut query = vec![("user_id", user_id), ("first", first.as_str())];
        if let Some(after) = after {
            query.push(("after", after));
        }
        self.get("/videos", &query, token).await
    }

    /// Une VOD supprimée ou inconnue répond 404 chez Helix : on la traite comme absente.
    pub async fn video(&self, token: &Secret, id: &str) -> Result<Option<HelixVideo>, CoreError> {
        match self.get::<Page<HelixVideo>>("/videos", &[("id", id)], token).await {
            Ok(page) => Ok(page.data.into_iter().next()),
            Err(CoreError::Api { status: 404, .. }) => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// Lives suivis par l'utilisateur, triés par spectateurs.
    pub async fn followed_streams(
        &self,
        token: &Secret,
        user_id: &str,
        after: Option<&str>,
    ) -> Result<Page<HelixStream>, CoreError> {
        let mut query = vec![("user_id", user_id), ("first", "100")];
        if let Some(after) = after {
            query.push(("after", after));
        }
        self.get("/streams/followed", &query, token).await
    }

    /// Lives les plus regardés, éventuellement limités à une catégorie.
    pub async fn streams(
        &self,
        token: &Secret,
        category_id: Option<&str>,
        language: Option<&str>,
        after: Option<&str>,
        first: u32,
    ) -> Result<Page<HelixStream>, CoreError> {
        let first = first.clamp(1, 100).to_string();
        let mut query = vec![("first", first.as_str())];
        if let Some(id) = category_id {
            query.push(("game_id", id));
        }
        if let Some(language) = language {
            query.push(("language", language));
        }
        if let Some(after) = after {
            query.push(("after", after));
        }
        self.get("/streams", &query, token).await
    }

    pub async fn stream_by_login(&self, token: &Secret, login: &str) -> Result<Option<HelixStream>, CoreError> {
        let page: Page<HelixStream> = self.get("/streams", &[("user_login", login)], token).await?;
        Ok(page.data.into_iter().next())
    }

    pub async fn top_categories(&self, token: &Secret, after: Option<&str>, first: u32) -> Result<Page<HelixGame>, CoreError> {
        let first = first.clamp(1, 100).to_string();
        let mut query = vec![("first", first.as_str())];
        if let Some(after) = after {
            query.push(("after", after));
        }
        self.get("/games/top", &query, token).await
    }
}

/// Attente avant réessai, d'après l'en-tête `Ratelimit-Reset` (secondes Unix).
fn rate_limit_wait(response: &reqwest::Response) -> Option<Duration> {
    let reset: u64 = response.headers().get("Ratelimit-Reset")?.to_str().ok()?.parse().ok()?;
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).ok()?.as_secs();
    let wait = Duration::from_secs(reset.saturating_sub(now).max(1));
    (wait <= MAX_RATE_LIMIT_WAIT).then_some(wait)
}
