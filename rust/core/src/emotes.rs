//! Émotes tierces : 7TV, BetterTTV et FrankerFaceZ (via le cache de BetterTTV).
//! Services non officiels et décoratifs : une panne d'un fournisseur laisse simplement
//! ses émotes en texte.

use std::collections::HashMap;

use serde::Deserialize;
use serde_json::Value;

const SEVENTV: &str = "https://7tv.io/v3";
const BTTV: &str = "https://api.betterttv.net/3/cached";

/// Nom → image. Ordre de priorité en cas de doublon : 7TV, puis BTTV, puis FFZ.
pub(crate) type EmoteMap = HashMap<String, String>;

pub(crate) async fn global(http: &reqwest::Client) -> EmoteMap {
    let (seventv, bttv, ffz) = tokio::join!(
        fetch(http, format!("{SEVENTV}/emote-sets/global")),
        fetch(http, format!("{BTTV}/emotes/global")),
        fetch(http, format!("{BTTV}/frankerfacez/emotes/global")),
    );
    merge([parse_ffz(&ffz), parse_bttv(&bttv), parse_7tv_set(&seventv)])
}

pub(crate) async fn channel(http: &reqwest::Client, channel_id: &str) -> EmoteMap {
    let (seventv, bttv, ffz) = tokio::join!(
        fetch(http, format!("{SEVENTV}/users/twitch/{channel_id}")),
        fetch(http, format!("{BTTV}/users/twitch/{channel_id}")),
        fetch(http, format!("{BTTV}/frankerfacez/users/twitch/{channel_id}")),
    );
    let bttv_channel: Vec<Value> = ["channelEmotes", "sharedEmotes"]
        .iter()
        .flat_map(|key| bttv.get(key).and_then(Value::as_array).cloned().unwrap_or_default())
        .collect();
    merge([parse_ffz(&ffz), parse_bttv(&Value::Array(bttv_channel)), parse_7tv_set(&seventv["emote_set"])])
}

/// `Null` en cas d'échec : une chaîne sans émotes tierces répond 404.
async fn fetch(http: &reqwest::Client, url: String) -> Value {
    match http.get(url).send().await {
        Ok(response) if response.status().is_success() => response.json().await.unwrap_or(Value::Null),
        _ => Value::Null,
    }
}

fn merge<const N: usize>(maps: [EmoteMap; N]) -> EmoteMap {
    maps.into_iter().fold(EmoteMap::new(), |mut acc, map| {
        acc.extend(map);
        acc
    })
}

/// Jeu d'émotes 7TV : `{ emotes: [{ name, data: { host: { url: "//cdn.7tv.app/emote/ID" } } }] }`.
fn parse_7tv_set(set: &Value) -> EmoteMap {
    #[derive(Deserialize)]
    struct Host {
        url: String,
    }
    #[derive(Deserialize)]
    struct Data {
        host: Host,
    }
    #[derive(Deserialize)]
    struct Emote {
        name: String,
        data: Data,
    }
    let emotes: Vec<Emote> = set.get("emotes").cloned().and_then(|v| serde_json::from_value(v).ok()).unwrap_or_default();
    // WebP : Android le décode nativement ; une émote animée s'affiche sur sa première image.
    emotes.into_iter().map(|e| (e.name, format!("https:{}/2x.webp", e.data.host.url))).collect()
}

/// `[{ id, code }]`.
fn parse_bttv(list: &Value) -> EmoteMap {
    #[derive(Deserialize)]
    struct Emote {
        id: String,
        code: String,
    }
    let emotes: Vec<Emote> = serde_json::from_value(list.clone()).unwrap_or_default();
    emotes.into_iter().map(|e| (e.code, format!("https://cdn.betterttv.net/emote/{}/2x", e.id))).collect()
}

/// `[{ code, images: { "1x", "2x", "4x" } }]`.
fn parse_ffz(list: &Value) -> EmoteMap {
    #[derive(Deserialize)]
    struct Emote {
        code: String,
        images: HashMap<String, Option<String>>,
    }
    let emotes: Vec<Emote> = serde_json::from_value(list.clone()).unwrap_or_default();
    emotes
        .into_iter()
        .filter_map(|e| {
            let url = e.images.get("2x").cloned().flatten().or_else(|| e.images.get("1x").cloned().flatten())?;
            Some((e.code, url))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parses_each_provider() {
        let seventv = json!({ "emotes": [{ "name": "RainTime", "data": { "host": { "url": "//cdn.7tv.app/emote/01F" } } }] });
        assert_eq!(parse_7tv_set(&seventv)["RainTime"], "https://cdn.7tv.app/emote/01F/2x.webp");
        let bttv = json!([{ "id": "54fa", "code": ":tf:", "imageType": "png" }]);
        assert_eq!(parse_bttv(&bttv)[":tf:"], "https://cdn.betterttv.net/emote/54fa/2x");
        let ffz = json!([{ "id": 9, "code": "ZrehplaR", "images": { "1x": "u/1", "2x": null, "4x": "u/4" } }]);
        assert_eq!(parse_ffz(&ffz)["ZrehplaR"], "u/1");
        assert!(parse_bttv(&Value::Null).is_empty());
        assert!(parse_7tv_set(&Value::Null).is_empty());
    }

    #[test]
    fn seventv_wins_over_bttv_and_ffz() {
        let ffz = EmoteMap::from([("OMEGALUL".into(), "ffz".into())]);
        let bttv = EmoteMap::from([("OMEGALUL".into(), "bttv".into())]);
        let seventv = EmoteMap::from([("OMEGALUL".into(), "7tv".into())]);
        assert_eq!(merge([ffz, bttv, seventv])["OMEGALUL"], "7tv");
    }
}
