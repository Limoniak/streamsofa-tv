//! OAuth Twitch pour client public : Device Code Grant Flow, refresh, validation, révocation.
//! https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow

use serde::Deserialize;

use crate::error::CoreError;
use crate::secret::Secret;

/// Scopes demandés à la connexion. En ajouter un force tous les comptes à se reconnecter.
pub const SCOPES: &[&str] = &[
    "user:read:follows",       // lives et chaînes suivis
    "chat:read",               // lecture du chat (IRC) avec l'identité du compte
    "chat:edit",               // envoi via IRC (repli)
    "user:write:chat",         // envoi via Helix, qui explique les refus
    "user:read:emotes",        // emotes utilisables par le compte
    "user:read:blocked_users", // masquer les utilisateurs bloqués
];

const DEVICE_GRANT: &str = "urn:ietf:params:oauth:grant-type:device_code";

#[derive(Debug, Deserialize)]
pub struct DeviceCodeResponse {
    pub device_code: Secret,
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in: u64,
    pub interval: u64,
}

#[derive(Debug, Deserialize)]
pub struct TokenResponse {
    pub access_token: Secret,
    pub refresh_token: Secret,
    pub expires_in: u64,
}

#[derive(Debug, Deserialize)]
pub struct ValidateResponse {
    pub login: String,
    pub user_id: String,
    #[serde(default)]
    pub scopes: Vec<String>,
    pub expires_in: u64,
}

#[derive(Debug)]
pub enum PollOutcome {
    Granted(TokenResponse),
    Pending,
    SlowDown,
    /// Code expiré, déjà utilisé ou refusé : il faut en demander un nouveau.
    Dead,
}

#[derive(Debug)]
pub enum AuthError {
    /// Twitch rejette définitivement le jeton : seul l'utilisateur peut réparer.
    Rejected,
    /// Échec peut-être passager : on réessaiera plus tard sans toucher au compte.
    Transient(CoreError),
}

impl From<reqwest::Error> for AuthError {
    fn from(e: reqwest::Error) -> Self {
        AuthError::Transient(e.into())
    }
}

#[derive(Deserialize, Default)]
struct ErrorBody {
    #[serde(default)]
    message: String,
}

pub struct AuthClient {
    http: reqwest::Client,
    base: String,
    client_id: String,
}

impl AuthClient {
    pub fn new(http: reqwest::Client, base: String, client_id: String) -> Self {
        Self { http, base, client_id }
    }

    pub async fn request_device_code(&self) -> Result<DeviceCodeResponse, CoreError> {
        let scopes = SCOPES.join(" ");
        let response = self
            .http
            .post(format!("{}/oauth2/device", self.base))
            .form(&[("client_id", self.client_id.as_str()), ("scopes", scopes.as_str())])
            .send()
            .await?;
        if !response.status().is_success() {
            return Err(api_error(response).await);
        }
        Ok(response.json().await?)
    }

    pub async fn poll_device_token(&self, device_code: &Secret) -> Result<PollOutcome, CoreError> {
        let scopes = SCOPES.join(" ");
        let response = self
            .http
            .post(format!("{}/oauth2/token", self.base))
            .form(&[
                ("client_id", self.client_id.as_str()),
                ("scopes", scopes.as_str()),
                ("device_code", device_code.expose()),
                ("grant_type", DEVICE_GRANT),
            ])
            .send()
            .await?;
        let status = response.status();
        if status.is_success() {
            return Ok(PollOutcome::Granted(response.json().await?));
        }
        if status.is_server_error() {
            // Panne côté Twitch : le code reste peut-être valable, on continue d'attendre.
            return Ok(PollOutcome::Pending);
        }
        let body: ErrorBody = response.json().await.unwrap_or_default();
        Ok(match body.message.as_str() {
            "authorization_pending" => PollOutcome::Pending,
            "slow_down" => PollOutcome::SlowDown,
            _ => PollOutcome::Dead,
        })
    }

    /// Le jeton de rafraîchissement d'un client public est à usage unique : la réponse
    /// en contient un nouveau, à persister avant toute autre chose.
    pub async fn refresh(&self, refresh_token: &Secret) -> Result<TokenResponse, AuthError> {
        let response = self
            .http
            .post(format!("{}/oauth2/token", self.base))
            .form(&[
                ("client_id", self.client_id.as_str()),
                ("grant_type", "refresh_token"),
                ("refresh_token", refresh_token.expose()),
            ])
            .send()
            .await?;
        match response.status().as_u16() {
            200..=299 => Ok(response.json().await?),
            400 | 401 => Err(AuthError::Rejected),
            _ => Err(AuthError::Transient(api_error(response).await)),
        }
    }

    pub async fn validate(&self, access_token: &Secret) -> Result<ValidateResponse, AuthError> {
        let response = self
            .http
            .get(format!("{}/oauth2/validate", self.base))
            .header("Authorization", format!("OAuth {}", access_token.expose()))
            .send()
            .await?;
        match response.status().as_u16() {
            200..=299 => Ok(response.json().await?),
            401 => Err(AuthError::Rejected),
            _ => Err(AuthError::Transient(api_error(response).await)),
        }
    }

    pub async fn revoke(&self, token: &Secret) -> Result<(), CoreError> {
        self.http
            .post(format!("{}/oauth2/revoke", self.base))
            .form(&[("client_id", self.client_id.as_str()), ("token", token.expose())])
            .send()
            .await?;
        Ok(())
    }
}

pub async fn api_error(response: reqwest::Response) -> CoreError {
    let status = response.status().as_u16();
    let body: ErrorBody = response.json().await.unwrap_or_default();
    CoreError::Api { status, detail: body.message }
}
