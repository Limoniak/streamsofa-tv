//! Chemins chauds du cœur, sans réseau : `cargo bench -p lumen-core --features bench`.
//!
//! - chat : une trame IRC de 20 messages (le rythme d'un gros salon est de 20 à 100 messages/s) ;
//! - catalogue : une page de 100 lives, Helix (compte connecté) et GQL (invité) ;
//! - lecture : la playlist maîtresse HLS d'un live ;
//! - base SQLite : préférences, reprise des VOD, cache des émotes.

use std::fmt::Write as _;
use std::hint::black_box;

use criterion::{criterion_group, criterion_main, BatchSize, Criterion};
use lumen_core::bench::{chat_frame, gql_streams, helix_streams, hls_master, BenchDb, ChatDecor};

/// 20 PRIVMSG réalistes : tags complets, émotes Twitch pour un sur trois, émotes tierces pour un sur quatre.
fn irc_frame() -> String {
    let mut frame = String::new();
    for i in 0..20 {
        let (emotes, text) = match i % 12 {
            0 | 3 | 6 | 9 => ("25:0-4,12-16".to_owned(), "Kappa salut Kappa tout le monde".to_owned()),
            4 | 8 => (String::new(), format!("gg Emote{} bien joué Emote{} !", i * 7, i * 3)),
            _ => (String::new(), format!("message numéro {i} avec un peu de texte pour faire une vraie phrase de chat")),
        };
        write!(
            frame,
            "@badge-info=subscriber/14;badges=subscriber/12,premium/1;client-nonce=abcdef0123456789;color=#1E90FF;\
             display-name=Viewer{i};emotes={emotes};first-msg=0;flags=;id=3f2a8c1e-0000-4000-8000-{i:012};mod=0;\
             returning-chatter=0;room-id=123456789;subscriber=1;tmi-sent-ts=17945607{i:05};turbo=0;user-id=9{i:07};\
             user-type= :viewer{i}!viewer{i}@viewer{i}.tmi.twitch.tv PRIVMSG #chaine :{text}\r\n"
        )
        .unwrap();
    }
    frame
}

fn helix_page() -> Vec<u8> {
    let streams: Vec<String> = (0..100)
        .map(|i| {
            format!(
                r#"{{"id":"4{i:010}","user_id":"1{i:08}","user_login":"streamer{i}","user_name":"Streamer{i}","game_id":"509658",
                "game_name":"Just Chatting","type":"live","title":"Soirée détente numéro {i} — on discute et on joue !",
                "viewer_count":{v},"started_at":"2026-11-13T08:00:00Z","language":"fr",
                "thumbnail_url":"https://static-cdn.jtvnw.net/previews-ttv/live_user_streamer{i}-{{width}}x{{height}}.jpg",
                "tag_ids":[],"tags":["Français","Détente","Communauté"],"is_mature":false}}"#,
                v = 50_000 - i * 400
            )
        })
        .collect();
    format!(r#"{{"data":[{}],"pagination":{{"cursor":"eyJiIjpudWxsfQ"}}}}"#, streams.join(",")).into_bytes()
}

fn gql_page() -> Vec<u8> {
    let edges: Vec<String> = (0..100)
        .map(|i| {
            format!(
                r#"{{"cursor":"c-{i}","node":{{"id":"4{i:010}","viewersCount":{v},"createdAt":"2026-11-13T08:00:00Z",
                "broadcaster":{{"id":"1{i:08}","login":"streamer{i}","displayName":"Streamer{i}",
                "profileImageURL":"https://static-cdn.jtvnw.net/jtv_user_pictures/{i}-profile_image-70x70.png",
                "broadcastSettings":{{"title":"Soirée détente numéro {i} — on discute et on joue !"}}}},
                "game":{{"id":"509658","displayName":"Just Chatting"}}}}}}"#,
                v = 50_000 - i * 400
            )
        })
        .collect();
    format!(r#"{{"data":{{"streams":{{"edges":[{}],"pageInfo":{{"hasNextPage":true}}}}}}}}"#, edges.join(",")).into_bytes()
}

fn chat(c: &mut Criterion) {
    let frame = irc_frame();
    let plain = ChatDecor::new(0);
    let decorated = ChatDecor::new(400);
    assert_eq!(chat_frame(&decorated, &frame).len(), 20);
    c.bench_function("chat/trame_20_messages", |b| b.iter(|| chat_frame(black_box(&plain), black_box(&frame))));
    c.bench_function("chat/trame_20_messages_emotes_tierces", |b| b.iter(|| chat_frame(black_box(&decorated), black_box(&frame))));
}

fn catalog(c: &mut Criterion) {
    let helix = helix_page();
    let gql = gql_page();
    assert_eq!(helix_streams(&helix), 100);
    assert_eq!(gql_streams(&gql), 100);
    c.bench_function("catalogue/helix_100_lives", |b| b.iter(|| helix_streams(black_box(&helix))));
    c.bench_function("catalogue/gql_100_lives", |b| b.iter(|| gql_streams(black_box(&gql))));
}

fn hls(c: &mut Criterion) {
    let master = include_str!("../tests/fixtures/master_live.m3u8");
    assert!(hls_master(master, "https://usher.ttvnw.net/api/channel/hls/x.m3u8") > 0);
    c.bench_function("hls/playlist_maitresse", |b| {
        b.iter(|| hls_master(black_box(master), black_box("https://usher.ttvnw.net/api/channel/hls/x.m3u8")))
    });
}

fn store(c: &mut Criterion) {
    let dir = tempfile::tempdir().unwrap();
    let db = BenchDb::open(&dir.path().join("lumen.db"));
    for i in 0..40 {
        db.set_setting("guest", &format!("cle_{i}"), "une valeur de préférence");
    }
    let ids: Vec<String> = (0..100).map(|i| format!("21{i:08}")).collect();
    for id in &ids {
        db.save_vod_progress("guest", id, 600);
    }
    let refs: Vec<&str> = ids.iter().map(String::as_str).collect();
    // Cache des émotes d'une chaîne : ~400 entrées nom → URL.
    let emotes: std::collections::HashMap<String, String> =
        (0..400).map(|i| (format!("Emote{i}"), format!("https://cdn.7tv.app/emote/01F6MQ33FG000{i:04}/2x.webp"))).collect();
    db.meta_put("emotes:123456789", &serde_json::to_string(&emotes).unwrap());

    c.bench_function("base/lire_preferences_40", |b| b.iter(|| db.settings(black_box("guest"))));
    c.bench_function("base/ecrire_preference", |b| {
        let mut n = 0u32;
        b.iter_batched(
            || {
                n += 1;
                n.to_string()
            },
            |value| db.set_setting("guest", "chat_width", &value),
            BatchSize::SmallInput,
        )
    });
    c.bench_function("base/reprise_100_vod", |b| b.iter(|| db.vod_progress(black_box("guest"), black_box(&refs))));
    c.bench_function("base/enregistrer_reprise", |b| {
        let mut n = 0u32;
        b.iter(|| {
            n += 1;
            db.save_vod_progress("guest", "2100000001", n)
        })
    });
    c.bench_function("base/cache_emotes_400", |b| b.iter(|| db.meta_get_map(black_box("emotes:123456789"))));
}

criterion_group!(benches, chat, catalog, hls, store);
criterion_main!(benches);
