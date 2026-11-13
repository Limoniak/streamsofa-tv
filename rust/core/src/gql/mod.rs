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
    box_art_template, parse_utc, thumbnail_template, video_thumbnail, Category, CategoryPage, Channel, ChannelAbout, ChannelActivity, Chapter,
    Clip, ClipQuality, LiveStream, Panel, Poll, PollChoice, Prediction, PredictionOutcome, PredictionStatus, ReplayComment, ScheduleSegment, SearchChannel, SearchResults, StreamPage, Video,
};
use crate::hls::url_query;
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

    /// Chapitres d'une VOD, dans l'ordre ; vide si Twitch n'en a pas.
    pub async fn video_chapters(&self, id: &str) -> Result<Vec<Chapter>, CoreError> {
        #[derive(Deserialize)]
        struct GGame {
            #[serde(default, rename = "displayName")]
            display_name: String,
        }
        #[derive(Deserialize)]
        struct GDetails {
            game: Option<GGame>,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GMoment {
            #[serde(default)]
            position_milliseconds: u64,
            #[serde(default)]
            description: String,
            details: Option<GDetails>,
        }
        #[derive(Deserialize)]
        struct GVideo {
            moments: Option<Connection<GMoment>>,
        }
        #[derive(Deserialize)]
        struct Data {
            video: Option<GVideo>,
        }
        let data: Data = self.run("VideoChapters", queries::VIDEO_CHAPTERS, json!({ "id": id })).await?;
        let mut chapters: Vec<Chapter> = data
            .video
            .and_then(|v| v.moments)
            .map(|m| m.edges)
            .unwrap_or_default()
            .into_iter()
            .map(|e| {
                let m = e.node;
                let game = m.details.and_then(|d| d.game).map(|g| g.display_name).filter(|n| !n.is_empty());
                Chapter { position_ms: m.position_milliseconds, title: game.unwrap_or(m.description) }
            })
            .collect();
        chapters.sort_by_key(|c| c.position_ms);
        Ok(chapters)
    }

    /// Sondage visible (en cours ou tout juste terminé) et prédiction en cours.
    pub async fn channel_activity(&self, login: &str) -> Result<ChannelActivity, CoreError> {
        #[derive(Deserialize)]
        struct GVotes {
            #[serde(default)]
            total: u32,
        }
        #[derive(Deserialize)]
        struct GChoice {
            #[serde(default)]
            title: String,
            votes: Option<GVotes>,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GPoll {
            #[serde(default)]
            title: String,
            #[serde(default)]
            status: String,
            #[serde(default)]
            remaining_duration_milliseconds: u64,
            votes: Option<GVotes>,
            #[serde(default)]
            choices: Vec<GChoice>,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GOutcome {
            #[serde(default)]
            id: String,
            #[serde(default)]
            title: String,
            #[serde(default)]
            total_points: u64,
            #[serde(default)]
            total_users: u32,
        }
        #[derive(Deserialize)]
        struct GId {
            id: String,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GPrediction {
            #[serde(default)]
            title: String,
            #[serde(default)]
            status: String,
            #[serde(default)]
            created_at: String,
            #[serde(default)]
            prediction_window_seconds: i64,
            #[serde(default)]
            outcomes: Vec<GOutcome>,
            winning_outcome: Option<GId>,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GChannel {
            #[serde(default)]
            active_prediction_events: Vec<GPrediction>,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GUser {
            viewable_poll: Option<GPoll>,
            channel: Option<GChannel>,
        }
        #[derive(Deserialize)]
        struct Data {
            user: Option<GUser>,
        }
        let data: Data = self.run("ChannelActivity", queries::CHANNEL_ACTIVITY, json!({ "login": login.to_lowercase() })).await?;
        let Some(user) = data.user else { return Ok(ChannelActivity { poll: None, prediction: None }) };
        let poll = user.viewable_poll.and_then(|p| {
            // Archivé, modéré ou invalide : plus rien à montrer.
            let active = match p.status.as_str() {
                "ACTIVE" => true,
                "COMPLETED" | "TERMINATED" => false,
                _ => return None,
            };
            Some(Poll {
                title: p.title,
                total_votes: p.votes.map_or(0, |v| v.total),
                choices: p.choices.into_iter().map(|c| PollChoice { title: c.title, votes: c.votes.map_or(0, |v| v.total) }).collect(),
                active,
                remaining_ms: p.remaining_duration_milliseconds,
            })
        });
        let prediction = user.channel.and_then(|c| c.active_prediction_events.into_iter().next()).and_then(|p| {
            let status = match p.status.as_str() {
                "ACTIVE" => PredictionStatus::Active,
                "LOCKED" | "RESOLVE_PENDING" => PredictionStatus::Locked,
                "RESOLVED" => PredictionStatus::Resolved,
                // Annulée : rien à montrer.
                _ => return None,
            };
            let winner = p.winning_outcome.map(|w| w.id);
            Some(Prediction {
                title: p.title,
                status,
                locks_at_s: parse_utc(&p.created_at).map_or(0, |t| t + p.prediction_window_seconds),
                outcomes: p
                    .outcomes
                    .into_iter()
                    .map(|o| PredictionOutcome {
                        winner: winner.as_deref() == Some(o.id.as_str()),
                        title: o.title,
                        points: o.total_points,
                        users: o.total_users,
                    })
                    .collect(),
            })
        });
        Ok(ChannelActivity { poll, prediction })
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
                        reply_to: None,
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

    pub async fn search(&self, query: &str) -> Result<SearchResults, CoreError> {
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
            #[serde(default)]
            stream: Option<GLive>,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GLive {
            #[serde(default)]
            viewers_count: u32,
            #[serde(default)]
            game: Option<GGame>,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GCategory {
            id: String,
            display_name: String,
            #[serde(default)]
            viewers_count: u32,
        }
        #[derive(Deserialize)]
        struct Items<T> {
            #[serde(default = "Vec::new")]
            items: Vec<T>,
        }
        #[derive(Deserialize)]
        struct GSearch {
            channels: Option<Items<GChannel>>,
            games: Option<Items<GCategory>>,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Data {
            search_for: Option<GSearch>,
        }
        let data: Data = self.run("Search", queries::SEARCH, json!({ "query": query })).await?;
        let Some(search) = data.search_for else { return Ok(SearchResults { channels: Vec::new(), categories: Vec::new() }) };
        let channels = search
            .channels
            .map(|c| c.items)
            .unwrap_or_default()
            .into_iter()
            .map(|c| {
                let live = c.stream.is_some();
                SearchChannel {
                    title: if live { c.broadcast_settings.and_then(|b| b.title).unwrap_or_default() } else { String::new() },
                    category_name: c.stream.as_ref().and_then(|s| s.game.as_ref()).map(|g| g.display_name.clone()).unwrap_or_default(),
                    viewers: c.stream.map(|s| s.viewers_count).unwrap_or(0),
                    id: c.id,
                    login: c.login,
                    display_name: c.display_name,
                    avatar_url: c.profile_image_url.unwrap_or_default(),
                    live,
                }
            })
            .collect();
        let categories = search
            .games
            .map(|g| g.items)
            .unwrap_or_default()
            .into_iter()
            .map(|g| Category { box_art_url: box_art_template(&g.id), id: g.id, name: g.display_name, viewers: g.viewers_count })
            .collect();
        Ok(SearchResults { channels, categories })
    }

    /// `all_time` : sinon, les clips des 7 derniers jours.
    pub async fn clips(&self, login: &str, all_time: bool, first: u32) -> Result<Vec<Clip>, CoreError> {
        #[derive(Deserialize)]
        struct GUser {
            clips: Option<Connection<GClip>>,
        }
        #[derive(Deserialize)]
        struct Data {
            user: Option<GUser>,
        }
        let period = if all_time { "ALL_TIME" } else { "LAST_WEEK" };
        let data: Data = self.run("Clips", queries::CLIPS, json!({ "login": login, "first": first, "period": period })).await?;
        Ok(clip_list(data.user.and_then(|u| u.clips)))
    }

    /// `language` : code ISO 639-1 (`fr`), `None` pour toutes les langues.
    /// Parmi `keys` (logins, ou ids si `by_id`), celles des chaînes tout public. Une chaîne
    /// inconnue ou sans réglages lisibles est exclue : le mode enfants échoue fermé.
    pub async fn safe_channels(&self, keys: &[String], by_id: bool) -> Result<std::collections::HashSet<String>, CoreError> {
        #[derive(Deserialize)]
        struct GSettings {
            #[serde(default, rename = "isMature")]
            is_mature: Option<bool>,
        }
        #[derive(Deserialize)]
        struct GLabel {}
        #[derive(Deserialize)]
        struct GChannel {
            #[serde(default, rename = "contentClassificationLabels")]
            labels: Option<Vec<GLabel>>,
        }
        #[derive(Deserialize)]
        struct GUser {
            id: String,
            login: String,
            #[serde(default, rename = "broadcastSettings")]
            settings: Option<GSettings>,
            #[serde(default)]
            channel: Option<GChannel>,
        }
        #[derive(Deserialize)]
        struct Data {
            #[serde(default)]
            users: Vec<Option<GUser>>,
        }
        let (operation, query) = if by_id { ("SafeById", queries::SAFE_BY_ID) } else { ("SafeByLogin", queries::SAFE_BY_LOGIN) };
        let mut safe = std::collections::HashSet::new();
        for chunk in keys.chunks(SAFE_CHUNK) {
            let data: Data = self.run(operation, query, json!({ "keys": chunk })).await?;
            for user in data.users.into_iter().flatten() {
                let mature = user.settings.and_then(|s| s.is_mature).unwrap_or(true);
                let labelled = user.channel.and_then(|c| c.labels).is_some_and(|l| !l.is_empty());
                if !mature && !labelled {
                    safe.insert(if by_id { user.id } else { user.login.to_lowercase() });
                }
            }
        }
        Ok(safe)
    }

    pub async fn category_clips(&self, category_id: &str, language: Option<&str>, first: u32) -> Result<Vec<Clip>, CoreError> {
        #[derive(Deserialize)]
        struct GGame {
            clips: Option<Connection<GClip>>,
        }
        #[derive(Deserialize)]
        struct Data {
            game: Option<GGame>,
        }
        let languages = language.map(|l| vec![l.to_uppercase()]);
        let variables = json!({ "id": category_id, "first": first, "languages": languages });
        let data: Data = self.run("CategoryClips", queries::CATEGORY_CLIPS, variables).await?;
        Ok(clip_list(data.game.and_then(|g| g.clips)))
    }

    /// Fichiers d'un clip, de la meilleure qualité à la plus basse ; vide si le clip n'existe plus.
    pub async fn clip_playback(&self, slug: &str) -> Result<Vec<ClipQuality>, CoreError> {
        #[derive(Deserialize)]
        struct GQuality {
            #[serde(default)]
            quality: String,
            #[serde(rename = "sourceURL")]
            source_url: String,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GClip {
            playback_access_token: Option<PlaybackToken>,
            #[serde(default)]
            video_qualities: Vec<GQuality>,
        }
        #[derive(Deserialize)]
        struct Data {
            clip: Option<GClip>,
        }
        let data: Data = self.run("ClipPlayback", queries::CLIP_PLAYBACK, json!({ "slug": slug })).await?;
        let Some(clip) = data.clip else { return Ok(Vec::new()) };
        let token = clip
            .playback_access_token
            .ok_or_else(|| CoreError::GqlChanged { operation: "ClipPlayback".into(), detail: "jeton absent".into() })?;
        // Les fichiers refusent une requête sans le jeton anonyme qui les accompagne.
        let signature = url_query(&[("sig", &token.signature), ("token", &token.value)]);
        let mut qualities: Vec<ClipQuality> = clip
            .video_qualities
            .into_iter()
            .map(|q| ClipQuality { height: q.quality.parse().unwrap_or(0), url: format!("{}?{signature}", q.source_url) })
            .collect();
        qualities.sort_by(|a, b| b.height.cmp(&a.height));
        Ok(qualities)
    }

    /// Planning à venir (`now` en secondes Unix) et panneaux d'une chaîne ; `None` si elle n'existe pas.
    pub async fn channel_about(&self, login: &str, now: i64) -> Result<Option<ChannelAbout>, CoreError> {
        #[derive(Deserialize)]
        struct GPanel {
            #[serde(default)]
            title: Option<String>,
            #[serde(default)]
            description: Option<String>,
            #[serde(default, rename = "imageURL")]
            image_url: Option<String>,
            #[serde(default, rename = "linkURL")]
            link_url: Option<String>,
        }
        #[derive(Deserialize)]
        struct GName {
            #[serde(default)]
            name: String,
        }
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct GSegment {
            start_at: String,
            #[serde(default)]
            end_at: Option<String>,
            #[serde(default)]
            title: Option<String>,
            #[serde(default)]
            is_cancelled: bool,
            #[serde(default)]
            categories: Option<Vec<GName>>,
        }
        #[derive(Deserialize)]
        struct GSchedule {
            #[serde(default)]
            segments: Option<Vec<GSegment>>,
        }
        #[derive(Deserialize)]
        struct GChannel {
            #[serde(default)]
            schedule: Option<GSchedule>,
        }
        #[derive(Deserialize)]
        struct GUser {
            // Un panneau d'extension arrive vide (`{}`) : il est écarté plus bas.
            #[serde(default)]
            panels: Option<Vec<Option<GPanel>>>,
            #[serde(default)]
            channel: Option<GChannel>,
        }
        #[derive(Deserialize)]
        struct Data {
            user: Option<GUser>,
        }
        let data: Data = self.run("ChannelAbout", queries::CHANNEL_ABOUT, json!({ "login": login })).await?;
        let Some(user) = data.user else { return Ok(None) };
        let panels = user
            .panels
            .unwrap_or_default()
            .into_iter()
            .flatten()
            .map(|p| Panel {
                title: p.title.unwrap_or_default(),
                description: p.description.unwrap_or_default(),
                image_url: p.image_url.unwrap_or_default(),
                link_url: p.link_url.unwrap_or_default(),
            })
            .filter(|p| !(p.title.is_empty() && p.description.is_empty() && p.image_url.is_empty()))
            .collect();
        let schedule = user
            .channel
            .and_then(|c| c.schedule)
            .and_then(|s| s.segments)
            .unwrap_or_default()
            .into_iter()
            .filter(|s| !s.is_cancelled)
            .filter_map(|s| {
                let start_at = parse_utc(&s.start_at)?;
                let end_at = s.end_at.as_deref().and_then(parse_utc).unwrap_or(0);
                // Créneau passé (ou commencé depuis plus de 12 h sans fin annoncée) : sans intérêt.
                let over = if end_at > 0 { end_at <= now } else { start_at + 12 * 3600 <= now };
                (!over).then(|| ScheduleSegment {
                    start_at,
                    end_at,
                    title: s.title.unwrap_or_default(),
                    category_name: s.categories.unwrap_or_default().into_iter().next().map(|c| c.name).unwrap_or_default(),
                })
            })
            .collect();
        Ok(Some(ChannelAbout { schedule, panels }))
    }

    pub async fn top_streams(&self, after: Option<&str>, first: u32) -> Result<StreamPage, CoreError> {
        #[derive(Deserialize)]
        struct Data {
            streams: Option<Connection<GStream>>,
        }
        let data: Data = self.run("TopStreams", &queries::top_streams(), json!({ "first": first, "after": after })).await?;
        Ok(stream_page(data.streams))
    }

    /// `language` : code ISO 639-1 (`fr`), sinon toutes les langues.
    pub async fn category_streams(
        &self,
        category_id: &str,
        language: Option<&str>,
        after: Option<&str>,
        first: u32,
    ) -> Result<StreamPage, CoreError> {
        #[derive(Deserialize)]
        struct Game {
            streams: Option<Connection<GStream>>,
        }
        #[derive(Deserialize)]
        struct Data {
            game: Option<Game>,
        }
        let data: Data = self
            .run("CategoryStreams", &queries::category_streams(), json!({ "id": category_id, "first": first, "after": after, "languages": language.map(|l| vec![l]) }))
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

/// Clés par requête `users` du mode enfants.
const SAFE_CHUNK: usize = 50;

#[derive(Deserialize)]
struct GName {
    #[serde(default)]
    login: String,
    #[serde(default, rename = "displayName")]
    display_name: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GClip {
    slug: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    duration_seconds: u32,
    #[serde(default)]
    view_count: u32,
    #[serde(default)]
    created_at: Option<String>,
    #[serde(default, rename = "thumbnailURL")]
    thumbnail_url: Option<String>,
    #[serde(default)]
    curator: Option<GName>,
    #[serde(default)]
    broadcaster: Option<GName>,
}

fn clip_list(connection: Option<Connection<GClip>>) -> Vec<Clip> {
    connection
        .map(|c| c.edges)
        .unwrap_or_default()
        .into_iter()
        .map(|edge| {
            let c = edge.node;
            Clip {
                created_at: c.created_at.as_deref().and_then(parse_utc).unwrap_or(0),
                slug: c.slug,
                title: c.title,
                duration_s: c.duration_seconds,
                views: c.view_count,
                thumbnail_url: c.thumbnail_url.unwrap_or_default(),
                curator: c.curator.map(|c| c.display_name).unwrap_or_default(),
                channel_login: c.broadcaster.as_ref().map(|b| b.login.clone()).unwrap_or_default(),
                channel_name: c.broadcaster.map(|b| b.display_name).unwrap_or_default(),
            }
        })
        .collect()
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
