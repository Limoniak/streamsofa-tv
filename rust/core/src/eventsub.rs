//! Raids de la chaîne regardée, via EventSub WebSocket. Exige un compte connecté :
//! Twitch n'offre aucune source anonyme pour cet événement.

use std::time::Duration;

use futures_util::StreamExt;
use serde::Deserialize;
use tokio::task::AbortHandle;
use tokio_tungstenite::tungstenite::Message;

/// Un raid lancé par la chaîne regardée.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Raid {
    pub to_login: String,
    pub to_name: String,
    pub viewers: u32,
}

/// Implémenté côté Kotlin. Appelé depuis un thread du cœur, jamais le thread principal.
#[uniffi::export(with_foreign)]
pub trait RaidListener: Send + Sync {
    fn on_raid(&self, raid: Raid);
}

/// Surveillance en cours ; la libérer (`destroy()` côté Kotlin) coupe la socket.
#[derive(uniffi::Object)]
pub struct RaidWatch {
    pub(crate) task: Option<AbortHandle>,
}

impl Drop for RaidWatch {
    fn drop(&mut self) {
        if let Some(task) = &self.task {
            task.abort();
        }
    }
}

/// Twitch envoie un keepalive toutes les ~10 s : un silence plus long signale une connexion morte.
pub(crate) const SILENCE_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Debug, PartialEq)]
pub(crate) enum Frame {
    Welcome { session_id: String },
    /// Twitch déplace la session : on rejoint cette URL, l'abonnement suit.
    Reconnect { url: String },
    Raid(Raid),
    Other,
}

pub(crate) fn parse(text: &str) -> Frame {
    #[derive(Deserialize)]
    struct Metadata {
        message_type: String,
    }
    #[derive(Deserialize)]
    struct Session {
        id: String,
        #[serde(default)]
        reconnect_url: Option<String>,
    }
    #[derive(Deserialize)]
    struct RaidEvent {
        to_broadcaster_user_login: String,
        to_broadcaster_user_name: String,
        #[serde(default)]
        viewers: u32,
    }
    #[derive(Deserialize)]
    struct Payload {
        #[serde(default)]
        session: Option<Session>,
        #[serde(default)]
        event: Option<RaidEvent>,
    }
    #[derive(Deserialize)]
    struct Envelope {
        metadata: Metadata,
        #[serde(default)]
        payload: Option<Payload>,
    }
    let Ok(envelope) = serde_json::from_str::<Envelope>(text) else { return Frame::Other };
    let payload = envelope.payload;
    match envelope.metadata.message_type.as_str() {
        "session_welcome" => match payload.and_then(|p| p.session) {
            Some(session) => Frame::Welcome { session_id: session.id },
            None => Frame::Other,
        },
        "session_reconnect" => match payload.and_then(|p| p.session).and_then(|s| s.reconnect_url) {
            Some(url) => Frame::Reconnect { url },
            None => Frame::Other,
        },
        "notification" => match payload.and_then(|p| p.event) {
            Some(e) => Frame::Raid(Raid { to_login: e.to_broadcaster_user_login, to_name: e.to_broadcaster_user_name, viewers: e.viewers }),
            None => Frame::Other,
        },
        _ => Frame::Other,
    }
}

/// Prochaine trame texte, ou `None` si la socket se ferme ou reste muette trop longtemps.
pub(crate) async fn next_text<S>(socket: &mut S) -> Option<String>
where
    S: futures_util::Stream<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin,
{
    loop {
        match tokio::time::timeout(SILENCE_TIMEOUT, socket.next()).await {
            Ok(Some(Ok(Message::Text(text)))) => return Some(text.to_string()),
            Ok(Some(Ok(Message::Close(_)))) | Ok(Some(Err(_))) | Ok(None) | Err(_) => return None,
            Ok(Some(Ok(_))) => continue,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_welcome_raid_and_reconnect() {
        let welcome = r#"{"metadata":{"message_id":"1","message_type":"session_welcome"},
            "payload":{"session":{"id":"AQoQ","status":"connected","keepalive_timeout_seconds":10,"reconnect_url":null}}}"#;
        assert_eq!(parse(welcome), Frame::Welcome { session_id: "AQoQ".into() });
        let raid = r#"{"metadata":{"message_type":"notification","subscription_type":"channel.raid"},
            "payload":{"subscription":{"type":"channel.raid"},"event":{"from_broadcaster_user_login":"a",
            "to_broadcaster_user_id":"2","to_broadcaster_user_login":"zerator","to_broadcaster_user_name":"ZeratoR","viewers":1234}}}"#;
        assert_eq!(parse(raid), Frame::Raid(Raid { to_login: "zerator".into(), to_name: "ZeratoR".into(), viewers: 1234 }));
        let reconnect = r#"{"metadata":{"message_type":"session_reconnect"},
            "payload":{"session":{"id":"AQoQ","reconnect_url":"wss://eventsub.wss.twitch.tv/ws?x=1"}}}"#;
        assert_eq!(parse(reconnect), Frame::Reconnect { url: "wss://eventsub.wss.twitch.tv/ws?x=1".into() });
        assert_eq!(parse(r#"{"metadata":{"message_type":"session_keepalive"},"payload":{}}"#), Frame::Other);
        assert_eq!(parse("pas du json"), Frame::Other);
    }
}
