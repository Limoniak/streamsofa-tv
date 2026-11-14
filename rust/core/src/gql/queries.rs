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

/// Lives les plus regardés d'une langue, toutes catégories confondues.
pub fn language_streams() -> String {
    format!(
        "query LanguageStreams($first: Int, $languages: [String!]) {{ streams(first: $first, options: {{ languages: $languages }}) {{ \
         edges {{ cursor node {{ {STREAM_FIELDS} }} }} pageInfo {{ hasNextPage }} }} }}"
    )
}

pub fn category_streams() -> String {
    format!(
        "query CategoryStreams($id: ID!, $first: Int, $after: Cursor, $languages: [String!]) {{ game(id: $id) {{ \
         streams(first: $first, after: $after, options: {{ languages: $languages }}) {{ \
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
/// Chapitres (changements de jeu) d'une VOD, comme le bouton « Chapitres » du lecteur web.
pub const VIDEO_CHAPTERS: &str = "query VideoChapters($id: ID!) { video(id: $id) { \
    moments(momentRequestType: VIDEO_CHAPTER_MARKERS, types: [GAME_CHANGE]) { edges { node { \
    positionMilliseconds description details { ... on GameChangeMomentDetails { game { displayName } } } } } } } }";

/// Lisible en anonyme : `viewablePoll` (sur l'utilisateur) et les prédictions de la chaîne.
pub const CHANNEL_ACTIVITY: &str = "query ChannelActivity($login: String!) { user(login: $login) { \
    viewablePoll { title status remainingDurationMilliseconds votes { total } choices { title votes { total } } } \
    channel { activePredictionEvents { title status createdAt predictionWindowSeconds \
    outcomes { id title totalPoints totalUsers } winningOutcome { id } } } } }";

pub const VOD_COMMENTS: &str = "query VodComments($id: ID!, $offset: Int) { video(id: $id) { id \
    comments(contentOffsetSeconds: $offset) { edges { node { id contentOffsetSeconds \
    commenter { id login displayName } message { userColor fragments { text emote { emoteID } } } } } } } }";

pub const GLOBAL_BADGES: &str = "query GlobalBadges { badges { setID version imageURL(size: DOUBLE) } }";

/// Badges propres à la chaîne (abonné, bits) ; ils remplacent les badges globaux du même nom.
pub const CHANNEL_BADGES: &str = "query ChannelBadges($login: String!) { user(login: $login) { id     broadcastBadges { setID version imageURL(size: DOUBLE) } } }";

pub const SEARCH: &str = "query Search($query: String!) { \
    searchFor(userQuery: $query, platform: \"web\", options: { targets: [{ index: CHANNEL }, { index: GAME }] }) { \
    channels { items { id login displayName profileImageURL(width: 70) \
    broadcastSettings { title } stream { viewersCount game { displayName } } } } \
    games { items { id displayName viewersCount } } } }";

/// `period` : `LAST_WEEK` ou `ALL_TIME`.
pub const CLIPS: &str = "query Clips($login: String!, $first: Int, $period: ClipsPeriod) { user(login: $login) { \
    clips(first: $first, criteria: { period: $period }) { edges { node { \
    slug title durationSeconds viewCount createdAt thumbnailURL curator { displayName } broadcaster { login displayName } } } } } }";

/// Clips les plus vus de la semaine dans une catégorie, filtrables par langue (codes en majuscules).
pub const CATEGORY_CLIPS: &str = "query CategoryClips($id: ID!, $first: Int, $languages: [Language!]) { game(id: $id) { \
    clips(first: $first, criteria: { period: LAST_WEEK, languages: $languages }) { edges { node { \
    slug title durationSeconds viewCount createdAt thumbnailURL curator { displayName } broadcaster { login displayName } } } } } }";

/// Jeton anonyme d'un clip et ses fichiers MP4, à signer avec ce jeton.
pub const CLIP_PLAYBACK: &str = "query ClipPlayback($slug: ID!) { clip(slug: $slug) { \
    playbackAccessToken(params: { platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\" }) { value signature } \
    videoQualities { quality sourceURL } } }";

pub const CHANNEL_ABOUT: &str = "query ChannelAbout($login: String!) { user(login: $login) { \
    panels { ... on DefaultPanel { title description imageURL linkURL } } \
    channel { schedule { segments { startAt endAt title isCancelled categories { name } } } } } }";

/// Chaînes tout public (mode enfants) : ni « contenu mature », ni étiquette de classification.
pub const SAFE_BY_LOGIN: &str = "query SafeByLogin($keys: [String!]) { users(logins: $keys) { \
    id login broadcastSettings { isMature } channel { contentClassificationLabels { id } } } }";
pub const SAFE_BY_ID: &str = "query SafeById($keys: [ID!]) { users(ids: $keys) { \
    id login broadcastSettings { isMature } channel { contentClassificationLabels { id } } } }";
