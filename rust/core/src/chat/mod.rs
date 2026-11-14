//! Chat en lecture : IRC de Twitch sur WebSocket, en anonyme (l'envoi passe par Helix).
//!
//! Les événements sont regroupés et livrés à Kotlin toutes les ~100 ms : un chat rapide
//! (des centaines de messages par seconde) ne déclenche qu'une dizaine de recompositions.

pub(crate) mod decor;
mod irc;
#[cfg(test)]
mod tests;

use std::future::Future;
use std::sync::{Arc, RwLock};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::task::AbortHandle;
use tokio::time::Instant;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::Connector;

use decor::Decor;
use irc::parse_line;
pub(crate) use irc::{emote_url, readable_color};

const BATCH_INTERVAL: Duration = Duration::from_millis(100);
/// Un lot ne garde que les plus récents : au-delà, l'écran ne pourrait de toute façon pas suivre.
const MAX_BATCH: usize = 300;
/// Twitch envoie un PING toutes les ~5 minutes : un silence plus long signale une connexion morte.
const SILENCE_TIMEOUT: Duration = Duration::from_secs(6 * 60);
const MAX_BACKOFF: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum ChatFragment {
    Text { text: String },
    Emote { id: String, name: String, url: String },
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ChatMessage {
    pub id: String,
    pub user_id: String,
    pub login: String,
    pub display_name: String,
    /// ARGB, déjà rendue lisible sur fond sombre.
    pub color: u32,
    pub fragments: Vec<ChatFragment>,
    /// Message en `/me` : affiché dans la couleur du pseudo.
    pub action: bool,
    /// Texte système d'un abonnement, raid ou annonce.
    pub system: Option<String>,
    pub timestamp_ms: i64,
    /// Images des badges (abonné, modérateur…), dans l'ordre de Twitch.
    pub badges: Vec<String>,
    /// Message auquel celui-ci répond (fil de réponses de Twitch).
    pub reply_to: Option<ReplyParent>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ReplyParent {
    pub display_name: String,
    pub body: String,
}

/// Émote proposée dans le sélecteur d'envoi : on insère son nom dans le message.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PickerEmote {
    pub name: String,
    pub url: String,
}

/// Ordre du sélecteur : émotes Twitch de la chaîne, puis ses émotes tierces, puis le reste
/// (autres abonnements, globales en dernier). Un nom déjà vu n'est pas répété.
pub(crate) fn picker_order(
    broadcaster_id: &str,
    twitch: Vec<crate::helix::HelixEmote>,
    third_party: Vec<(String, String)>,
) -> Vec<PickerEmote> {
    let rank = |owner: &str| match owner {
        o if o == broadcaster_id => 0,
        "0" | "" => 3,
        _ => 2,
    };
    let mut all: Vec<(u8, PickerEmote)> = twitch
        .into_iter()
        .map(|e| (rank(&e.owner_id), PickerEmote { url: irc::emote_url(&e.id), name: e.name }))
        .chain(third_party.into_iter().map(|(name, url)| (1, PickerEmote { name, url })))
        .collect();
    // Tri stable : l'ordre de Twitch est gardé dans chaque groupe.
    all.sort_by_key(|(rank, _)| *rank);
    let mut seen = std::collections::HashSet::new();
    all.into_iter().map(|(_, e)| e).filter(|e| seen.insert(e.name.clone())).collect()
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum ChatState {
    Connecting,
    Joined,
    /// Connexion perdue : nouvel essai automatique.
    Reconnecting,
}

// `Message` est bien plus gros que les autres variantes, mais UniFFI n'accepte pas de `Box` ici ;
// les événements voyagent par lots bornés (~100 ms), la place perdue reste négligeable.
#[allow(clippy::large_enum_variant)]
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum ChatEvent {
    Message { message: ChatMessage },
    /// Messages d'un utilisateur banni ou réduit au silence.
    ClearUser { user_id: String },
    ClearAll,
    Delete { message_id: String },
    Notice { text: String },
    State { state: ChatState },
    /// Modes du salon (ROOMSTATE) ; seuls les champs présents ont changé, les autres sont `None`.
    /// `slow_s` : secondes entre deux messages (0 = désactivé) ; `followers_min` : ancienneté de
    /// follow exigée en minutes (-1 = désactivé, 0 = tout follower).
    Room { emote_only: Option<bool>, slow_s: Option<i32>, followers_min: Option<i32>, subs_only: Option<bool> },
}

/// Implémenté côté Kotlin. Appelé depuis un thread du cœur, jamais le thread principal.
#[uniffi::export(with_foreign)]
pub trait ChatListener: Send + Sync {
    fn on_events(&self, events: Vec<ChatEvent>);
}

/// Connexion à un salon ; la libérer (`destroy()` côté Kotlin) coupe la socket.
#[derive(uniffi::Object)]
pub struct ChatSession {
    task: AbortHandle,
}

impl Drop for ChatSession {
    fn drop(&mut self) {
        self.task.abort();
    }
}

/// `loader` remplit `decor` pendant que la connexion s'établit ; couper la session l'annule aussi.
pub(crate) fn spawn(
    url: String,
    tls: Arc<rustls::ClientConfig>,
    channel: String,
    listener: Arc<dyn ChatListener>,
    decor: Arc<RwLock<Decor>>,
    loader: impl Future<Output = ()> + Send + 'static,
) -> ChatSession {
    let task = tokio::spawn(async move {
        tokio::join!(loader, run(url, tls, channel.to_lowercase(), listener, decor));
    });
    ChatSession { task: task.abort_handle() }
}

async fn run(url: String, tls: Arc<rustls::ClientConfig>, channel: String, listener: Arc<dyn ChatListener>, decor: Arc<RwLock<Decor>>) {
    let mut backoff = Duration::from_secs(1);
    let mut state = ChatState::Connecting;
    loop {
        listener.on_events(vec![ChatEvent::State { state: state.clone() }]);
        let joined = session(&url, &tls, &channel, listener.as_ref(), &decor).await;
        // Une session qui avait abouti repart vite ; des échecs répétés espacent les essais.
        backoff = if joined { Duration::from_secs(1) } else { (backoff * 2).min(MAX_BACKOFF) };
        state = ChatState::Reconnecting;
        listener.on_events(vec![ChatEvent::State { state: state.clone() }]);
        tokio::time::sleep(backoff).await;
    }
}

/// Une connexion complète ; rend `true` si le salon avait été rejoint.
async fn session(url: &str, tls: &Arc<rustls::ClientConfig>, channel: &str, listener: &dyn ChatListener, decor: &RwLock<Decor>) -> bool {
    let connector = Some(Connector::Rustls(tls.clone()));
    let Ok(Ok((socket, _))) = tokio::time::timeout(
        Duration::from_secs(10),
        tokio_tungstenite::connect_async_tls_with_config(url, None, true, connector),
    )
    .await
    else {
        return false;
    };
    let (mut write, mut read) = socket.split();

    // Pseudo anonyme « justinfan » : lecture seule, aucun jeton envoyé sur cette socket.
    let nick = format!("justinfan{}", 10_000 + rand_suffix() % 80_000);
    for line in [
        "CAP REQ :twitch.tv/tags twitch.tv/commands".to_owned(),
        "PASS SCHMOOPIIE".to_owned(),
        format!("NICK {nick}"),
        format!("JOIN #{channel}"),
    ] {
        if write.send(Message::text(line)).await.is_err() {
            return false;
        }
    }

    let mut joined = false;
    let mut batch: Vec<ChatEvent> = Vec::new();
    let mut last_seen = Instant::now();
    let mut tick = tokio::time::interval(BATCH_INTERVAL);
    loop {
        tokio::select! {
            frame = read.next() => {
                let text = match frame {
                    Some(Ok(Message::Text(text))) => text,
                    Some(Ok(Message::Ping(payload))) => {
                        let _ = write.send(Message::Pong(payload)).await;
                        continue;
                    }
                    Some(Ok(Message::Close(_))) | Some(Err(_)) | None => break,
                    Some(Ok(_)) => continue,
                };
                last_seen = Instant::now();
                // Une trame peut porter plusieurs lignes IRC.
                for line in text.split("\r\n").filter_map(parse_line) {
                    match line.command.as_str() {
                        "PING" => {
                            let _ = write.send(Message::text(format!("PONG :{}", line.trailing()))).await;
                        }
                        // Twitch annonce une maintenance : on se reconnecte tout de suite.
                        "RECONNECT" => {
                            flush(listener, &mut batch);
                            return joined;
                        }
                        "ROOMSTATE" => {
                            if !joined {
                                joined = true;
                                batch.push(ChatEvent::State { state: ChatState::Joined });
                            }
                            batch.push(irc::room(&line));
                        }
                        _ => {
                            let Some(mut event) = irc::to_event(&line) else { continue };
                            if let ChatEvent::Message { message } = &mut event {
                                let decor = decor.read().unwrap_or_else(|e| e.into_inner());
                                if decor.hides(message) {
                                    continue;
                                }
                                decor.apply(message, line.tag("badges"));
                            }
                            batch.push(event);
                        }
                    }
                }
                if batch.len() > MAX_BATCH {
                    batch.drain(..batch.len() - MAX_BATCH);
                }
            }
            _ = tick.tick() => {
                flush(listener, &mut batch);
                if last_seen.elapsed() > SILENCE_TIMEOUT {
                    break;
                }
            }
        }
    }
    flush(listener, &mut batch);
    joined
}

fn flush(listener: &dyn ChatListener, batch: &mut Vec<ChatEvent>) {
    if !batch.is_empty() {
        listener.on_events(std::mem::take(batch));
    }
}

fn rand_suffix() -> u32 {
    use std::hash::{BuildHasher, Hasher};
    let mut hasher = std::collections::hash_map::RandomState::new().build_hasher();
    hasher.write_u8(0);
    hasher.finish() as u32
}
