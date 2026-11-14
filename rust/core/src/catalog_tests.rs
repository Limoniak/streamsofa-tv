//! Catalogue et lecture, contre un faux serveur Twitch (Helix, GQL et usher sur la même adresse).

use serde_json::{json, Value};
use wiremock::matchers::{body_string_contains, header, method, path, path_regex, query_param};
use wiremock::{Mock, ResponseTemplate};

use crate::accounts::tests::Harness;
use crate::error::CoreError;

fn gql_stream(login: &str, viewers: u32) -> Value {
    json!({ "cursor": format!("c-{login}"), "node": {
        "id": format!("s-{login}"), "viewersCount": viewers, "createdAt": "2026-11-13T08:00:00Z",
        "broadcaster": { "id": format!("u-{login}"), "login": login, "displayName": login.to_uppercase(),
            "profileImageURL": format!("https://cdn.example/{login}.png"),
            "broadcastSettings": { "title": format!("Titre de {login}") } },
        "game": { "id": "509658", "displayName": "Just Chatting" },
    }})
}

fn helix_stream(login: &str, viewers: u32) -> Value {
    json!({ "user_id": format!("u-{login}"), "user_login": login, "user_name": login.to_uppercase(),
        "game_id": "21779", "game_name": "League of Legends", "title": format!("Titre de {login}"),
        "viewer_count": viewers, "started_at": "2026-11-13T08:00:00Z",
        "thumbnail_url": format!("https://static-cdn.jtvnw.net/previews-ttv/live_user_{login}-{{width}}x{{height}}.jpg") })
}

async fn mock_avatars(h: &Harness) {
    Mock::given(method("GET"))
        .and(path("/helix/users"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": [
            { "id": "u-alpha", "display_name": "ALPHA", "profile_image_url": "https://cdn.example/alpha.png" },
        ]})))
        .mount(&h.server)
        .await;
}

// --- Invité : GQL ----------------------------------------------------------------------------

#[tokio::test]
async fn guest_top_streams_come_from_gql_with_the_web_client_id() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .and(header("Client-Id", crate::gql::WEB_CLIENT_ID))
        .and(body_string_contains("\"operationName\":\"TopStreams\""))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "streams": {
            "edges": [gql_stream("alpha", 900), { "cursor": "x", "node": { "viewersCount": 5, "broadcaster": null } }],
            "pageInfo": { "hasNextPage": true } } } })))
        .expect(1)
        .mount(&h.server)
        .await;

    let page = h.core.top_streams(None).await.unwrap();
    assert_eq!(page.items.len(), 1, "une chaîne sans diffuseur (suspendue) est ignorée");
    let stream = &page.items[0];
    assert_eq!((stream.login.as_str(), stream.display_name.as_str(), stream.viewers), ("alpha", "ALPHA", 900));
    assert_eq!(stream.title, "Titre de alpha");
    assert_eq!(stream.category_name, "Just Chatting");
    assert_eq!(stream.avatar_url, "https://cdn.example/alpha.png");
    assert_eq!(stream.started_at, crate::catalog::parse_utc("2026-11-13T08:00:00Z").unwrap());
    assert!(stream.thumbnail_url.ends_with("live_user_alpha-{width}x{height}.jpg"));
    assert_eq!(page.cursor.as_deref(), Some("x"));
}

#[tokio::test]
async fn guest_followed_streams_need_an_account() {
    let h = Harness::new().await;
    assert!(matches!(h.core.followed_streams().await, Err(CoreError::NotSignedIn)));
    assert!(h.server.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn gql_errors_are_reported_as_a_changed_api() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "errors": [{ "message": "Cannot query field \"games\" on type \"Query\"." }] })))
        .mount(&h.server)
        .await;
    match h.core.top_categories(None).await {
        Err(CoreError::GqlChanged { operation, detail }) => {
            assert_eq!(operation, "TopCategories");
            assert!(detail.contains("Cannot query field"));
        }
        other => panic!("GqlChanged attendu, obtenu {other:?}"),
    }
}

#[tokio::test]
async fn guest_categories_get_a_box_art_template() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .and(body_string_contains("TopCategories"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "games": {
            "edges": [{ "cursor": "c1", "node": { "id": "509658", "displayName": "Just Chatting", "viewersCount": 250000 } }],
            "pageInfo": { "hasNextPage": false } } } })))
        .mount(&h.server)
        .await;
    let page = h.core.top_categories(None).await.unwrap();
    assert_eq!(page.items[0].viewers, 250000);
    assert_eq!(page.items[0].box_art_url, "https://static-cdn.jtvnw.net/ttv-boxart/509658-{width}x{height}.jpg");
    assert_eq!(page.cursor, None);
}

// --- Connecté : Helix ------------------------------------------------------------------------

#[tokio::test]
async fn signed_in_followed_streams_use_helix_and_follow_pagination() {
    let h = Harness::new().await;
    h.sign_in("42", "me").await;
    Mock::given(method("GET"))
        .and(path("/helix/streams/followed"))
        .and(query_param("user_id", "42"))
        .and(query_param("after", "page2"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": [helix_stream("beta", 10)], "pagination": {} })))
        .mount(&h.server)
        .await;
    Mock::given(method("GET"))
        .and(path("/helix/streams/followed"))
        .and(header("Authorization", "Bearer access-me"))
        .and(header("Client-Id", "client123"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": [helix_stream("alpha", 5)], "pagination": { "cursor": "page2" } })))
        .mount(&h.server)
        .await;
    mock_avatars(&h).await;

    let streams = h.core.followed_streams().await.unwrap();
    let summary: Vec<_> = streams.iter().map(|s| (s.login.as_str(), s.viewers, s.avatar_url.as_str())).collect();
    assert_eq!(summary, [("beta", 10, ""), ("alpha", 5, "https://cdn.example/alpha.png")]);
    assert!(h.server.received_requests().await.unwrap().iter().all(|r| !r.url.path().contains("gql")));
}

#[tokio::test]
async fn a_rejected_helix_token_is_refreshed_once_and_the_call_retried() {
    let h = Harness::new().await;
    h.sign_in("42", "me").await;
    Mock::given(method("GET"))
        .and(path("/helix/streams"))
        .and(header("Authorization", "Bearer access-me"))
        .respond_with(ResponseTemplate::new(401).set_body_json(json!({ "message": "Invalid OAuth token" })))
        .expect(1)
        .mount(&h.server)
        .await;
    Mock::given(method("POST"))
        .and(path("/oauth2/token"))
        .and(body_string_contains("refresh_token=refresh-me"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "access_token": "access-new", "refresh_token": "refresh-new", "expires_in": 14400 })))
        .expect(1)
        .mount(&h.server)
        .await;
    Mock::given(method("GET"))
        .and(path("/helix/streams"))
        .and(header("Authorization", "Bearer access-new"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": [helix_stream("alpha", 3)], "pagination": {} })))
        .expect(1)
        .mount(&h.server)
        .await;
    mock_avatars(&h).await;

    let page = h.core.top_streams(None).await.unwrap();
    assert_eq!(page.items[0].login, "alpha");
    assert_eq!(page.cursor, None);
}

#[tokio::test]
async fn a_revoked_account_falls_back_to_the_guest_catalog() {
    let h = Harness::new().await;
    h.sign_in("42", "me").await;
    Mock::given(method("GET")).and(path("/helix/streams")).respond_with(ResponseTemplate::new(401)).mount(&h.server).await;
    Mock::given(method("POST")).and(path("/oauth2/token")).respond_with(ResponseTemplate::new(400)).mount(&h.server).await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "streams": {
            "edges": [gql_stream("alpha", 1)], "pageInfo": { "hasNextPage": false } } } })))
        .expect(1)
        .mount(&h.server)
        .await;

    let page = h.core.top_streams(None).await.unwrap();
    assert_eq!(page.items[0].login, "alpha");
    let snapshot = h.core.accounts().unwrap();
    assert_eq!(snapshot.accounts[0].state, crate::accounts::AccountState::NeedsReauth);
}

#[tokio::test]
async fn guest_language_streams_ask_gql_for_that_language() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .and(body_string_contains("\"operationName\":\"LanguageStreams\""))
        .and(body_string_contains("\"languages\":[\"fr\"]"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "streams": {
            "edges": [gql_stream("alpha", 900)], "pageInfo": { "hasNextPage": true } } } })))
        .expect(1)
        .mount(&h.server)
        .await;

    let streams = h.core.language_streams("FR".into()).await.unwrap();
    assert_eq!(streams.iter().map(|s| s.login.as_str()).collect::<Vec<_>>(), ["alpha"]);
}

#[tokio::test]
async fn signed_in_language_streams_use_helix_with_the_language() {
    let h = Harness::new().await;
    h.sign_in("42", "me").await;
    Mock::given(method("GET"))
        .and(path("/helix/streams"))
        .and(query_param("language", "de"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": [helix_stream("alpha", 3)], "pagination": {} })))
        .expect(1)
        .mount(&h.server)
        .await;
    mock_avatars(&h).await;

    let streams = h.core.language_streams("de".into()).await.unwrap();
    assert_eq!(streams[0].login, "alpha");
}

#[tokio::test]
async fn language_streams_refuse_an_invalid_code() {
    let h = Harness::new().await;
    assert!(h.core.language_streams("fr&x=1".into()).await.is_err());
}

// --- Lecture -----------------------------------------------------------------------------------

async fn mock_playback_token(h: &Harness, token: Value) {
    Mock::given(method("POST"))
        .and(path("/gql"))
        .and(body_string_contains("PlaybackAccessToken_Template"))
        .and(body_string_contains("\"login\":\"zerator\""))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "streamPlaybackAccessToken": token } })))
        .mount(&h.server)
        .await;
}

#[tokio::test]
async fn live_playback_returns_the_master_playlist_and_its_variants() {
    let h = Harness::new().await;
    mock_playback_token(&h, json!({ "value": "{\"channel\":\"zerator\"}", "signature": "sig123",
        "authorization": { "isForbidden": false, "forbiddenReasonCode": "NONE" } })).await;
    Mock::given(method("GET"))
        .and(path("/api/channel/hls/zerator.m3u8"))
        .and(query_param("sig", "sig123"))
        .and(query_param("token", "{\"channel\":\"zerator\"}"))
        .respond_with(ResponseTemplate::new(200).set_body_string(include_str!("../tests/fixtures/master_live.m3u8")))
        .mount(&h.server)
        .await;

    let playback = h.core.live_playback("ZeRaToR".into()).await.unwrap();
    assert!(playback.master_url.contains("/api/channel/hls/zerator.m3u8?"));
    assert_eq!(playback.variants.len(), 5);
    assert!(playback.variants[0].source);
}

#[tokio::test]
async fn an_offline_channel_is_reported_as_offline() {
    let h = Harness::new().await;
    mock_playback_token(&h, json!({ "value": "{}", "signature": "s" })).await;
    Mock::given(method("GET"))
        .and(path_regex("^/api/channel/hls/"))
        .respond_with(ResponseTemplate::new(404).set_body_json(json!([{ "error": "transcode does not exist" }])))
        .mount(&h.server)
        .await;
    assert!(matches!(h.core.live_playback("zerator".into()).await, Err(CoreError::Offline)));
}

#[tokio::test]
async fn an_unknown_channel_is_reported_as_offline() {
    let h = Harness::new().await;
    mock_playback_token(&h, Value::Null).await;
    assert!(matches!(h.core.live_playback("zerator".into()).await, Err(CoreError::Offline)));
}

#[tokio::test]
async fn forbidden_playback_explains_why() {
    let h = Harness::new().await;
    mock_playback_token(&h, json!({ "value": "{}", "signature": "s",
        "authorization": { "isForbidden": true, "forbiddenReasonCode": "GEOBLOCKED" } })).await;
    match h.core.live_playback("zerator".into()).await {
        Err(CoreError::Unavailable { detail }) => assert_eq!(detail, "GEOBLOCKED"),
        other => panic!("Unavailable attendu, obtenu {other:?}"),
    }
}

#[tokio::test]
async fn usher_refusals_are_explained_without_leaking_the_url() {
    let h = Harness::new().await;
    mock_playback_token(&h, json!({ "value": "{}", "signature": "s" })).await;
    Mock::given(method("GET"))
        .and(path_regex("^/api/channel/hls/"))
        .respond_with(ResponseTemplate::new(403).set_body_json(json!([{ "error": "Content Restricted In Region" }])))
        .mount(&h.server)
        .await;
    match h.core.live_playback("zerator".into()).await {
        Err(CoreError::Unavailable { detail }) => assert_eq!(detail, "Content Restricted In Region"),
        other => panic!("Unavailable attendu, obtenu {other:?}"),
    }
}

// --- Envoi de chat : Helix -----------------------------------------------------------------

#[tokio::test]
async fn sending_chat_uses_helix_and_reports_twitch_refusals() {
    let h = Harness::new().await;
    assert!(matches!(h.core.send_chat("b1".into(), "salut".into(), None).await, Err(CoreError::NotSignedIn)));

    h.sign_in("u1", "alice").await;
    Mock::given(method("POST"))
        .and(path("/helix/chat/messages"))
        .and(header("Authorization", "Bearer access-alice"))
        .and(body_string_contains("\"sender_id\":\"u1\""))
        .and(body_string_contains("\"message\":\"salut\""))
        .and(body_string_contains("\"reply_parent_message_id\":\"m1\""))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": [{ "message_id": "x", "is_sent": true }] })))
        .up_to_n_times(1)
        .mount(&h.server)
        .await;
    h.core.send_chat("b1".into(), "  salut  ".into(), Some("m1".into())).await.unwrap();

    Mock::given(method("POST"))
        .and(path("/helix/chat/messages"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": [{ "message_id": "", "is_sent": false,
            "drop_reason": { "code": "followers_only", "message": "Réservé aux followers." } }] })))
        .mount(&h.server)
        .await;
    match h.core.send_chat("b1".into(), "salut".into(), None).await {
        Err(CoreError::ChatRejected { detail }) => assert_eq!(detail, "Réservé aux followers."),
        other => panic!("{other:?}"),
    }
}

// --- Chaînes et VOD --------------------------------------------------------------------------

fn gql_video(id: &str) -> Value {
    json!({ "node": { "id": id, "title": format!("VOD {id}"), "lengthSeconds": 3600,
        "publishedAt": "2026-11-12T08:00:00Z", "viewCount": 42,
        "previewThumbnailURL": format!("https://static-cdn.jtvnw.net/cf_vods/x/{id}-320x180.jpeg"),
        "owner": { "id": "u-alpha", "login": "alpha", "displayName": "Alpha" } } })
}

#[tokio::test]
async fn guest_videos_come_from_gql_with_the_resume_position() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .and(body_string_contains("\"operationName\":\"ChannelVideos\""))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "user": { "videos": {
            "edges": [gql_video("11"), gql_video("12")] } } } })))
        .mount(&h.server)
        .await;
    h.core.save_vod_progress("12".into(), 600, 3600).unwrap();

    let page = h.core.channel_videos("u-alpha".into(), None).await.unwrap();
    assert_eq!(page.cursor, None, "une seule page en invité");
    let ids: Vec<_> = page.items.iter().map(|v| (v.id.as_str(), v.position_s, v.finished)).collect();
    assert_eq!(ids, vec![("11", 0, false), ("12", 600, false)]);
    let video = &page.items[0];
    assert_eq!((video.duration_s, video.views, video.channel_login.as_str()), (3600, 42, "alpha"));
    assert!(video.thumbnail_url.ends_with("/11-{width}x{height}.jpeg"));
}

#[tokio::test]
async fn signed_in_videos_and_follows_come_from_helix() {
    let h = Harness::new().await;
    h.sign_in("u1", "alice").await;
    Mock::given(method("GET"))
        .and(path("/helix/videos"))
        .and(query_param("user_id", "u-alpha"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": [{
            "id": "21", "user_id": "u-alpha", "user_login": "alpha", "user_name": "Alpha", "title": "Rediff",
            "published_at": "2026-11-12T08:00:00Z", "view_count": 7, "duration": "1h2m3s",
            "thumbnail_url": "https://static-cdn.jtvnw.net/cf_vods/x/thumb-%{width}x%{height}.jpg" }],
            "pagination": { "cursor": "next" } })))
        .mount(&h.server)
        .await;
    Mock::given(method("GET"))
        .and(path("/helix/channels/followed"))
        .and(query_param("user_id", "u1"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": [
            { "broadcaster_id": "u-alpha", "broadcaster_login": "alpha", "broadcaster_name": "ALPHA", "followed_at": "x" },
            { "broadcaster_id": "u-beta", "broadcaster_login": "beta", "broadcaster_name": "Beta", "followed_at": "x" },
        ]})))
        .mount(&h.server)
        .await;
    mock_avatars(&h).await;

    let page = h.core.channel_videos("u-alpha".into(), None).await.unwrap();
    assert_eq!(page.cursor.as_deref(), Some("next"));
    assert_eq!(page.items[0].duration_s, 3723);
    assert_eq!(page.items[0].thumbnail_url, "https://static-cdn.jtvnw.net/cf_vods/x/thumb-{width}x{height}.jpg");

    let follows = h.core.followed_channels().await.unwrap();
    let logins: Vec<_> = follows.iter().map(|c| (c.login.as_str(), c.avatar_url.as_str())).collect();
    assert_eq!(logins, vec![("alpha", "https://cdn.example/alpha.png"), ("beta", "")]);
}

#[tokio::test]
async fn subscriber_only_vods_are_refused_not_bypassed() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .and(body_string_contains("\"vodID\":\"31\""))
        .and(body_string_contains("\"isVod\":true"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": {
            "videoPlaybackAccessToken": { "value": "{\"vod_id\":31}", "signature": "sig" } } })))
        .mount(&h.server)
        .await;
    Mock::given(method("GET"))
        .and(path("/vod/31.m3u8"))
        .and(query_param("sig", "sig"))
        .respond_with(ResponseTemplate::new(403).set_body_json(json!([{ "error": "Manifest is restricted" }])))
        .mount(&h.server)
        .await;
    match h.core.vod_playback("31".into()).await {
        Err(CoreError::Unavailable { detail }) => assert_eq!(detail, "Manifest is restricted"),
        other => panic!("Unavailable attendu, obtenu {other:?}"),
    }
    assert!(matches!(h.core.vod_playback("../x".into()).await, Err(CoreError::Unavailable { .. })));
}

#[tokio::test]
async fn vod_chat_starts_at_the_offset_and_keeps_emotes() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .and(body_string_contains("\"operationName\":\"VodComments\""))
        .and(body_string_contains("\"offset\":100"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "video": { "id": "41", "comments": {
            "edges": [
                { "node": { "id": "c0", "contentOffsetSeconds": 95, "commenter": { "id": "1", "login": "a", "displayName": "A" },
                    "message": { "userColor": "#FF0000", "fragments": [{ "text": "trop tôt", "emote": null }] } } },
                { "node": { "id": "c1", "contentOffsetSeconds": 101, "commenter": null,
                    "message": { "fragments": [{ "text": "compte supprimé", "emote": null }] } } },
                { "node": { "id": "c2", "contentOffsetSeconds": 102, "commenter": { "id": "2", "login": "b", "displayName": "B" },
                    "message": { "userColor": null, "fragments": [
                        { "text": "salut ", "emote": null }, { "text": "Kappa", "emote": { "emoteID": "25" } }] } } },
            ] } } } })))
        .mount(&h.server)
        .await;
    let comments = h.core.vod_chat("41".into(), 100).await.unwrap();
    assert_eq!(comments.len(), 1);
    assert_eq!(comments[0].offset_s, 102);
    assert_eq!(comments[0].message.display_name, "B");
    assert_eq!(
        comments[0].message.fragments[1],
        crate::chat::ChatFragment::Emote { id: "25".into(), name: "Kappa".into(), url: crate::chat::emote_url("25") }
    );
}

#[tokio::test]
async fn channel_activity_reads_poll_and_prediction() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .and(body_string_contains("\"operationName\":\"ChannelActivity\""))
        .and(body_string_contains("\"login\":\"alpha\""))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "user": {
            "viewablePoll": { "title": "Quel jeu ?", "status": "ACTIVE", "remainingDurationMilliseconds": 42000,
                "votes": { "total": 30 }, "choices": [
                    { "title": "Elden Ring", "votes": { "total": 20 } }, { "title": "Celeste", "votes": { "total": 10 } }] },
            "channel": { "activePredictionEvents": [{ "title": "Il gagne ?", "status": "RESOLVED",
                "createdAt": "2026-11-13T09:00:00Z", "predictionWindowSeconds": 120,
                "outcomes": [{ "id": "o1", "title": "Oui", "totalPoints": 5000, "totalUsers": 12 },
                             { "id": "o2", "title": "Non", "totalPoints": 800, "totalUsers": 3 }],
                "winningOutcome": { "id": "o2" } }] } } } })))
        .mount(&h.server)
        .await;
    let activity = h.core.channel_activity("Alpha".into()).await.unwrap();
    let poll = activity.poll.unwrap();
    assert_eq!((poll.title.as_str(), poll.total_votes, poll.active, poll.remaining_ms), ("Quel jeu ?", 30, true, 42000));
    assert_eq!(poll.choices[1], crate::catalog::PollChoice { title: "Celeste".into(), votes: 10 });
    let prediction = activity.prediction.unwrap();
    assert_eq!(prediction.status, crate::catalog::PredictionStatus::Resolved);
    assert_eq!(prediction.locks_at_s, crate::catalog::parse_utc("2026-11-13T09:02:00Z").unwrap());
    assert_eq!(prediction.outcomes.iter().map(|o| o.winner).collect::<Vec<_>>(), [false, true]);
}

#[tokio::test]
async fn channel_activity_without_poll_or_prediction() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "user": {
            "viewablePoll": { "title": "vieux", "status": "ARCHIVED", "choices": [] },
            "channel": { "activePredictionEvents": [] } } } })))
        .mount(&h.server)
        .await;
    let activity = h.core.channel_activity("alpha".into()).await.unwrap();
    assert_eq!((activity.poll, activity.prediction), (None, None));
}

#[tokio::test]
async fn category_clips_filter_by_language() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .and(body_string_contains("\"operationName\":\"CategoryClips\""))
        .and(body_string_contains("\"languages\":[\"FR\"]"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "game": { "clips": { "edges": [
            { "node": { "slug": "s1", "title": "Ouf", "durationSeconds": 30, "viewCount": 900,
                "curator": { "displayName": "Bob" }, "broadcaster": { "displayName": "Alice" } } }] } } } })))
        .mount(&h.server)
        .await;
    let clips = h.core.category_clips("32982".into(), Some("fr".into())).await.unwrap();
    assert_eq!((clips[0].slug.as_str(), clips[0].channel_name.as_str(), clips[0].curator.as_str()), ("s1", "Alice", "Bob"));
}

#[tokio::test]
async fn guest_followed_clips_need_an_account() {
    let h = Harness::new().await;
    assert!(matches!(h.core.followed_clips().await, Err(CoreError::NotSignedIn)));
}

// --- Profils : préférences, PIN, mode enfants -------------------------------------------------

#[tokio::test]
async fn settings_are_per_profile_except_device_ones() {
    let h = Harness::new().await;
    h.core.set_setting("accent".into(), "blue".into()).unwrap();
    h.core.set_setting("check_updates".into(), "false".into()).unwrap();
    h.sign_in("42", "me").await;
    let signed_in = h.core.settings().unwrap();
    assert_eq!((signed_in.get("accent"), signed_in.get("check_updates").map(String::as_str)), (None, Some("false")));
    h.core.set_active_account(None).unwrap();
    assert_eq!(h.core.settings().unwrap().get("accent").map(String::as_str), Some("blue"));
    assert!(h.core.set_setting("kids_profile".into(), "true".into()).is_err());
}

#[tokio::test]
async fn profile_pins_and_kids_mode_show_in_the_snapshot() {
    let h = Harness::new().await;
    assert!(h.core.set_profile_pin(None, Some("12a4".into())).is_err());
    h.core.set_profile_pin(None, Some("1234".into())).unwrap();
    h.core.set_kids_profile(None, true).unwrap();
    let locks = h.core.accounts().unwrap().locks;
    assert_eq!((locks.len(), locks[0].profile_id.clone(), locks[0].has_pin, locks[0].kids), (1, None, true, true));
    assert!(h.core.check_profile_pin(None, "1234".into()).unwrap() && !h.core.check_profile_pin(None, "4321".into()).unwrap());
    assert!(!h.core.settings().unwrap().contains_key("pin_hash"));
    h.core.reset_guest_profile().unwrap();
    assert!(h.core.accounts().unwrap().locks.is_empty());
}

#[tokio::test]
async fn kids_mode_keeps_only_family_friendly_channels() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .and(body_string_contains("\"operationName\":\"TopStreams\""))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "streams": {
            "edges": [gql_stream("alpha", 900), gql_stream("beta", 800), gql_stream("gamma", 700)],
            "pageInfo": { "hasNextPage": false } } } })))
        .mount(&h.server)
        .await;
    Mock::given(method("POST"))
        .and(path("/gql"))
        .and(body_string_contains("\"operationName\":\"SafeByLogin\""))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": { "users": [
            { "id": "u-alpha", "login": "alpha", "broadcastSettings": { "isMature": false }, "channel": { "contentClassificationLabels": [] } },
            { "id": "u-beta", "login": "beta", "broadcastSettings": { "isMature": false }, "channel": { "contentClassificationLabels": [{ "id": "Gambling" }] } },
            { "id": "u-gamma", "login": "gamma", "broadcastSettings": { "isMature": true }, "channel": null },
        ] } })))
        .mount(&h.server)
        .await;
    assert_eq!(h.core.top_streams(None).await.unwrap().items.len(), 3);
    h.core.set_kids_profile(None, true).unwrap();
    let logins: Vec<String> = h.core.top_streams(None).await.unwrap().items.into_iter().map(|s| s.login).collect();
    assert_eq!(logins, ["alpha"]);
    assert!(matches!(h.core.live_playback("beta".into()).await, Err(CoreError::Unavailable { detail }) if detail == "kids_profile"));
}
