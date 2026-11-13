//! API GQL interne de Twitch : lecture des flux et catalogue en mode invité.
//!
//! ⚠ Non officielle et non documentée. Tout ce qui en dépend est isolé ici : si Twitch la
//! modifie, les erreurs remontent en `CoreError::GqlChanged` avec le nom de l'opération,
//! et la correction se limite à ce module.

mod queries;

use serde::de::DeserializeOwned;
use serde::Deserialize;
use serde_json::{json, Value};

use crate::catalog::{box_art_template, parse_utc, thumbnail_template, Category, CategoryPage, LiveStream, StreamPage};
use crate::error::CoreError;

/// Client ID public du site twitch.tv. GQL n'accepte pas celui de l'application.
/// Il ne donne accès à aucun compte : les requêtes restent anonymes.
pub const WEB_CLIENT_ID: &str = "kimne78kx3ncx6brgo4mv6wki5h1ko";

/// Jeton de lecture anonyme : ni Turbo ni avantages d'abonnement.
#[derive(Debug, Deserialize)]
pub struct PlaybackToken {
    pub value: String,
    pub signature: String,
    #[serde(default)]
    pub authorization: Option<Authorization>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Authorization {
    #[serde(default)]
    pub is_forbidden: bool,
    #[serde(default)]
    pub forbidden_reason_code: Option<String>,
}

pub struct GqlClient {
    http: reqwest::Client,
    url: String,
    device_id: String,
}

impl GqlClient {
    pub fn new(http: reqwest::Client, url: String, device_id: String) -> Self {
        Self { http, url, device_id }
    }

    async fn run<T: DeserializeOwned>(&self, operation: &'static str, query: &str, variables: Value) -> Result<T, CoreError> {
        let changed = |detail: String| CoreError::GqlChanged { operation: operation.into(), detail };
        let response = self
            .http
            .post(&self.url)
            .header("Client-Id", WEB_CLIENT_ID)
            .header("X-Device-Id", &self.device_id)
            .json(&json!({ "operationName": operation, "query": query, "variables": variables }))
            .send()
            .await?;
        let status = response.status();
        if status.is_server_error() {
            return Err(CoreError::Api { status: status.as_u16(), detail: format!("GQL {operation}") });
        }
        if !status.is_success() {
            return Err(changed(format!("HTTP {}", status.as_u16())));
        }
        let envelope: Envelope<T> = response.json().await.map_err(|e| changed(e.without_url().to_string()))?;
        if let Some(error) = envelope.errors.into_iter().next() {
            return Err(changed(error.message));
        }
        envelope.data.ok_or_else(|| changed("réponse sans données".into()))
    }

    /// `Ok(None)` : la chaîne n'existe pas.
    pub async fn live_playback_token(&self, login: &str) -> Result<Option<PlaybackToken>, CoreError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Data {
            stream_playback_access_token: Option<PlaybackToken>,
        }
        let data: Data = self
            .run(
                "PlaybackAccessToken_Template",
                queries::PLAYBACK_ACCESS_TOKEN,
                json!({ "login": login.to_lowercase(), "isLive": true, "vodID": "", "isVod": false, "playerType": "site" }),
            )
            .await?;
        Ok(data.stream_playback_access_token)
    }

    pub async fn top_streams(&self, after: Option<&str>, first: u32) -> Result<StreamPage, CoreError> {
        #[derive(Deserialize)]
        struct Data {
            streams: Option<Connection<GStream>>,
        }
        let data: Data = self.run("TopStreams", &queries::top_streams(), json!({ "first": first, "after": after })).await?;
        Ok(stream_page(data.streams))
    }

    pub async fn category_streams(&self, category_id: &str, after: Option<&str>, first: u32) -> Result<StreamPage, CoreError> {
        #[derive(Deserialize)]
        struct Game {
            streams: Option<Connection<GStream>>,
        }
        #[derive(Deserialize)]
        struct Data {
            game: Option<Game>,
        }
        let data: Data = self
            .run("CategoryStreams", &queries::category_streams(), json!({ "id": category_id, "first": first, "after": after }))
            .await?;
        Ok(stream_page(data.game.and_then(|g| g.streams)))
    }

    pub async fn top_categories(&self, after: Option<&str>, first: u32) -> Result<CategoryPage, CoreError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GCategory {
            id: String,
            display_name: String,
            #[serde(default)]
            viewers_count: Option<u32>,
        }
        #[derive(Deserialize)]
        struct Data {
            games: Option<Connection<GCategory>>,
        }
        let data: Data = self.run("TopCategories", queries::TOP_CATEGORIES, json!({ "first": first, "after": after })).await?;
        let Some(games) = data.games else { return Ok(CategoryPage { items: Vec::new(), cursor: None }) };
        let cursor = games.next_cursor();
        let items = games
            .edges
            .into_iter()
            .map(|edge| Category {
                box_art_url: box_art_template(&edge.node.id),
                id: edge.node.id,
                name: edge.node.display_name,
                viewers: edge.node.viewers_count.unwrap_or(0),
            })
            .collect();
        Ok(CategoryPage { items, cursor })
    }

    /// `Ok(None)` : chaîne inexistante ou hors ligne.
    pub async fn channel_stream(&self, login: &str) -> Result<Option<LiveStream>, CoreError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GChannel {
            id: String,
            login: String,
            display_name: String,
            #[serde(default, rename = "profileImageURL")]
            profile_image_url: Option<String>,
            #[serde(default)]
            broadcast_settings: Option<BroadcastSettings>,
            stream: Option<GChannelStream>,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GChannelStream {
            #[serde(default)]
            viewers_count: u32,
            #[serde(default)]
            created_at: Option<String>,
            game: Option<GGame>,
        }
        #[derive(Deserialize)]
        struct Data {
            user: Option<GChannel>,
        }
        let data: Data = self.run("ChannelStream", queries::CHANNEL_STREAM, json!({ "login": login.to_lowercase() })).await?;
        let Some(user) = data.user else { return Ok(None) };
        let Some(stream) = user.stream else { return Ok(None) };
        let game = stream.game.unwrap_or_default();
        Ok(Some(LiveStream {
            thumbnail_url: thumbnail_template(&user.login),
            channel_id: user.id,
            login: user.login,
            display_name: user.display_name,
            avatar_url: user.profile_image_url.unwrap_or_default(),
            title: user.broadcast_settings.and_then(|b| b.title).unwrap_or_default(),
            category_id: game.id,
            category_name: game.display_name,
            viewers: stream.viewers_count,
            started_at: stream.created_at.as_deref().and_then(parse_utc).unwrap_or(0),
        }))
    }
}

#[derive(Deserialize)]
struct Envelope<T> {
    data: Option<T>,
    #[serde(default)]
    errors: Vec<GqlError>,
}

#[derive(Deserialize)]
struct GqlError {
    #[serde(default)]
    message: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Connection<T> {
    #[serde(default = "Vec::new")]
    edges: Vec<Edge<T>>,
    #[serde(default)]
    page_info: PageInfo,
}

impl<T> Connection<T> {
    fn next_cursor(&self) -> Option<String> {
        if !self.page_info.has_next_page {
            return None;
        }
        self.edges.last().and_then(|e| e.cursor.clone()).filter(|c| !c.is_empty())
    }
}

#[derive(Deserialize)]
struct Edge<T> {
    #[serde(default)]
    cursor: Option<String>,
    node: T,
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct PageInfo {
    #[serde(default)]
    has_next_page: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GStream {
    #[serde(default)]
    viewers_count: u32,
    #[serde(default)]
    created_at: Option<String>,
    broadcaster: Option<GBroadcaster>,
    game: Option<GGame>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GBroadcaster {
    id: String,
    login: String,
    display_name: String,
    #[serde(default, rename = "profileImageURL")]
    profile_image_url: Option<String>,
    #[serde(default)]
    broadcast_settings: Option<BroadcastSettings>,
}

#[derive(Deserialize)]
struct BroadcastSettings {
    #[serde(default)]
    title: Option<String>,
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct GGame {
    #[serde(default)]
    id: String,
    #[serde(default)]
    display_name: String,
}

fn stream_page(connection: Option<Connection<GStream>>) -> StreamPage {
    let Some(connection) = connection else { return StreamPage { items: Vec::new(), cursor: None } };
    let cursor = connection.next_cursor();
    let items = connection
        .edges
        .into_iter()
        .filter_map(|edge| {
            // Chaîne suspendue ou supprimée : Twitch renvoie un diffuseur nul.
            let broadcaster = edge.node.broadcaster?;
            let game = edge.node.game.unwrap_or_default();
            Some(LiveStream {
                thumbnail_url: thumbnail_template(&broadcaster.login),
                channel_id: broadcaster.id,
                login: broadcaster.login,
                display_name: broadcaster.display_name,
                avatar_url: broadcaster.profile_image_url.unwrap_or_default(),
                title: broadcaster.broadcast_settings.and_then(|b| b.title).unwrap_or_default(),
                category_id: game.id,
                category_name: game.display_name,
                viewers: edge.node.viewers_count,
                started_at: edge.node.created_at.as_deref().and_then(parse_utc).unwrap_or(0),
            })
        })
        .collect();
    StreamPage { items, cursor }
}
