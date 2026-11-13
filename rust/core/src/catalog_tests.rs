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
