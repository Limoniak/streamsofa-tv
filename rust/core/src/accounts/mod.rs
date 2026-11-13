//! Comptes Twitch : connexion par code d'appareil, jetons, compte actif.
//!
//! Les jetons ne vivent qu'en mÃ©moire et, chiffrÃ©s par l'hÃ´te, dans le `SecretStore`.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::api::SecretStore;
use crate::auth::{AuthClient, AuthError, DeviceCodeResponse, PollOutcome, TokenResponse};
use crate::error::CoreError;
use crate::helix::HelixClient;
use crate::qr::{self, QrMatrix};
use crate::secret::Secret;
use crate::store::Db;

/// Un jeton d'accÃ¨s qui expire dans moins de 5 minutes est renouvelÃ© avant usage.
const REFRESH_MARGIN_S: i64 = 300;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AccountState {
    Ok,
    /// Jeton rÃ©voquÃ©, expirÃ© ou perdu : le compte reste listÃ© mais doit se reconnecter.
    NeedsReauth,
}

impl AccountState {
    pub fn as_db(self) -> &'static str {
        match self {
            AccountState::Ok => "ok",
            AccountState::NeedsReauth => "needs_reauth",
        }
    }

    pub fn from_db(value: &str) -> Self {
        match value {
            "ok" => AccountState::Ok,
            _ => AccountState::NeedsReauth,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Account {
    /// Identifiant utilisateur Twitch (stable, contrairement au login).
    pub id: String,
    pub login: String,
    pub display_name: String,
    pub avatar_url: String,
    pub state: AccountState,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AccountsSnapshot {
    pub accounts: Vec<Account>,
    /// `None` = mode invitÃ©.
    pub active_id: Option<String>,
}

/// ImplÃ©mentÃ© cÃ´tÃ© Kotlin pour suivre les changements (ajout, retrait, expiration, bascule).
#[uniffi::export(with_foreign)]
pub trait AccountsListener: Send + Sync {
    fn on_accounts_changed(&self, snapshot: AccountsSnapshot);
}

/// Ce qu'il faut afficher Ã  l'Ã©cran pendant une connexion par code.
#[derive(Debug, Clone, uniffi::Record)]
pub struct DeviceLogin {
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in_s: u32,
    pub qr: QrMatrix,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct StoredTokens {
    access_token: Secret,
    refresh_token: Secret,
    /// Secondes Unix.
    expires_at: i64,
}

impl StoredTokens {
    fn from_response(response: TokenResponse) -> Self {
        Self {
            access_token: response.access_token,
            refresh_token: response.refresh_token,
            expires_at: now() + response.expires_in as i64,
        }
    }
}

pub struct AccountManager {
    db: Db,
    secrets: Arc<dyn SecretStore>,
    auth: AuthClient,
    helix: HelixClient,
    cache: Mutex<HashMap<String, StoredTokens>>,
    /// Un verrou par compte : deux refresh simultanÃ©s grilleraient le jeton Ã  usage unique.
    locks: Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>,
    /// Connexion en cours. Le code d'appareil ne franchit jamais la FFI.
    pending: Mutex<Option<DeviceCodeResponse>>,
    listener: Mutex<Option<Arc<dyn AccountsListener>>>,
    min_poll_interval: Duration,
}

impl AccountManager {
    pub fn new(
        db: Db,
        secrets: Arc<dyn SecretStore>,
        auth: AuthClient,
        helix: HelixClient,
        min_poll_interval: Duration,
    ) -> Self {
        Self {
            db,
            secrets,
            auth,
            helix,
            cache: Mutex::default(),
            locks: Mutex::default(),
            pending: Mutex::default(),
            listener: Mutex::default(),
            min_poll_interval,
        }
    }

    pub fn set_listener(&self, listener: Option<Arc<dyn AccountsListener>>) {
        *lock(&self.listener) = listener;
    }

    pub fn snapshot(&self) -> Result<AccountsSnapshot, CoreError> {
        Ok(AccountsSnapshot {
            accounts: self.db.accounts()?,
            active_id: self.db.active_account_id()?,
        })
    }

    pub fn set_active(&self, id: Option<String>) -> Result<(), CoreError> {
        if let Some(id) = &id {
            self.ensure_exists(id)?;
        }
        self.db.set_active_account(id.as_deref(), now())?;
        self.notify();
        Ok(())
    }

    // --- Connexion par code d'appareil ---------------------------------------------------

    pub async fn start_device_login(&self) -> Result<DeviceLogin, CoreError> {
        let response = self.auth.request_device_code().await?;
        let login = DeviceLogin {
            user_code: response.user_code.clone(),
            verification_uri: response.verification_uri.clone(),
            expires_in_s: response.expires_in.min(u32::MAX as u64) as u32,
            qr: qr::encode(&response.verification_uri)?,
        };
        *lock(&self.pending) = Some(response);
        Ok(login)
    }

    /// Attend que l'utilisateur valide le code. L'appelant annule en abandonnant le futur.
    pub async fn complete_device_login(&self, user_code: &str) -> Result<Account, CoreError> {
        let pending = {
            let mut slot = lock(&self.pending);
            match slot.as_ref() {
                Some(p) if p.user_code == user_code => slot.take(),
                _ => None,
            }
        }
        .ok_or(CoreError::DeviceCodeExpired)?;

        let deadline = Instant::now() + Duration::from_secs(pending.expires_in);
        let mut interval = Duration::from_secs(pending.interval).max(self.min_poll_interval);
        let mut last_error = None;
        let tokens = loop {
            tokio::time::sleep(interval).await;
            if Instant::now() >= deadline {
                // Si le rÃ©seau Ã©tait coupÃ©, c'est Ã§a qu'il faut dire, pas Â« code expirÃ© Â».
                return Err(last_error.unwrap_or(CoreError::DeviceCodeExpired));
            }
            match self.auth.poll_device_token(&pending.device_code).await {
                Ok(PollOutcome::Granted(tokens)) => break tokens,
                Ok(PollOutcome::Pending) => last_error = None,
                Ok(PollOutcome::SlowDown) => interval += Duration::from_secs(5),
                Ok(PollOutcome::Dead) => return Err(CoreError::DeviceCodeExpired),
                Err(e) => last_error = Some(e),
            }
        };
        self.register(StoredTokens::from_response(tokens)).await
    }

    async fn register(&self, tokens: StoredTokens) -> Result<Account, CoreError> {
        let identity = match self.auth.validate(&tokens.access_token).await {
            Ok(identity) => identity,
            Err(AuthError::Rejected) => {
                return Err(CoreError::Api { status: 401, detail: "jeton refusÃ© dÃ¨s son Ã©mission".into() })
            }
            Err(AuthError::Transient(e)) => return Err(e),
        };
        // Le profil n'est que cosmÃ©tique : son Ã©chec ne doit pas faire Ã©chouer la connexion.
        let (display_name, avatar_url) = match self.helix.current_user(&tokens.access_token).await {
            Ok(user) => (user.display_name, user.profile_image_url),
            Err(_) => (identity.login.clone(), String::new()),
        };
        let account = Account {
            id: identity.user_id,
            login: identity.login,
            display_name,
            avatar_url,
            state: AccountState::Ok,
        };

        let guard = self.lock_for(&account.id);
        let _guard = guard.lock().await;
        // Le secret d'abord : un compte listÃ© sans jeton serait inutilisable.
        self.save_tokens(&account.id, &tokens).await?;
        self.db.upsert_account(&account, &identity.scopes.join(" "), now())?;
        self.db.set_active_account(Some(&account.id), now())?;
        self.notify();
        Ok(account)
    }

    // --- Jetons --------------------------------------------------------------------------

    /// Compte actif s'il est utilisable ; `None` en invitÃ© ou s'il doit se reconnecter.
    pub fn active_usable_account(&self) -> Result<Option<Account>, CoreError> {
        let Some(id) = self.db.active_account_id()? else { return Ok(None) };
        Ok(self.db.accounts()?.into_iter().find(|a| a.id == id && a.state == AccountState::Ok))
    }

    /// Twitch a refusÃ© `rejected` (401) avant son expiration prÃ©vue : renouvelle-le, sauf si
    /// un autre appel l'a dÃ©jÃ  fait entre-temps.
    pub async fn token_rejected(&self, account_id: &str, rejected: &Secret) -> Result<Secret, CoreError> {
        self.ensure_exists(account_id)?;
        let guard = self.lock_for(account_id);
        let _guard = guard.lock().await;
        let Some(tokens) = self.load_tokens(account_id).await? else {
            return Err(self.require_reauth(account_id).await);
        };
        if &tokens.access_token != rejected {
            return Ok(tokens.access_token);
        }
        Ok(self.refresh_locked(account_id, &tokens).await?.access_token)
    }

    /// Jeton d'accÃ¨s valide pour ce compte, renouvelÃ© au besoin.
    pub async fn access_token(&self, account_id: &str) -> Result<Secret, CoreError> {
        self.ensure_exists(account_id)?;
        let guard = self.lock_for(account_id);
        let _guard = guard.lock().await;

        let Some(tokens) = self.load_tokens(account_id).await? else {
            return Err(self.require_reauth(account_id).await);
        };
        if tokens.expires_at - now() > REFRESH_MARGIN_S {
            return Ok(tokens.access_token);
        }
        match self.refresh_locked(account_id, &tokens).await {
            Ok(fresh) => Ok(fresh.access_token),
            Err(e @ CoreError::ReauthRequired { .. }) => Err(e),
            // Refresh impossible pour l'instant, mais l'ancien jeton vit encore quelques minutes.
            Err(_) if tokens.expires_at > now() => Ok(tokens.access_token),
            Err(e) => Err(e),
        }
    }

    /// VÃ©rifie chaque compte auprÃ¨s de Twitch (au dÃ©marrage, puis toutes les heures).
    /// Une panne rÃ©seau ne change l'Ã©tat d'aucun compte.
    pub async fn validate_all(&self) -> Result<(), CoreError> {
        for account in self.db.accounts()? {
            if account.state == AccountState::NeedsReauth {
                continue;
            }
            let guard = self.lock_for(&account.id);
            let _guard = guard.lock().await;
            let Some(mut tokens) = self.load_tokens(&account.id).await? else {
                self.require_reauth(&account.id).await;
                continue;
            };
            match self.auth.validate(&tokens.access_token).await {
                Ok(info) if info.expires_in as i64 > REFRESH_MARGIN_S => {
                    tokens.expires_at = now() + info.expires_in as i64;
                    lock(&self.cache).insert(account.id.clone(), tokens);
                }
                Ok(_) | Err(AuthError::Rejected) => {
                    // Les erreurs passagÃ¨res sont ignorÃ©es ; un refus marque dÃ©jÃ  le compte.
                    let _ = self.refresh_locked(&account.id, &tokens).await;
                }
                Err(AuthError::Transient(_)) => {}
            }
        }
        Ok(())
    }

    /// Ã€ appeler en tenant le verrou du compte.
    async fn refresh_locked(&self, account_id: &str, tokens: &StoredTokens) -> Result<StoredTokens, CoreError> {
        match self.auth.refresh(&tokens.refresh_token).await {
            Ok(response) => {
                let fresh = StoredTokens::from_response(response);
                // L'ancien jeton de rafraÃ®chissement est dÃ©jÃ  consommÃ©. Si l'Ã©criture Ã©choue,
                // la session continue en mÃ©moire et le compte se reconnectera au prochain
                // dÃ©marrage : c'est moins grave que d'Ã©chouer maintenant.
                let _ = self.save_tokens(account_id, &fresh).await;
                lock(&self.cache).insert(account_id.to_owned(), fresh.clone());
                Ok(fresh)
            }
            Err(AuthError::Rejected) => Err(self.require_reauth(account_id).await),
            Err(AuthError::Transient(e)) => Err(e),
        }
    }

    async fn require_reauth(&self, account_id: &str) -> CoreError {
        lock(&self.cache).remove(account_id);
        let _ = self.delete_tokens(account_id).await;
        if let Ok(true) = self.db.set_account_state(account_id, AccountState::NeedsReauth) {
            self.notify();
        }
        CoreError::ReauthRequired { account_id: account_id.to_owned() }
    }

    // --- Suppression ---------------------------------------------------------------------

    pub async fn remove(&self, account_id: &str) -> Result<(), CoreError> {
        self.ensure_exists(account_id)?;
        let guard = self.lock_for(account_id);
        let _guard = guard.lock().await;

        let tokens = self.load_tokens(account_id).await.ok().flatten();
        lock(&self.cache).remove(account_id);
        self.delete_tokens(account_id).await?;
        self.db.delete_account(account_id)?;
        lock(&self.locks).remove(account_id);
        self.notify();

        // RÃ©vocation au mieux : hors ligne, le compte est quand mÃªme retirÃ© de l'appareil.
        if let Some(tokens) = tokens {
            let _ = self.auth.revoke(&tokens.access_token).await;
        }
        Ok(())
    }

    // --- Outils --------------------------------------------------------------------------

    fn ensure_exists(&self, account_id: &str) -> Result<(), CoreError> {
        if self.db.account_exists(account_id)? {
            Ok(())
        } else {
            Err(CoreError::UnknownAccount { account_id: account_id.to_owned() })
        }
    }

    fn lock_for(&self, account_id: &str) -> Arc<tokio::sync::Mutex<()>> {
        lock(&self.locks).entry(account_id.to_owned()).or_default().clone()
    }

    fn notify(&self) {
        let listener = lock(&self.listener).clone();
        if let (Some(listener), Ok(snapshot)) = (listener, self.snapshot()) {
            listener.on_accounts_changed(snapshot);
        }
    }

    async fn load_tokens(&self, account_id: &str) -> Result<Option<StoredTokens>, CoreError> {
        if let Some(tokens) = lock(&self.cache).get(account_id) {
            return Ok(Some(tokens.clone()));
        }
        let (secrets, key) = (self.secrets.clone(), secret_key(account_id));
        // Le Keystore peut bloquer : jamais sur un worker async.
        let blob = tokio::task::spawn_blocking(move || secrets.load(key)).await??;
        // Un blob illisible vaut une absence : le compte devra se reconnecter.
        let tokens = blob.and_then(|blob| serde_json::from_slice::<StoredTokens>(&blob).ok());
        if let Some(tokens) = &tokens {
            lock(&self.cache).insert(account_id.to_owned(), tokens.clone());
        }
        Ok(tokens)
    }

    async fn save_tokens(&self, account_id: &str, tokens: &StoredTokens) -> Result<(), CoreError> {
        let blob = serde_json::to_vec(tokens).map_err(|e| CoreError::Internal { reason: e.to_string() })?;
        let (secrets, key) = (self.secrets.clone(), secret_key(account_id));
        tokio::task::spawn_blocking(move || secrets.save(key, blob)).await??;
        lock(&self.cache).insert(account_id.to_owned(), tokens.clone());
        Ok(())
    }

    async fn delete_tokens(&self, account_id: &str) -> Result<(), CoreError> {
        let (secrets, key) = (self.secrets.clone(), secret_key(account_id));
        tokio::task::spawn_blocking(move || secrets.delete(key)).await?
    }
}

fn secret_key(account_id: &str) -> String {
    format!("account_{account_id}")
}

fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(test)]
pub(crate) mod tests;
