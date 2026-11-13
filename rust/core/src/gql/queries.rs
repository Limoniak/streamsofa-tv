//! Requêtes GQL envoyées à Twitch. API interne non documentée : si Twitch la modifie,
//! c'est ici (et dans les types de `mod.rs`) qu'il faut corriger.

/// Jeton de lecture d'un live ou d'une VOD. Même requête que le lecteur web de Twitch.
pub const PLAYBACK_ACCESS_TOKEN: &str = r#"query PlaybackAccessToken_Template($login: String!, $isLive: Boolean!, $vodID: ID!, $isVod: Boolean!, $playerType: String!) {
  streamPlaybackAccessToken(channelName: $login, params: {platform: "web", playerBackend: "mediaplayer", playerType: $playerType}) @include(if: $isLive) {
    value
    signature
    authorization { isForbidden forbiddenReasonCode }
  }
  videoPlaybackAccessToken(id: $vodID, params: {platform: "web", playerBackend: "mediaplayer", playerType: $playerType}) @include(if: $isVod) {
    value
    signature
  }
}"#;

const STREAM_FIELDS: &str = "id viewersCount createdAt \
    broadcaster { id login displayName profileImageURL(width: 70) broadcastSettings { title } } \
    game { id displayName }";

pub fn top_streams() -> String {
    format!(
        "query TopStreams($first: Int, $after: Cursor) {{ streams(first: $first, after: $after) {{ \
         edges {{ cursor node {{ {STREAM_FIELDS} }} }} pageInfo {{ hasNextPage }} }} }}"
    )
}

pub fn category_streams() -> String {
    format!(
        "query CategoryStreams($id: ID!, $first: Int, $after: Cursor) {{ game(id: $id) {{ \
         streams(first: $first, after: $after) {{ \
         edges {{ cursor node {{ {STREAM_FIELDS} }} }} pageInfo {{ hasNextPage }} }} }} }}"
    )
}

pub const TOP_CATEGORIES: &str = "query TopCategories($first: Int, $after: Cursor) { \
    games(first: $first, after: $after) { \
    edges { cursor node { id displayName viewersCount } } pageInfo { hasNextPage } } }";

pub const CHANNEL_STREAM: &str = "query ChannelStream($login: String!) { user(login: $login) { \
    id login displayName profileImageURL(width: 70) broadcastSettings { title } \
    stream { id viewersCount createdAt game { id displayName } } } }";

pub const CHANNEL: &str = "query Channel($login: String!) { user(login: $login) { \
    id login displayName description profileImageURL(width: 150) } }";

const VIDEO_FIELDS: &str = "id title lengthSeconds publishedAt viewCount \
    previewThumbnailURL(width: 320, height: 180) owner { id login displayName }";

/// Sans curseur : la page suivante exige un jeton d'intégrité que Lumen ne fournit pas.
pub fn channel_videos() -> String {
    format!(
        "query ChannelVideos($id: ID!, $first: Int) {{ user(id: $id) {{ \
         videos(first: $first, sort: TIME) {{ edges {{ node {{ {VIDEO_FIELDS} }} }} }} }} }}"
    )
}

pub fn video() -> String {
    format!("query Video($id: ID!) {{ video(id: $id) {{ {VIDEO_FIELDS} }} }}")
}

/// Commentaires autour d'un instant de la VOD (une fenêtre de quelques minutes).
pub const VOD_COMMENTS: &str = "query VodComments($id: ID!, $offset: Int) { video(id: $id) { id \
    comments(contentOffsetSeconds: $offset) { edges { node { id contentOffsetSeconds \
    commenter { id login displayName } message { userColor fragments { text emote { emoteID } } } } } } } }";
