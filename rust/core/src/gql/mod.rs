//! API GQL interne de Twitch : lecture des flux et catalogue en mode invité.
//!
//! ⚠ Non officielle et non documentée. Tout ce qui en dépend est isolé ici : si Twitch la
//! modifie, les erreurs remontent en `CoreError::GqlChanged` avec le nom de l'opération,
//! et la correction se limite à ce module.

mod queries;

use std::collections::HashMap;

use serde::de::DeserializeOwned;
use serde::Deserialize;
use serde_json::{json, Value};

use crate::catalog::{
    box_art_template, parse_utc, thumbnail_template, video_thumbnail, Category, CategoryPage, Channel, LiveStream, ReplayComment,
    StreamPage, Video,
};
use crate::chat::{emote_url, readable_color, ChatFragment, ChatMessage};
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

    /// `Ok(None)` : la VOD n'existe pas.
    pub async fn vod_playback_token(&self, video_id: &str) -> Result<Option<PlaybackToken>, CoreError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Data {
            video_playback_access_token: Option<PlaybackToken>,
        }
        let data: Data = self
            .run(
                "PlaybackAccessToken_Template",
                queries::PLAYBACK_ACCESS_TOKEN,
                json!({ "login": "", "isLive": false, "vodID": video_id, "isVod": true, "playerType": "site" }),
            )
            .await?;
        Ok(data.video_playback_access_token)
    }

    pub async fn channel(&self, login: &str) -> Result<Option<Channel>, CoreError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GUser {
            id: String,
            login: String,
            display_name: String,
            #[serde(default)]
            description: Option<String>,
            #[serde(default, rename = "profileImageURL")]
            profile_image_url: Option<String>,
        }
        #[derive(Deserialize)]
        struct Data {
            user: Option<GUser>,
        }
        let data: Data = self.run("Channel", queries::CHANNEL, json!({ "login": login.to_lowercase() })).await?;
        Ok(data.user.map(|u| Channel {
            id: u.id,
            login: u.login,
            display_name: u.display_name,
            avatar_url: u.profile_image_url.unwrap_or_default(),
            description: u.description.unwrap_or_default(),
        }))
    }

    pub async fn channel_videos(&self, channel_id: &str, first: u32) -> Result<Vec<Video>, CoreError> {
        #[derive(Deserialize)]
        struct GUser {
            videos: Option<Connection<GVideo>>,
        }
        #[derive(Deserialize)]
        struct Data {
            user: Option<GUser>,
        }
        let data: Data = self.run("ChannelVideos", &queries::channel_videos(), json!({ "id": channel_id, "first": first })).await?;
        let edges = data.user.and_then(|u| u.videos).map(|c| c.edges).unwrap_or_default();
        Ok(edges.into_iter().map(|e| e.node.into()).collect())
    }

    pub async fn video(&self, id: &str) -> Result<Option<Video>, CoreError> {
        #[derive(Deserialize)]
        struct Data {
            video: Option<GVideo>,
        }
        let data: Data = self.run("Video", &queries::video(), json!({ "id": id })).await?;
        Ok(data.video.map(Video::from))
    }

    /// Chat d'une VOD à partir de `offset_s` ; vide si la VOD n'a pas (ou plus) de chat.
    pub async fn vod_comments(&self, video_id: &str, offset_s: u32) -> Result<Vec<ReplayComment>, CoreError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GComment {
            id: String,
            #[serde(default)]
            content_offset_seconds: u32,
            commenter: Option<GOwner>,
            message: Option<GMessage>,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GMessage {
            #[serde(default)]
            user_color: Option<String>,
            #[serde(default)]
            fragments: Vec<GFragment>,
        }
        #[derive(Deserialize)]
        struct GFragment {
            #[serde(default)]
            text: String,
            emote: Option<GEmote>,
        }
        #[derive(Deserialize)]
        struct GEmote {
            #[serde(rename = "emoteID")]
            emote_id: String,
        }
        #[derive(Deserialize)]
        struct GVideoComments {
            comments: Option<Connection<GComment>>,
        }
        #[derive(Deserialize)]
        struct Data {
            video: Option<GVideoComments>,
        }
        let data: Data = self.run("VodComments", queries::VOD_COMMENTS, json!({ "id": video_id, "offset": offset_s })).await?;
        let edges = data.video.and_then(|v| v.comments).map(|c| c.edges).unwrap_or_default();
        Ok(edges
            .into_iter()
            .filter_map(|edge| {
                let node = edge.node;
                // Compte supprimé : Twitch renvoie un auteur nul.
                let author = node.commenter?;
                let message = node.message?;
                let fragments = message
                    .fragments
                    .into_iter()
                    .map(|f| match f.emote {
                        Some(e) => ChatFragment::Emote { url: emote_url(&e.emote_id), id: e.emote_id, name: f.text },
                        None => ChatFragment::Text { text: f.text },
                    })
                    .collect();
                Some(ReplayComment {
                    offset_s: node.content_offset_seconds,
                    message: ChatMessage {
                        color: readable_color(message.user_color.as_deref().unwrap_or(""), &author.login),
                        id: node.id,
                        user_id: author.id,
                        login: author.login,
                        display_name: author.display_name,
                        fragments,
                        action: false,
                        system: None,
                        timestamp_ms: 0,
                        // Vides en anonyme dans le replay : inutile de les demander.
                        badges: Vec::new(),
                    },
                })
            })
            .collect())
    }

    /// `set/version` → image.
    pub async fn global_badges(&self) -> Result<HashMap<String, String>, CoreError> {
        #[derive(Deserialize)]
        struct Data {
            badges: Option<Vec<Option<GBadge>>>,
        }
        let data: Data = self.run("GlobalBadges", queries::GLOBAL_BADGES, json!({})).await?;
        Ok(badge_map(data.badges.unwrap_or_default()))
    }

    /// Identifiant de la chaîne (pour les émotes tierces) et ses badges ; `None` si elle n'existe pas.
    pub async fn channel_badges(&self, login: &str) -> Result<Option<(String, HashMap<String, String>)>, CoreError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct User {
            id: String,
            broadcast_badges: Option<Vec<Option<GBadge>>>,
        }
        #[derive(Deserialize)]
        struct Data {
            user: Option<User>,
        }
        let data: Data = self.run("ChannelBadges", queries::CHANNEL_BADGES, json!({ "login": login })).await?;
        Ok(data.user.map(|u| (u.id, badge_map(u.broadcast_badges.unwrap_or_default()))))
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

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GVideo {
    id: String,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    length_seconds: u32,
    #[serde(default)]
    published_at: Option<String>,
    #[serde(default)]
    view_count: u32,
    #[serde(default, rename = "previewThumbnailURL")]
    preview_thumbnail_url: Option<String>,
    owner: Option<GOwner>,
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct GOwner {
    id: String,
    login: String,
    display_name: String,
}

impl From<GVideo> for Video {
    fn from(v: GVideo) -> Self {
        let owner = v.owner.unwrap_or_default();
        Video {
            thumbnail_url: video_thumbnail(v.preview_thumbnail_url.as_deref().unwrap_or("")),
            published_at: v.published_at.as_deref().and_then(parse_utc).unwrap_or(0),
            id: v.id,
            title: v.title.unwrap_or_default(),
            channel_id: owner.id,
            channel_login: owner.login,
            channel_name: owner.display_name,
            duration_s: v.length_seconds,
            views: v.view_count,
            position_s: 0,
            finished: false,
        }
    }
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

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GBadge {
    #[serde(rename = "setID")]
    set_id: String,
    version: String,
    #[serde(rename = "imageURL")]
    image_url: String,
}

fn badge_map(badges: Vec<Option<GBadge>>) -> HashMap<String, String> {
    badges.into_iter().flatten().map(|b| (format!("{}/{}", b.set_id, b.version), b.image_url)).collect()
}
