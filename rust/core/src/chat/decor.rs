//! Habillage et filtrage des messages : badges, émotes tierces (7TV, BTTV, FFZ),
//! mots et pseudos masqués. Rempli en parallèle de la connexion IRC : les premiers messages
//! peuvent arriver sans badges, jamais en retard.

use std::collections::{HashMap, HashSet};

use super::{ChatFragment, ChatMessage};

/// Clés de préférences partagées avec l'app (table `settings`, portée `global`).
pub(crate) const HIDDEN_WORDS: &str = "chat_hidden_words";
pub(crate) const HIDDEN_USERS: &str = "chat_hidden_users";
pub(crate) const THIRD_PARTY_EMOTES: &str = "third_party_emotes";

#[derive(Debug, Default)]
pub(crate) struct Decor {
    /// `set/version` → image.
    pub badges: HashMap<String, String>,
    /// Nom exact (sensible à la casse) → image.
    pub emotes: HashMap<String, String>,
    /// Pseudos en minuscules.
    pub hidden_users: HashSet<String>,
    /// Mots en minuscules, cherchés n'importe où dans le message.
    pub hidden_words: Vec<String>,
}

impl Decor {
    /// Filtres lus dans les préférences (listes séparées par des virgules ou des retours à la ligne).
    pub fn with_filters(settings: &HashMap<String, String>) -> Self {
        let list = |key: &str| -> Vec<String> {
            settings
                .get(key)
                .map(|raw| {
                    raw.split([',', '\n'])
                        .map(|item| item.trim().trim_start_matches('@').to_lowercase())
                        .filter(|item| !item.is_empty())
                        .collect()
                })
                .unwrap_or_default()
        };
        Self { hidden_users: list(HIDDEN_USERS).into_iter().collect(), hidden_words: list(HIDDEN_WORDS), ..Self::default() }
    }

    pub fn hides(&self, message: &ChatMessage) -> bool {
        if self.hidden_users.contains(&message.login.to_lowercase()) {
            return true;
        }
        if self.hidden_words.is_empty() {
            return false;
        }
        let text: String = message
            .fragments
            .iter()
            .map(|f| match f {
                ChatFragment::Text { text } => text.as_str(),
                ChatFragment::Emote { name, .. } => name.as_str(),
            })
            .collect::<Vec<_>>()
            .join(" ")
            .to_lowercase();
        self.hidden_words.iter().any(|word| text.contains(word.as_str()))
    }

    /// Badges du tag IRC `badges` (`subscriber/12,moderator/1`) et émotes tierces.
    pub fn apply(&self, message: &mut ChatMessage, badges_tag: &str) {
        message.badges = badges_tag
            .split(',')
            .filter_map(|badge| self.badges.get(badge).cloned())
            .collect();
        if !self.emotes.is_empty() {
            message.fragments = split_emotes(std::mem::take(&mut message.fragments), &self.emotes);
        }
    }
}

/// Remplace dans le texte les mots qui sont des émotes tierces, en gardant les espaces.
pub(crate) fn split_emotes(fragments: Vec<ChatFragment>, emotes: &HashMap<String, String>) -> Vec<ChatFragment> {
    let mut out: Vec<ChatFragment> = Vec::with_capacity(fragments.len());
    let push_text = |out: &mut Vec<ChatFragment>, piece: &str| {
        if piece.is_empty() {
            return;
        }
        match out.last_mut() {
            Some(ChatFragment::Text { text }) => text.push_str(piece),
            _ => out.push(ChatFragment::Text { text: piece.to_owned() }),
        }
    };
    for fragment in fragments {
        let ChatFragment::Text { text } = fragment else {
            out.push(fragment);
            continue;
        };
        for (index, word) in text.split(' ').enumerate() {
            if index > 0 {
                push_text(&mut out, " ");
            }
            match emotes.get(word) {
                Some(url) => out.push(ChatFragment::Emote { id: word.to_owned(), name: word.to_owned(), url: url.clone() }),
                None => push_text(&mut out, word),
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(t: &str) -> ChatFragment {
        ChatFragment::Text { text: t.into() }
    }

    fn msg(login: &str, fragments: Vec<ChatFragment>) -> ChatMessage {
        ChatMessage {
            id: "1".into(),
            user_id: "1".into(),
            login: login.into(),
            display_name: login.into(),
            color: 0,
            fragments,
            action: false,
            system: None,
            timestamp_ms: 0,
            badges: vec![],
        }
    }

    #[test]
    fn replaces_third_party_emotes_word_by_word() {
        let emotes = HashMap::from([("KEKW".to_string(), "u/kekw".to_string())]);
        let twitch = ChatFragment::Emote { id: "25".into(), name: "Kappa".into(), url: "u/kappa".into() };
        let out = split_emotes(vec![text("trop KEKW  "), twitch.clone(), text(" KEKWait kekw")], &emotes);
        assert_eq!(
            out,
            vec![
                text("trop "),
                ChatFragment::Emote { id: "KEKW".into(), name: "KEKW".into(), url: "u/kekw".into() },
                text("  "),
                twitch,
                text(" KEKWait kekw"),
            ]
        );
    }

    #[test]
    fn maps_badges_from_the_irc_tag() {
        let decor = Decor { badges: HashMap::from([("subscriber/12".into(), "u/sub".into()), ("moderator/1".into(), "u/mod".into())]), ..Decor::default() };
        let mut m = msg("a", vec![text("salut")]);
        decor.apply(&mut m, "moderator/1,subscriber/12,unknown/1");
        assert_eq!(m.badges, vec!["u/mod".to_string(), "u/sub".to_string()]);
        decor.apply(&mut m, "");
        assert!(m.badges.is_empty());
    }

    #[test]
    fn hides_listed_users_and_words() {
        let settings = HashMap::from([
            (HIDDEN_USERS.to_string(), " @Nightbot, streamelements\n".to_string()),
            (HIDDEN_WORDS.to_string(), "Spoiler, ,".to_string()),
        ]);
        let decor = Decor::with_filters(&settings);
        assert!(decor.hides(&msg("NightBot", vec![text("!discord")])));
        assert!(decor.hides(&msg("viewer", vec![text("gros SPOILER ici")])));
        assert!(!decor.hides(&msg("viewer", vec![text("bonjour")])));
        assert!(!Decor::with_filters(&HashMap::new()).hides(&msg("x", vec![text("spoiler")])));
    }
}
