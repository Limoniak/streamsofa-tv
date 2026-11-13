use std::sync::atomic::{AtomicBool, Ordering};

use serde_json::json;
use wiremock::matchers::{body_string_contains, header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::*;
use crate::api::{Core, CoreConfig, Endpoints};

#[derive(Default)]
pub(crate) struct MemoryStore {
    entries: Mutex<HashMap<String, Vec<u8>>>,
    fail_saves: AtomicBool,
}

impl SecretStore for MemoryStore {
    fn load(&self, key: String) -> Result<Option<Vec<u8>>, CoreError> {
        Ok(self.entries.lock().unwrap().get(&key).cloned())
    }
    fn save(&self, key: String, blob: Vec<u8>) -> Result<(), CoreError> {
        if self.fail_saves.load(Ordering::SeqCst) {
            return Err(CoreError::SecretStore { reason: "keystore verrouillÃ©".into() });
        }
        self.entries.lock().unwrap().insert(key, blob);
        Ok(())
    }
    fn delete(&self, key: String) -> Result<(), CoreError> {
        self.entries.lock().unwrap().remove(&key);
        Ok(())
    }
}

impl MemoryStore {
    fn tokens(&self, account_id: &str) -> Option<StoredTokens> {
        let blob = self.entries.lock().unwrap().get(&secret_key(account_id)).cloned()?;
        serde_json::from_slice(&blob).ok()
    }

    fn put(&self, account_id: &str, access: &str, refresh: &str, expires_in: i64) {
        let tokens = StoredTokens {
            access_token: Secret::new(access),
            refresh_token: Secret::new(refresh),
            expires_at: now() + expires_in,
        };
        self.entries.lock().unwrap().insert(secret_key(account_id), serde_json::to_vec(&tokens).unwrap());
    }
}

#[derive(Default)]
pub(crate) struct Recorder(Mutex<Vec<AccountsSnapshot>>);

impl AccountsListener for Recorder {
    fn on_accounts_changed(&self, snapshot: AccountsSnapshot) {
        self.0.lock().unwrap().push(snapshot);
    }
}

pub(crate) struct Harness {
    pub(crate) server: MockServer,
    pub(crate) store: Arc<MemoryStore>,
    pub(crate) events: Arc<Recorder>,
    pub(crate) core: Arc<Core>,
    _dir: tempfile::TempDir,
}

impl Harness {
    pub(crate) async fn new() -> Self {
        let server = MockServer::start().await;
        let store = Arc::new(MemoryStore::default());
        let dir = tempfile::tempdir().unwrap();
        let core = Core::with_endpoints(
            CoreConfig {
                client_id: "client123".into(),
                data_dir: dir.path().to_string_lossy().into_owned(),
                app_version: "test".into(),
            },
            store.clone(),
            Endpoints {
                id: server.uri(),
                helix: format!("{}/helix", server.uri()),
                gql: format!("{}/gql", server.uri()),
                usher: server.uri(),
                chat: server.uri().replace("http", "ws"),
                eventsub: server.uri().replace("http", "ws"),
                github: server.uri(),
                min_poll_interval: Duration::from_millis(10),
            },
        )
        .unwrap();
        let events = Arc::new(Recorder::default());
        core.set_accounts_listener(Some(events.clone()));
        Self { server, store, events, core, _dir: dir }
    }

    async fn mock_device_code(&self, expires_in: u64) {
        Mock::given(method("POST"))
            .and(path("/oauth2/device"))
            .and(body_string_contains("client_id=client123"))
            .and(body_string_contains("user%3Aread%3Afollows"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "device_code": "dev-secret", "user_code": "ABCD1234", "interval": 0,
                "expires_in": expires_in,
                "verification_uri": "https://www.twitch.tv/activate?device-code=ABCD1234",
            })))
            .mount(&self.server)
            .await;
    }

    async fn mock_identity(&self, token: &str, user_id: &str, login: &str) {
        Mock::given(method("GET"))
            .and(path("/oauth2/validate"))
            .and(header("Authorization", format!("OAuth {token}")))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "client_id": "client123", "login": login, "user_id": user_id,
                "scopes": ["user:read:follows"], "expires_in": 14000,
            })))
            .mount(&self.server)
            .await;
        Mock::given(method("GET"))
            .and(path("/helix/users"))
            .and(header("Authorization", format!("Bearer {token}")))
            .and(header("Client-Id", "client123"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "data": [{
                "id": user_id, "login": login, "display_name": login.to_uppercase(),
                "profile_image_url": format!("https://cdn.example/{login}.png"),
            }]})))
            .mount(&self.server)
            .await;
    }

    /// Connecte un compte de bout en bout via le flux par code.
    pub(crate) async fn sign_in(&self, user_id: &str, login: &str) -> Account {
        let access = format!("access-{login}");
        self.server.reset().await;
        self.mock_device_code(1800).await;
        Mock::given(method("POST"))
            .and(path("/oauth2/token"))
            .respond_with(ResponseTemplate::new(200).set_body_json(token_body(&access, &format!("refresh-{login}"))))
            .mount(&self.server)
            .await;
        self.mock_identity(&access, user_id, login).await;
        let login = self.core.start_device_login().await.unwrap();
        let account = self.core.complete_device_login(login.user_code).await.unwrap();
        self.server.reset().await;
        account
    }

    async fn mock_refresh(&self, old_refresh: &str, response: ResponseTemplate) {
        Mock::given(method("POST"))
            .and(path("/oauth2/token"))
            .and(body_string_contains("grant_type=refresh_token"))
            .and(body_string_contains(format!("refresh_token={old_refresh}")))
            .respond_with(response)
            .expect(1)
            .mount(&self.server)
            .await;
    }

    fn state_of(&self, account_id: &str) -> AccountState {
        let snapshot = self.core.accounts().unwrap();
        snapshot.accounts.into_iter().find(|a| a.id == account_id).unwrap().state
    }
}

fn token_body(access: &str, refresh: &str) -> serde_json::Value {
    json!({ "access_token": access, "refresh_token": refresh, "expires_in": 14400,
            "scope": ["user:read:follows"], "token_type": "bearer" })
}

fn pending() -> ResponseTemplate {
    ResponseTemplate::new(400).set_body_json(json!({ "status": 400, "message": "authorization_pending" }))
}

#[tokio::test]
async fn device_login_waits_for_approval_then_stores_the_account() {
    let h = Harness::new().await;
    h.mock_device_code(1800).await;
    // Deux rÃ©ponses Â« en attente Â», puis l'utilisateur valide.
    Mock::given(method("POST"))
        .and(path("/oauth2/token"))
        .and(body_string_contains("device_code=dev-secret"))
        .and(body_string_contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        .respond_with(pending())
        .up_to_n_times(2)
        .expect(2)
        .mount(&h.server)
        .await;
    Mock::given(method("POST"))
        .and(path("/oauth2/token"))
        .respond_with(ResponseTemplate::new(200).set_body_json(token_body("access-1", "refresh-1")))
        .mount(&h.server)
        .await;
    h.mock_identity("access-1", "42", "alice").await;

    let login = h.core.start_device_login().await.unwrap();
    assert_eq!(login.user_code, "ABCD1234");
    assert_eq!(login.qr.modules.len() as u32, login.qr.size * login.qr.size);

    let account = h.core.complete_device_login(login.user_code).await.unwrap();
    assert_eq!(
        account,
        Account {
            id: "42".into(),
            login: "alice".into(),
            display_name: "ALICE".into(),
            avatar_url: "https://cdn.example/alice.png".into(),
            state: AccountState::Ok,
        }
    );
    let snapshot = h.core.accounts().unwrap();
    assert_eq!(snapshot.active_id.as_deref(), Some("42"));
    assert_eq!(h.events.0.lock().unwrap().last(), Some(&snapshot));
    assert_eq!(h.store.tokens("42").unwrap().refresh_token.expose(), "refresh-1");
}

#[tokio::test]
async fn device_login_reports_expiry_when_twitch_kills_the_code() {
    let h = Harness::new().await;
    h.mock_device_code(1800).await;
    Mock::given(method("POST"))
        .and(path("/oauth2/token"))
        .respond_with(ResponseTemplate::new(400).set_body_json(json!({ "status": 400, "message": "invalid device code" })))
        .mount(&h.server)
        .await;
    let login = h.core.start_device_login().await.unwrap();
    let err = h.core.complete_device_login(login.user_code).await.unwrap_err();
    assert!(matches!(err, CoreError::DeviceCodeExpired));
    assert!(h.core.accounts().unwrap().accounts.is_empty());
}

#[tokio::test]
async fn device_login_gives_up_at_the_deadline() {
    let h = Harness::new().await;
    h.mock_device_code(0).await;
    Mock::given(method("POST")).and(path("/oauth2/token")).respond_with(pending()).expect(0).mount(&h.server).await;
    let login = h.core.start_device_login().await.unwrap();
    let err = h.core.complete_device_login(login.user_code).await.unwrap_err();
    assert!(matches!(err, CoreError::DeviceCodeExpired));
}

#[tokio::test]
async fn a_stale_user_code_cannot_complete_a_newer_login() {
    let h = Harness::new().await;
    h.mock_device_code(1800).await;
    h.core.start_device_login().await.unwrap();
    let err = h.core.complete_device_login("OLDCODE".into()).await.unwrap_err();
    assert!(matches!(err, CoreError::DeviceCodeExpired));
}

#[tokio::test]
async fn login_fails_cleanly_when_the_keystore_refuses_the_token() {
    let h = Harness::new().await;
    h.mock_device_code(1800).await;
    Mock::given(method("POST"))
        .and(path("/oauth2/token"))
        .respond_with(ResponseTemplate::new(200).set_body_json(token_body("access-1", "refresh-1")))
        .mount(&h.server)
        .await;
    h.mock_identity("access-1", "42", "alice").await;
    h.store.fail_saves.store(true, Ordering::SeqCst);

    let login = h.core.start_device_login().await.unwrap();
    let err = h.core.complete_device_login(login.user_code).await.unwrap_err();
    assert!(matches!(err, CoreError::SecretStore { .. }));
    assert!(h.core.accounts().unwrap().accounts.is_empty(), "pas de compte fantÃ´me sans jeton");
}

#[tokio::test]
async fn missing_client_id_is_reported_before_any_request() {
    let dir = tempfile::tempdir().unwrap();
    let core = Core::new(
        CoreConfig { client_id: " ".into(), data_dir: dir.path().to_string_lossy().into_owned(), app_version: "t".into() },
        Arc::new(MemoryStore::default()),
    )
    .unwrap();
    assert!(matches!(core.start_device_login().await.unwrap_err(), CoreError::NotConfigured));
}

#[tokio::test]
async fn a_fresh_token_is_returned_without_touching_the_network() {
    let h = Harness::new().await;
    h.sign_in("42", "alice").await;
    let token = h.core.accounts.access_token("42").await.unwrap();
    assert_eq!(token.expose(), "access-alice");
    assert!(h.server.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn an_expiring_token_is_refreshed_and_the_rotated_refresh_token_persisted() {
    let h = Harness::new().await;
    h.sign_in("42", "alice").await;
    h.store.put("42", "old-access", "old-refresh", 60);
    h.core.accounts.cache.lock().unwrap().clear();
    h.mock_refresh("old-refresh", ResponseTemplate::new(200).set_body_json(token_body("new-access", "new-refresh"))).await;

    // Deux demandes simultanÃ©es ne doivent produire qu'un seul refresh (`expect(1)`).
    let (a, b) = tokio::join!(h.core.accounts.access_token("42"), h.core.accounts.access_token("42"));
    assert_eq!(a.unwrap().expose(), "new-access");
    assert_eq!(b.unwrap().expose(), "new-access");
    let stored = h.store.tokens("42").unwrap();
    assert_eq!(stored.refresh_token.expose(), "new-refresh");
    assert!(stored.expires_at - now() > 14000);
}

#[tokio::test]
async fn a_rejected_refresh_marks_the_account_without_removing_it() {
    let h = Harness::new().await;
    h.sign_in("42", "alice").await;
    h.store.put("42", "old-access", "revoked-refresh", 60);
    h.core.accounts.cache.lock().unwrap().clear();
    h.mock_refresh(
        "revoked-refresh",
        ResponseTemplate::new(400).set_body_json(json!({ "status": 400, "message": "Invalid refresh token" })),
    )
    .await;

    let err = h.core.accounts.access_token("42").await.unwrap_err();
    assert!(matches!(err, CoreError::ReauthRequired { account_id } if account_id == "42"));
    assert_eq!(h.state_of("42"), AccountState::NeedsReauth);
    assert!(h.store.tokens("42").is_none());
    assert_eq!(h.events.0.lock().unwrap().last().unwrap().accounts[0].state, AccountState::NeedsReauth);
    // L'appel suivant Ã©choue de la mÃªme faÃ§on, sans nouvel aller-retour rÃ©seau.
    assert!(matches!(h.core.accounts.access_token("42").await, Err(CoreError::ReauthRequired { .. })));
}

#[tokio::test]
async fn a_server_outage_during_refresh_keeps_the_account_and_the_still_valid_token() {
    let h = Harness::new().await;
    h.sign_in("42", "alice").await;
    h.store.put("42", "old-access", "old-refresh", 60);
    h.core.accounts.cache.lock().unwrap().clear();
    h.mock_refresh("old-refresh", ResponseTemplate::new(503)).await;

    assert_eq!(h.core.accounts.access_token("42").await.unwrap().expose(), "old-access");
    assert_eq!(h.state_of("42"), AccountState::Ok);
    assert_eq!(h.store.tokens("42").unwrap().refresh_token.expose(), "old-refresh");
}

#[tokio::test]
async fn startup_validation_refreshes_a_token_twitch_no_longer_accepts() {
    let h = Harness::new().await;
    h.sign_in("42", "alice").await;
    Mock::given(method("GET")).and(path("/oauth2/validate")).respond_with(ResponseTemplate::new(401)).mount(&h.server).await;
    h.mock_refresh("refresh-alice", ResponseTemplate::new(200).set_body_json(token_body("new-access", "new-refresh"))).await;

    h.core.validate_accounts().await.unwrap();
    assert_eq!(h.state_of("42"), AccountState::Ok);
    assert_eq!(h.store.tokens("42").unwrap().access_token.expose(), "new-access");
}

#[tokio::test]
async fn startup_validation_flags_a_revoked_account() {
    let h = Harness::new().await;
    h.sign_in("42", "alice").await;
    Mock::given(method("GET")).and(path("/oauth2/validate")).respond_with(ResponseTemplate::new(401)).mount(&h.server).await;
    h.mock_refresh("refresh-alice", ResponseTemplate::new(400)).await;

    h.core.validate_accounts().await.unwrap();
    assert_eq!(h.state_of("42"), AccountState::NeedsReauth);
    // Toujours actif : l'UI doit proposer la reconnexion, pas faire disparaÃ®tre le compte.
    assert_eq!(h.core.accounts().unwrap().active_id.as_deref(), Some("42"));
}

#[tokio::test]
async fn startup_validation_offline_changes_nothing() {
    let h = Harness::new().await;
    h.sign_in("42", "alice").await;
    Mock::given(method("GET")).and(path("/oauth2/validate")).respond_with(ResponseTemplate::new(503)).mount(&h.server).await;
    h.core.validate_accounts().await.unwrap();
    assert_eq!(h.state_of("42"), AccountState::Ok);
    assert_eq!(h.store.tokens("42").unwrap().refresh_token.expose(), "refresh-alice");
}

#[tokio::test]
async fn reconnecting_restores_a_flagged_account() {
    let h = Harness::new().await;
    h.sign_in("42", "alice").await;
    h.core.accounts.require_reauth("42").await;
    assert_eq!(h.state_of("42"), AccountState::NeedsReauth);
    h.sign_in("42", "alice").await;
    assert_eq!(h.state_of("42"), AccountState::Ok);
    assert_eq!(h.core.accounts().unwrap().accounts.len(), 1);
}

#[tokio::test]
async fn several_accounts_switch_and_remove_independently() {
    let h = Harness::new().await;
    h.sign_in("1", "alice").await;
    h.sign_in("2", "bob").await;
    assert_eq!(h.core.accounts().unwrap().active_id.as_deref(), Some("2"), "le dernier connectÃ© devient actif");

    h.core.set_active_account(Some("1".into())).unwrap();
    assert_eq!(h.core.accounts.access_token("1").await.unwrap().expose(), "access-alice");
    assert_eq!(h.core.accounts.access_token("2").await.unwrap().expose(), "access-bob");

    Mock::given(method("POST"))
        .and(path("/oauth2/revoke"))
        .and(body_string_contains("token=access-alice"))
        .respond_with(ResponseTemplate::new(200))
        .expect(1)
        .mount(&h.server)
        .await;
    h.core.remove_account("1".into()).await.unwrap();

    let snapshot = h.core.accounts().unwrap();
    assert_eq!(snapshot.accounts.iter().map(|a| a.login.as_str()).collect::<Vec<_>>(), ["bob"]);
    assert_eq!(snapshot.active_id, None, "retirer le compte actif bascule en invitÃ©");
    assert!(h.store.tokens("1").is_none());
    assert!(h.store.tokens("2").is_some());
    assert!(matches!(h.core.set_active_account(Some("1".into())), Err(CoreError::UnknownAccount { .. })));
}

#[tokio::test]
async fn removing_an_account_works_offline() {
    let h = Harness::new().await;
    h.sign_in("42", "alice").await;
    Mock::given(method("POST")).and(path("/oauth2/revoke")).respond_with(ResponseTemplate::new(503)).mount(&h.server).await;
    h.core.remove_account("42".into()).await.unwrap();
    assert!(h.core.accounts().unwrap().accounts.is_empty());
}

#[tokio::test]
async fn guest_mode_keeps_accounts_available() {
    let h = Harness::new().await;
    h.sign_in("42", "alice").await;
    h.core.set_active_account(None).unwrap();
    let snapshot = h.core.accounts().unwrap();
    assert_eq!(snapshot.active_id, None);
    assert_eq!(snapshot.accounts.len(), 1);
}

#[test]
fn stored_tokens_never_leak_through_debug() {
    let tokens = StoredTokens { access_token: Secret::new("AAAA"), refresh_token: Secret::new("RRRR"), expires_at: 0 };
    let printed = format!("{tokens:?}");
    assert!(!printed.contains("AAAA") && !printed.contains("RRRR"));
}
