//! Modèle du catalogue exposé à Kotlin, indépendant de la source (Helix ou GQL).

/// Un live en cours.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LiveStream {
    pub channel_id: String,
    pub login: String,
    pub display_name: String,
    /// Vide si inconnu : l'UI affiche alors l'initiale.
    pub avatar_url: String,
    pub title: String,
    pub category_id: String,
    pub category_name: String,
    pub viewers: u32,
    /// Début du live, en secondes Unix (0 si inconnu).
    pub started_at: i64,
    /// Modèle d'URL : remplacer `{width}` et `{height}` par la taille voulue, en pixels.
    pub thumbnail_url: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Category {
    pub id: String,
    pub name: String,
    /// 0 si la source ne le fournit pas (Helix).
    pub viewers: u32,
    /// Modèle d'URL : remplacer `{width}` et `{height}`.
    pub box_art_url: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct StreamPage {
    pub items: Vec<LiveStream>,
    /// `None` : dernière page.
    pub cursor: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CategoryPage {
    pub items: Vec<Category>,
    pub cursor: Option<String>,
}

/// Une chaîne, en live ou non.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Channel {
    pub id: String,
    pub login: String,
    pub display_name: String,
    pub avatar_url: String,
    pub description: String,
}

/// Une VOD (rediffusion, temps fort ou vidéo importée).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Video {
    pub id: String,
    pub title: String,
    pub channel_id: String,
    pub channel_login: String,
    pub channel_name: String,
    pub duration_s: u32,
    /// Secondes Unix (0 si inconnu).
    pub published_at: i64,
    pub views: u32,
    /// Modèle d'URL avec `{width}` et `{height}` ; vide tant que Twitch prépare la vidéo.
    pub thumbnail_url: String,
    /// Reprise pour le profil actif : dernière position vue (0 si jamais vue).
    pub position_s: u32,
    pub finished: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct VideoPage {
    pub items: Vec<Video>,
    pub cursor: Option<String>,
}

/// Chaîne trouvée par la recherche.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchChannel {
    pub id: String,
    pub login: String,
    pub display_name: String,
    pub avatar_url: String,
    pub live: bool,
    /// Titre du live en cours (vide hors ligne ou si la source ne le donne pas).
    pub title: String,
    pub category_name: String,
    /// 0 si hors ligne ou inconnu (Helix ne le donne pas).
    pub viewers: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchResults {
    pub channels: Vec<SearchChannel>,
    pub categories: Vec<Category>,
}

/// Chapitre d'une VOD : le jeu ou la catégorie à partir de `position_ms`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Chapter {
    pub position_ms: u64,
    pub title: String,
}

/// Sondage et prédiction en cours sur une chaîne, en lecture seule.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ChannelActivity {
    pub poll: Option<Poll>,
    pub prediction: Option<Prediction>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Poll {
    pub title: String,
    pub choices: Vec<PollChoice>,
    pub total_votes: u32,
    /// `false` : sondage terminé, on montre le résultat.
    pub active: bool,
    pub remaining_ms: u64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PollChoice {
    pub title: String,
    pub votes: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum PredictionStatus {
    /// Paris ouverts jusqu'à `locks_at_s`.
    Active,
    /// Paris fermés, résultat attendu.
    Locked,
    Resolved,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Prediction {
    pub title: String,
    pub status: PredictionStatus,
    pub outcomes: Vec<PredictionOutcome>,
    /// Fin des paris, en secondes Unix.
    pub locks_at_s: i64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PredictionOutcome {
    pub title: String,
    /// Points de chaîne misés.
    pub points: u64,
    pub users: u32,
    pub winner: bool,
}

/// Un clip ; `slug` sert à le lire.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Clip {
    pub slug: String,
    pub title: String,
    pub duration_s: u32,
    pub views: u32,
    /// Secondes Unix (0 si inconnu).
    pub created_at: i64,
    /// URL directe (taille fixe), vide si inconnue.
    pub thumbnail_url: String,
    pub curator: String,
}

/// Fichier MP4 d'un clip, déjà signé pour une lecture anonyme.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ClipQuality {
    /// Hauteur en pixels (0 si inconnue).
    pub height: u32,
    pub url: String,
}

/// Créneau du planning d'une chaîne.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScheduleSegment {
    /// Secondes Unix.
    pub start_at: i64,
    /// 0 si la fin n'est pas annoncée.
    pub end_at: i64,
    pub title: String,
    pub category_name: String,
}

/// Panneau « À propos » d'une chaîne (souvent une simple image).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Panel {
    pub title: String,
    pub description: String,
    pub image_url: String,
    pub link_url: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChannelAbout {
    /// Créneaux à venir ou en cours, dans l'ordre.
    pub schedule: Vec<ScheduleSegment>,
    pub panels: Vec<Panel>,
}

/// Message du chat d'une VOD, à afficher quand la lecture atteint `offset_s`.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ReplayComment {
    pub offset_s: u32,
    pub message: crate::chat::ChatMessage,
}

/// Miniature de VOD en modèle `{width}x{height}`, quelle que soit la source :
/// Helix écrit `%{width}`, GQL une taille fixe demandée (`320x180`).
pub fn video_thumbnail(url: &str) -> String {
    // Image « en cours de traitement » : l'UI affiche plutôt son propre fond.
    if url.is_empty() || url.contains("/_404/") {
        return String::new();
    }
    url.replace("%{width}", "{width}").replace("%{height}", "{height}").replace("320x180", "{width}x{height}")
}

/// Durée Helix (`3h2m10s`, `45m`, `12s`) en secondes.
pub fn parse_duration(value: &str) -> u32 {
    let mut total = 0u32;
    let mut number = 0u32;
    for c in value.chars() {
        match c {
            '0'..='9' => number = number.saturating_mul(10).saturating_add(c as u32 - '0' as u32),
            'h' => (total, number) = (total.saturating_add(number * 3_600), 0),
            'm' => (total, number) = (total.saturating_add(number * 60), 0),
            's' => (total, number) = (total.saturating_add(number), 0),
            _ => return 0,
        }
    }
    total
}

/// Aperçu d'un live. Même format pour Helix et GQL, ce qui garde un cache d'images commun.
pub fn thumbnail_template(login: &str) -> String {
    format!("https://static-cdn.jtvnw.net/previews-ttv/live_user_{}-{{width}}x{{height}}.jpg", login.to_lowercase())
}

pub fn box_art_template(category_id: &str) -> String {
    format!("https://static-cdn.jtvnw.net/ttv-boxart/{category_id}-{{width}}x{{height}}.jpg")
}

/// Horodatage RFC 3339 en UTC (`2026-11-13T09:05:00Z`, fractions tolérées) vers secondes Unix.
pub fn parse_utc(value: &str) -> Option<i64> {
    let b = value.as_bytes();
    if b.len() < 20 || b[4] != b'-' || b[7] != b'-' || b[10] != b'T' || b[13] != b':' || b[16] != b':' {
        return None;
    }
    if !value.ends_with('Z') {
        return None;
    }
    let num = |range: std::ops::Range<usize>| value.get(range)?.parse::<i64>().ok();
    let (year, month, day) = (num(0..4)?, num(5..7)?, num(8..10)?);
    let (hour, minute, second) = (num(11..13)?, num(14..16)?, num(17..19)?);
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) || hour > 23 || minute > 59 || second > 60 {
        return None;
    }
    Some(days_from_civil(year, month, day) * 86_400 + hour * 3_600 + minute * 60 + second)
}

/// Nombre de jours depuis le 1970-01-01 (algorithme de H. Hinnant).
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_twitch_timestamps() {
        assert_eq!(parse_utc("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(parse_utc("2026-11-13T09:05:30Z"), Some(1_794_560_730));
        assert_eq!(parse_utc("2024-02-29T23:59:59.123Z"), Some(1_709_251_199));
        assert_eq!(parse_utc("2026-11-13 09:05:30"), None);
        assert_eq!(parse_utc(""), None);
    }

    #[test]
    fn parses_helix_durations() {
        assert_eq!(parse_duration("3h2m10s"), 10_930);
        assert_eq!(parse_duration("45m"), 2_700);
        assert_eq!(parse_duration("12s"), 12);
        assert_eq!(parse_duration("abc"), 0);
    }

    #[test]
    fn video_thumbnails_become_templates() {
        assert_eq!(video_thumbnail("https://x/thumb-%{width}x%{height}.jpg"), "https://x/thumb-{width}x{height}.jpg");
        assert_eq!(video_thumbnail("https://x/thumb-320x180.jpeg"), "https://x/thumb-{width}x{height}.jpeg");
        assert_eq!(video_thumbnail("https://vod-secure.twitch.tv/_404/404_processing_320x180.png"), "");
    }

    #[test]
    fn templates_are_lowercase_and_sizable() {
        assert_eq!(
            thumbnail_template("Zerator"),
            "https://static-cdn.jtvnw.net/previews-ttv/live_user_zerator-{width}x{height}.jpg"
        );
        assert_eq!(box_art_template("509658"), "https://static-cdn.jtvnw.net/ttv-boxart/509658-{width}x{height}.jpg");
    }
}
