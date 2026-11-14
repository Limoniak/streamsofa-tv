//! Lignes IRC de Twitch (avec tags IRCv3) et leur traduction en événements de chat.

use std::collections::HashMap;

use super::{ChatEvent, ChatFragment, ChatMessage, ReplyParent};

#[derive(Debug, Default, PartialEq)]
pub struct IrcLine {
    pub tags: HashMap<String, String>,
    /// `nick!user@host` sans le `:` initial.
    pub prefix: String,
    pub command: String,
    pub params: Vec<String>,
}

impl IrcLine {
    pub fn tag(&self, key: &str) -> &str {
        self.tags.get(key).map(String::as_str).unwrap_or("")
    }

    /// Dernier paramètre : le texte du message pour PRIVMSG, USERNOTICE…
    pub fn trailing(&self) -> &str {
        self.params.last().map(String::as_str).unwrap_or("")
    }
}

pub fn parse_line(line: &str) -> Option<IrcLine> {
    let mut rest = line.trim_end_matches(['\r', '\n']);
    if rest.is_empty() {
        return None;
    }
    let mut out = IrcLine::default();
    if let Some(tagged) = rest.strip_prefix('@') {
        let (tags, after) = tagged.split_once(' ')?;
        for pair in tags.split(';') {
            let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
            out.tags.insert(key.to_owned(), unescape_tag(value));
        }
        rest = after.trim_start();
    }
    if let Some(prefixed) = rest.strip_prefix(':') {
        let (prefix, after) = prefixed.split_once(' ')?;
        out.prefix = prefix.to_owned();
        rest = after.trim_start();
    }
    let (command, mut params) = rest.split_once(' ').unwrap_or((rest, ""));
    out.command = command.to_owned();
    while !params.is_empty() {
        if let Some(trailing) = params.strip_prefix(':') {
            out.params.push(trailing.to_owned());
            break;
        }
        let (param, after) = params.split_once(' ').unwrap_or((params, ""));
        out.params.push(param.to_owned());
        params = after.trim_start();
    }
    Some(out)
}

fn unescape_tag(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut chars = value.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('s') => out.push(' '),
            Some(':') => out.push(';'),
            Some('r') => out.push('\r'),
            Some('n') => out.push('\n'),
            Some(other) => out.push(other),
            None => {}
        }
    }
    out
}

/// Traduit une ligne en événement d'interface ; `None` pour tout ce que l'écran n'affiche pas.
pub fn to_event(line: &IrcLine) -> Option<ChatEvent> {
    match line.command.as_str() {
        "PRIVMSG" => Some(ChatEvent::Message { message: message(line, None) }),
        // Abonnements, raids, annonces : le texte système, suivi du message éventuel.
        "USERNOTICE" => {
            let system = line.tag("system-msg").to_owned();
            Some(ChatEvent::Message { message: message(line, Some(system)) })
        }
        "CLEARCHAT" => match line.params.get(1) {
            Some(_) => Some(ChatEvent::ClearUser { user_id: line.tag("target-user-id").to_owned() }),
            None => Some(ChatEvent::ClearAll),
        },
        "CLEARMSG" => Some(ChatEvent::Delete { message_id: line.tag("target-msg-id").to_owned() }),
        "NOTICE" => Some(ChatEvent::Notice { text: line.trailing().to_owned() }),
        _ => None,
    }
}

/// Modes du salon portés par ROOMSTATE (complet au premier, puis seulement ce qui change).
pub fn room(line: &IrcLine) -> ChatEvent {
    let flag = |key: &str| line.tags.get(key).map(|v| v == "1");
    let number = |key: &str| line.tags.get(key).and_then(|v| v.parse::<i32>().ok());
    ChatEvent::Room {
        emote_only: flag("emote-only"),
        slow_s: number("slow"),
        followers_min: number("followers-only"),
        subs_only: flag("subs-only"),
    }
}

fn message(line: &IrcLine, system: Option<String>) -> ChatMessage {
    let login = line.tag("login").to_owned();
    let login = if login.is_empty() { line.prefix.split('!').next().unwrap_or("").to_owned() } else { login };
    let display_name = match line.tag("display-name") {
        "" => login.clone(),
        name => name.to_owned(),
    };
    // Avec USERNOTICE le texte est facultatif (un abonnement sans message).
    let raw = if line.command == "USERNOTICE" && line.params.len() < 2 { "" } else { line.trailing() };
    let (text, action) = match raw.strip_prefix("\u{1}ACTION ") {
        Some(inner) => (inner.trim_end_matches('\u{1}'), true),
        None => (raw, false),
    };
    ChatMessage {
        id: line.tag("id").to_owned(),
        user_id: line.tag("user-id").to_owned(),
        color: readable_color(line.tag("color"), &login),
        fragments: fragments(text, line.tag("emotes")),
        timestamp_ms: line.tag("tmi-sent-ts").parse().unwrap_or(0),
        system: system.filter(|s| !s.is_empty()),
        login,
        display_name,
        action,
        badges: Vec::new(),
        reply_to: match line.tag("reply-parent-msg-id") {
            "" => None,
            _ => Some(ReplyParent {
                display_name: match line.tag("reply-parent-display-name") {
                    "" => line.tag("reply-parent-user-login").to_owned(),
                    name => name.to_owned(),
                },
                body: line.tag("reply-parent-msg-body").to_owned(),
            }),
        },
    }
}

pub fn emote_url(id: &str) -> String {
    format!("https://static-cdn.jtvnw.net/emoticons/v2/{id}/default/dark/2.0")
}

/// Découpe le texte selon le tag `emotes` (`id:début-fin,début-fin/id:…`), dont les positions
/// comptent des caractères Unicode et non des octets.
pub fn fragments(text: &str, emotes: &str) -> Vec<ChatFragment> {
    let chars: Vec<char> = text.chars().collect();
    let mut ranges: Vec<(usize, usize, &str)> = Vec::new();
    for spec in emotes.split('/').filter(|s| !s.is_empty()) {
        let Some((id, positions)) = spec.split_once(':') else { continue };
        for pos in positions.split(',') {
            let Some((start, end)) = pos.split_once('-') else { continue };
            if let (Ok(start), Ok(end)) = (start.parse::<usize>(), end.parse::<usize>()) {
                if start <= end && end < chars.len() {
                    ranges.push((start, end, id));
                }
            }
        }
    }
    ranges.sort_unstable_by_key(|r| r.0);

    let mut out = Vec::new();
    let mut cursor = 0;
    for (start, end, id) in ranges {
        if start < cursor {
            continue; // Plages qui se chevauchent : tag mal formé, on garde la première.
        }
        if start > cursor {
            out.push(ChatFragment::Text { text: chars[cursor..start].iter().collect() });
        }
        out.push(ChatFragment::Emote {
            name: chars[start..=end].iter().collect(),
            url: emote_url(id),
            id: id.to_owned(),
        });
        cursor = end + 1;
    }
    if cursor < chars.len() {
        out.push(ChatFragment::Text { text: chars[cursor..].iter().collect() });
    }
    out
}

/// Couleurs proposées par Twitch aux comptes qui n'en ont pas choisi.
const DEFAULT_COLORS: [u32; 15] = [
    0xFF0000, 0x0000FF, 0x008000, 0xB22222, 0xFF7F50, 0x9ACD32, 0xFF4500, 0x2E8B57, 0xDAA520, 0xD2691E, 0x5F9EA0,
    0x1E90FF, 0xFF69B4, 0x8A2BE2, 0x00FF7F,
];

/// Couleur du pseudo en ARGB, éclaircie si elle serait illisible sur fond sombre.
pub fn readable_color(tag: &str, login: &str) -> u32 {
    let rgb = tag
        .strip_prefix('#')
        .and_then(|hex| u32::from_str_radix(hex, 16).ok())
        .unwrap_or_else(|| {
            let hash = login.bytes().fold(0u32, |h, b| h.wrapping_mul(31).wrapping_add(b as u32));
            DEFAULT_COLORS[hash as usize % DEFAULT_COLORS.len()]
        });
    let (mut r, mut g, mut b) = ((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    // Luminance approchée (0..255) ; on mélange avec du blanc jusqu'au seuil de lisibilité.
    while (r * 299 + g * 587 + b * 114) / 1000 < 110 {
        r = (r + (255 - r) / 3 + 1).min(255);
        g = (g + (255 - g) / 3 + 1).min(255);
        b = (b + (255 - b) / 3 + 1).min(255);
    }
    0xFF00_0000 | (r << 16) | (g << 8) | b
}
