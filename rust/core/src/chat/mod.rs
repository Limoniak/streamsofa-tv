//! Chat en lecture : IRC de Twitch sur WebSocket, en anonyme (l'envoi passe par Helix).
//!
//! Les événements sont regroupés et livrés à Kotlin toutes les ~100 ms : un chat rapide
//! (des centaines de messages par seconde) ne déclenche qu'une dizaine de recompositions.

mod irc;
#[cfg(test)]
mod tests;

use std::sync::Arc;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::task::AbortHandle;
use tokio::time::Instant;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::Connector;

use irc::parse_line;

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
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum ChatState {
    Connecting,
    Joined,
    /// Connexion perdue : nouvel essai automatique.
    Reconnecting,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum ChatEvent {
    Message { message: ChatMessage },
    /// Messages d'un utilisateur banni ou réduit au silence.
    ClearUser { user_id: String },
    ClearAll,
    Delete { message_id: String },
    Notice { text: String },
    State { state: ChatState },
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

pub(crate) fn spawn(url: String, tls: Arc<rustls::ClientConfig>, channel: String, listener: Arc<dyn ChatListener>) -> ChatSession {
    let task = tokio::spawn(run(url, tls, channel.to_lowercase(), listener));
    ChatSession { task: task.abort_handle() }
}

async fn run(url: String, tls: Arc<rustls::ClientConfig>, channel: String, listener: Arc<dyn ChatListener>) {
    let mut backoff = Duration::from_secs(1);
    let mut state = ChatState::Connecting;
    loop {
        listener.on_events(vec![ChatEvent::State { state: state.clone() }]);
        let joined = session(&url, &tls, &channel, listener.as_ref()).await;
        // Une session qui avait abouti repart vite ; des échecs répétés espacent les essais.
        backoff = if joined { Duration::from_secs(1) } else { (backoff * 2).min(MAX_BACKOFF) };
        state = ChatState::Reconnecting;
        listener.on_events(vec![ChatEvent::State { state: state.clone() }]);
        tokio::time::sleep(backoff).await;
    }
}

/// Une connexion complète ; rend `true` si le salon avait été rejoint.
async fn session(url: &str, tls: &Arc<rustls::ClientConfig>, channel: &str, listener: &dyn ChatListener) -> bool {
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
                        "ROOMSTATE" if !joined => {
                            joined = true;
                            batch.push(ChatEvent::State { state: ChatState::Joined });
                        }
                        _ => batch.extend(irc::to_event(&line)),
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
