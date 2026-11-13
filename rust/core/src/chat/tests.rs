use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::net::TcpListener;
use tokio_tungstenite::tungstenite::Message;

use super::irc::{emote_url, fragments, readable_color, to_event};
use super::*;

const PRIVMSG: &str = "@badge-info=;badges=;color=#0000FF;display-name=Kappa\\sFan;emotes=25:0-4,12-16;id=m1;\
login=kappafan;tmi-sent-ts=1794560730000;user-id=42 :kappafan!kappafan@kappafan.tmi.twitch.tv PRIVMSG #chan :Kappa hello Kappa";

fn text(t: &str) -> ChatFragment {
    ChatFragment::Text { text: t.into() }
}

fn emote(id: &str, name: &str) -> ChatFragment {
    ChatFragment::Emote { id: id.into(), name: name.into(), url: emote_url(id) }
}

#[test]
fn privmsg_with_tags_and_emotes() {
    let line = parse_line(PRIVMSG).unwrap();
    assert_eq!(line.command, "PRIVMSG");
    assert_eq!(line.params, ["#chan", "Kappa hello Kappa"]);
    let Some(ChatEvent::Message { message }) = to_event(&line) else { panic!() };
    assert_eq!(message.display_name, "Kappa Fan", "\\s dans un tag est une espace");
    assert_eq!((message.id.as_str(), message.user_id.as_str(), message.login.as_str()), ("m1", "42", "kappafan"));
    assert_eq!(message.fragments, vec![emote("25", "Kappa"), text(" hello "), emote("25", "Kappa")]);
    assert_eq!(message.timestamp_ms, 1_794_560_730_000);
    assert!(!message.action && message.system.is_none());
}

#[test]
fn emote_positions_count_characters_not_bytes() {
    // « é » et l'emoji occupent plusieurs octets mais un seul caractère chacun.
    assert_eq!(fragments("é😀 LUL", "425618:3-5"), vec![text("é😀 "), emote("425618", "LUL")]);
    assert_eq!(fragments("abc", "1:0-9"), vec![text("abc")], "plage hors du texte ignorée");
}

#[test]
fn action_and_usernotice() {
    let me = parse_line(":bob!bob@bob PRIVMSG #chan :\u{1}ACTION danse\u{1}").unwrap();
    let Some(ChatEvent::Message { message }) = to_event(&me) else { panic!() };
    assert!(message.action);
    assert_eq!(message.fragments, vec![text("danse")]);
    assert_eq!(message.login, "bob", "login lu dans le préfixe faute de tag");

    let sub = parse_line("@login=ann;system-msg=ann\\ss'est\\sabonnée;msg-id=sub USERNOTICE #chan").unwrap();
    let Some(ChatEvent::Message { message }) = to_event(&sub) else { panic!() };
    assert_eq!(message.system.as_deref(), Some("ann s'est abonnée"));
    assert!(message.fragments.is_empty());
}

#[test]
fn moderation_events() {
    let ban = parse_line("@target-user-id=42 :tmi.twitch.tv CLEARCHAT #chan :kappafan").unwrap();
    assert_eq!(to_event(&ban), Some(ChatEvent::ClearUser { user_id: "42".into() }));
    let clear = parse_line(":tmi.twitch.tv CLEARCHAT #chan").unwrap();
    assert_eq!(to_event(&clear), Some(ChatEvent::ClearAll));
    let del = parse_line("@login=x;target-msg-id=m1 :tmi.twitch.tv CLEARMSG #chan :oups").unwrap();
    assert_eq!(to_event(&del), Some(ChatEvent::Delete { message_id: "m1".into() }));
    assert_eq!(to_event(&parse_line(":tmi.twitch.tv 001 justinfan1 :Welcome").unwrap()), None);
}

#[test]
fn colors_stay_readable_on_dark_background() {
    assert_eq!(readable_color("#FF7F50", "x"), 0xFFFF7F50, "une couleur claire est conservée");
    let navy = readable_color("#000080", "x");
    let (r, g, b) = ((navy >> 16) & 0xFF, (navy >> 8) & 0xFF, navy & 0xFF);
    assert!((r * 299 + g * 587 + b * 114) / 1000 >= 110, "bleu marine éclairci");
    assert_eq!(readable_color("", "same"), readable_color("", "same"), "couleur par défaut stable");
}

#[derive(Default)]
struct Collector(Mutex<Vec<Vec<ChatEvent>>>);

impl ChatListener for Collector {
    fn on_events(&self, events: Vec<ChatEvent>) {
        self.0.lock().unwrap().push(events);
    }
}

impl Collector {
    fn all(&self) -> Vec<ChatEvent> {
        self.0.lock().unwrap().iter().flatten().cloned().collect()
    }

    async fn wait_for(&self, pred: impl Fn(&[ChatEvent]) -> bool) {
        for _ in 0..200 {
            if pred(&self.all()) {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("événements attendus absents : {:?}", self.all());
    }
}

/// Faux serveur IRC : vérifie la poignée de main anonyme, envoie un salon et des messages groupés.
#[tokio::test]
async fn session_joins_anonymously_batches_and_reconnects() {
    let server = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("ws://{}", server.local_addr().unwrap());
    let handshakes = Arc::new(Mutex::new(Vec::<Vec<String>>::new()));

    let seen = handshakes.clone();
    tokio::spawn(async move {
        loop {
            let (tcp, _) = server.accept().await.unwrap();
            let mut ws = tokio_tungstenite::accept_async(tcp).await.unwrap();
            let mut lines = Vec::new();
            while lines.len() < 4 {
                if let Some(Ok(Message::Text(t))) = ws.next().await {
                    lines.push(t.to_string());
                }
            }
            let first = { let mut s = seen.lock().unwrap(); s.push(lines); s.len() == 1 };
            ws.send(Message::text(":tmi.twitch.tv ROOMSTATE #chan")).await.unwrap();
            if first {
                // Deux lignes dans une trame, puis une suppression : tout arrive dans un même lot.
                ws.send(Message::text(format!("{PRIVMSG}\r\nPING :tmi.twitch.tv"))).await.unwrap();
                ws.send(Message::text("@target-msg-id=m1 :tmi.twitch.tv CLEARMSG #chan :x")).await.unwrap();
                let pong = ws.next().await.unwrap().unwrap();
                assert_eq!(pong, Message::text("PONG :tmi.twitch.tv"));
                ws.send(Message::text(":tmi.twitch.tv RECONNECT")).await.unwrap();
            } else {
                // Garde la seconde connexion ouverte jusqu'à la fin du test.
                while ws.next().await.is_some() {}
            }
        }
    });

    let collector = Arc::new(Collector::default());
    let decor = Arc::new(std::sync::RwLock::new(super::Decor::default()));
    let session = spawn(url, Arc::new(crate::http::tls_config().unwrap()), "Chan".into(), collector.clone(), decor, async {});

    collector
        .wait_for(|events| events.iter().filter(|e| **e == ChatEvent::State { state: ChatState::Joined }).count() == 2)
        .await;
    drop(session);

    let lines = handshakes.lock().unwrap()[0].clone();
    assert_eq!(lines[0], "CAP REQ :twitch.tv/tags twitch.tv/commands");
    assert!(lines[2].starts_with("NICK justinfan"), "lecture anonyme : {lines:?}");
    assert_eq!(lines[3], "JOIN #chan", "nom du salon en minuscules");
    assert!(!lines.iter().any(|l| l.contains("oauth")), "aucun jeton sur la socket");

    let events = collector.all();
    let message_at = events.iter().position(|e| matches!(e, ChatEvent::Message { .. })).unwrap();
    assert_eq!(events[message_at + 1], ChatEvent::Delete { message_id: "m1".into() });
    assert!(events.contains(&ChatEvent::State { state: ChatState::Reconnecting }));
    let batches = collector.0.lock().unwrap();
    assert!(
        batches.iter().any(|b| b.iter().any(|e| matches!(e, ChatEvent::Message { .. })) && b.iter().any(|e| matches!(e, ChatEvent::Delete { .. }))),
        "message et suppression livrés dans le même lot : {batches:?}"
    );
}
