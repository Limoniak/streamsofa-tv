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
    fn templates_are_lowercase_and_sizable() {
        assert_eq!(
            thumbnail_template("Zerator"),
            "https://static-cdn.jtvnw.net/previews-ttv/live_user_zerator-{width}x{height}.jpg"
        );
        assert_eq!(box_art_template("509658"), "https://static-cdn.jtvnw.net/ttv-boxart/509658-{width}x{height}.jpg");
    }
}
