//! Points d'entrée des benchmarks criterion (`cargo bench --features bench`).
//! Absent de la bibliothèque livrée : la feature `bench` n'est jamais activée par le build Android.

use std::collections::HashMap;
use std::path::Path;

use crate::chat::decor::Decor;
use crate::chat::ChatEvent;
use crate::store::Db;

/// Badges et émotes tierces d'un salon chargé.
pub struct ChatDecor(Decor);

impl ChatDecor {
    pub fn new(emotes: usize) -> Self {
        let mut decor = Decor::with_filters(&HashMap::from([("chat_hidden_words".to_owned(), "spoiler,arnaque".to_owned())]));
        for i in 0..emotes {
            decor.emotes.insert(format!("Emote{i}"), format!("https://cdn.7tv.app/emote/{i}/2x.webp"));
        }
        for set in ["subscriber/12", "moderator/1", "premium/1", "vip/1", "broadcaster/1"] {
            decor.badges.insert(set.to_owned(), format!("https://static-cdn.jtvnw.net/badges/v1/{set}/2"));
        }
        Self(decor)
    }
}

/// Le traitement d'une trame WebSocket par la session de chat : lignes IRC, événements, filtres, badges, émotes.
pub fn chat_frame(decor: &ChatDecor, frame: &str) -> Vec<ChatEvent> {
    crate::chat::bench_frame(&decor.0, frame)
}

pub fn hls_master(body: &str, base_url: &str) -> usize {
    crate::hls::parse_master(body, base_url).len()
}

/// Une page Helix `streams` décodée puis convertie pour l'interface.
pub fn helix_streams(body: &[u8]) -> usize {
    let page: crate::helix::Page<crate::helix::HelixStream> = serde_json::from_slice(body).expect("page Helix");
    let lives: Vec<_> = page.data.into_iter().map(|s| s.into_live(String::new())).collect();
    lives.len()
}

/// Une réponse GQL `TopStreams` (catalogue invité) décodée puis convertie.
pub fn gql_streams(body: &[u8]) -> usize {
    crate::gql::bench_top_streams(body)
}

/// La base SQLite du cœur (préférences, reprise des VOD, cache des badges et émotes).
pub struct BenchDb(Db);

impl BenchDb {
    pub fn open(path: &Path) -> Self {
        Self(Db::open(path).expect("base"))
    }

    pub fn settings(&self, scope: &str) -> usize {
        self.0.settings(scope).expect("settings").len()
    }

    pub fn set_setting(&self, scope: &str, key: &str, value: &str) {
        self.0.set_setting(scope, key, value).expect("set_setting");
    }

    pub fn save_vod_progress(&self, scope: &str, video_id: &str, position_s: u32) {
        self.0.save_vod_progress(scope, video_id, position_s, 7200, 1_700_000_000).expect("vod_progress");
    }

    pub fn vod_progress(&self, scope: &str, ids: &[&str]) -> usize {
        self.0.vod_progress(scope, ids).expect("vod_progress").len()
    }

    pub fn meta_put(&self, key: &str, body: &str) {
        self.0.meta_put(key, body, 1_700_000_000, 86_400).expect("meta_put");
    }

    /// Lecture du cache puis décodage, comme `Core::cached` pour les badges et les émotes.
    pub fn meta_get_map(&self, key: &str) -> usize {
        let body = self.0.meta_get(key, 1_700_000_100).expect("meta_get").expect("présent");
        serde_json::from_str::<HashMap<String, String>>(&body).expect("json").len()
    }
}
