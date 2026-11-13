//! Playlists HLS de Twitch : URL `usher` et lecture de la playlist maîtresse.

use std::collections::HashMap;

/// Une qualité proposée par la playlist maîtresse.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Variant {
    /// Nom affichable : « 1080p60 », « 720p », « audio_only »…
    pub name: String,
    /// Playlist de cette seule qualité, à lire directement pour une qualité fixe.
    pub url: String,
    pub bandwidth: u64,
    pub width: u32,
    pub height: u32,
    pub frame_rate: f32,
    /// Qualité d'origine diffusée par le streamer.
    pub source: bool,
    pub audio_only: bool,
}

/// De quoi lancer la lecture : la playlist maîtresse (qualité automatique) et ses variantes.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Playback {
    pub master_url: String,
    pub variants: Vec<Variant>,
}

/// URL de la playlist maîtresse d'un live.
///
/// `supported_codecs=avc1` : H.264 uniquement. Twitch propose aussi HEVC et AV1 sur certaines
/// chaînes, mais beaucoup de box ne les décodent pas en matériel.
pub fn live_master_url(base: &str, login: &str, token: &str, signature: &str, nonce: u32) -> String {
    let query: String = url_query(&[
        ("allow_source", "true"),
        ("allow_audio_only", "true"),
        ("fast_bread", "true"),
        ("playlist_include_framerate", "true"),
        ("player_backend", "mediaplayer"),
        ("supported_codecs", "avc1"),
        ("p", &nonce.to_string()),
        ("sig", signature),
        ("token", token),
    ]);
    format!("{base}/api/channel/hls/{}.m3u8?{query}", login.to_lowercase())
}

fn url_query(pairs: &[(&str, &str)]) -> String {
    pairs
        .iter()
        .map(|(k, v)| format!("{k}={}", percent_encode(v)))
        .collect::<Vec<_>>()
        .join("&")
}

fn percent_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(byte as char),
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

/// Lit les variantes d'une playlist maîtresse, dans l'ordre où Twitch les propose
/// (la source d'abord). Les URL relatives sont résolues par rapport à `base_url`.
pub fn parse_master(body: &str, base_url: &str) -> Vec<Variant> {
    // NAME de chaque groupe vidéo, déclaré par EXT-X-MEDIA avant la variante qui l'utilise.
    let mut names: HashMap<String, String> = HashMap::new();
    let mut variants = Vec::new();
    let mut pending: Option<HashMap<String, String>> = None;

    for line in body.lines().map(str::trim).filter(|l| !l.is_empty()) {
        if let Some(attrs) = line.strip_prefix("#EXT-X-MEDIA:") {
            let attrs = parse_attributes(attrs);
            if attrs.get("TYPE").map(String::as_str) == Some("VIDEO") {
                if let (Some(group), Some(name)) = (attrs.get("GROUP-ID"), attrs.get("NAME")) {
                    names.insert(group.clone(), name.clone());
                }
            }
        } else if let Some(attrs) = line.strip_prefix("#EXT-X-STREAM-INF:") {
            pending = Some(parse_attributes(attrs));
        } else if line.starts_with('#') {
            continue;
        } else if let Some(attrs) = pending.take() {
            variants.push(variant(&attrs, &names, resolve(base_url, line)));
        }
    }
    variants
}

fn variant(attrs: &HashMap<String, String>, names: &HashMap<String, String>, url: String) -> Variant {
    let (width, height) = attrs
        .get("RESOLUTION")
        .and_then(|r| r.split_once('x'))
        .and_then(|(w, h)| Some((w.parse().ok()?, h.parse().ok()?)))
        .unwrap_or((0, 0));
    let frame_rate: f32 = attrs.get("FRAME-RATE").and_then(|f| f.parse().ok()).unwrap_or(0.0);
    let group = attrs.get("VIDEO").cloned().unwrap_or_default();
    let raw_name = names.get(&group).cloned().unwrap_or_else(|| fallback_name(height, frame_rate, &group));
    let source = raw_name.contains("(source)") || group == "chunked";
    let name = raw_name.replace("(source)", "").trim().to_owned();
    Variant {
        audio_only: group == "audio_only" || (height == 0 && name == "audio_only"),
        name,
        url,
        bandwidth: attrs.get("BANDWIDTH").and_then(|b| b.parse().ok()).unwrap_or(0),
        width,
        height,
        frame_rate,
        source,
    }
}

fn fallback_name(height: u32, frame_rate: f32, group: &str) -> String {
    if height == 0 {
        return group.to_owned();
    }
    let fps = frame_rate.round() as u32;
    if fps > 30 {
        format!("{height}p{fps}")
    } else {
        format!("{height}p")
    }
}

/// Attributs `CLE=valeur` séparés par des virgules ; les valeurs entre guillemets
/// peuvent elles-mêmes contenir des virgules (`CODECS="avc1.64002A,mp4a.40.2"`).
fn parse_attributes(input: &str) -> HashMap<String, String> {
    let mut attrs = HashMap::new();
    let mut rest = input;
    while !rest.is_empty() {
        let Some((key, after)) = rest.split_once('=') else { break };
        let (value, remaining) = if let Some(quoted) = after.strip_prefix('"') {
            match quoted.split_once('"') {
                Some((value, tail)) => (value, tail.strip_prefix(',').unwrap_or(tail)),
                None => (quoted, ""),
            }
        } else {
            match after.split_once(',') {
                Some((value, tail)) => (value, tail),
                None => (after, ""),
            }
        };
        attrs.insert(key.trim().to_owned(), value.to_owned());
        rest = remaining;
    }
    attrs
}

fn resolve(base_url: &str, uri: &str) -> String {
    if uri.starts_with("http://") || uri.starts_with("https://") {
        return uri.to_owned();
    }
    let base = base_url.split('?').next().unwrap_or(base_url);
    match base.rfind('/') {
        Some(slash) => format!("{}/{}", &base[..slash], uri.trim_start_matches('/')),
        None => uri.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MASTER: &str = include_str!("../tests/fixtures/master_live.m3u8");

    #[test]
    fn parses_twitch_master_playlist() {
        let variants = parse_master(MASTER, "https://usher.ttvnw.net/api/channel/hls/x.m3u8?sig=1");
        let summary: Vec<_> = variants.iter().map(|v| (v.name.as_str(), v.height, v.source, v.audio_only)).collect();
        assert_eq!(
            summary,
            [
                ("1080p60", 1080, true, false),
                ("720p60", 720, false, false),
                ("480p", 480, false, false),
                ("160p", 160, false, false),
                ("audio_only", 0, false, true),
            ]
        );
        let source = &variants[0];
        assert_eq!(source.width, 1920);
        assert_eq!(source.bandwidth, 6_000_000);
        assert!((source.frame_rate - 60.0).abs() < 0.01);
        assert_eq!(source.url, "https://video-weaver.example.hls.ttvnw.net/v1/playlist/source.m3u8");
        assert_eq!(variants[2].url, "https://usher.ttvnw.net/api/channel/hls/relative/480p.m3u8");
    }

    #[test]
    fn tolerates_unknown_tags_and_missing_media_groups() {
        let body = "#EXTM3U\n#EXT-X-SOMETHING-NEW:FOO=1\n#EXT-X-STREAM-INF:BANDWIDTH=900,RESOLUTION=852x480,FRAME-RATE=30.000\nhttps://e/480.m3u8\n";
        let variants = parse_master(body, "https://e/master.m3u8");
        assert_eq!(variants.len(), 1);
        assert_eq!(variants[0].name, "480p");
        assert!(!variants[0].source);
    }

    #[test]
    fn empty_or_garbage_bodies_yield_no_variant() {
        assert!(parse_master("", "https://e/").is_empty());
        assert!(parse_master("<html>Not found</html>", "https://e/").is_empty());
    }

    #[test]
    fn quoted_attribute_values_keep_their_commas() {
        let attrs = parse_attributes(r#"BANDWIDTH=1,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked""#);
        assert_eq!(attrs["CODECS"], "avc1.64002A,mp4a.40.2");
        assert_eq!(attrs["VIDEO"], "chunked");
        assert_eq!(attrs["BANDWIDTH"], "1");
    }

    #[test]
    fn usher_url_encodes_the_token() {
        let url = live_master_url("https://usher.ttvnw.net", "ZeRaToR", r#"{"channel":"zerator"}"#, "abc123", 42);
        assert!(url.starts_with("https://usher.ttvnw.net/api/channel/hls/zerator.m3u8?"));
        assert!(url.contains("token=%7B%22channel%22%3A%22zerator%22%7D"));
        assert!(url.contains("sig=abc123"));
        assert!(url.contains("supported_codecs=avc1"));
    }
}
