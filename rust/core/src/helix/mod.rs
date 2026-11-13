//! API officielle Twitch (Helix). Chaque appel porte le Client ID de l'app et un jeton utilisateur.

use serde::Deserialize;

use crate::auth::api_error;
use crate::error::CoreError;
use crate::secret::Secret;

#[derive(Debug, Deserialize)]
pub struct HelixUser {
    pub display_name: String,
    #[serde(default)]
    pub profile_image_url: String,
}

#[derive(Deserialize)]
struct Page<T> {
    data: Vec<T>,
}

pub struct HelixClient {
    http: reqwest::Client,
    base: String,
    client_id: String,
}

impl HelixClient {
    pub fn new(http: reqwest::Client, base: String, client_id: String) -> Self {
        Self { http, base, client_id }
    }

    /// Profil du propriétaire du jeton.
    pub async fn current_user(&self, token: &Secret) -> Result<HelixUser, CoreError> {
        let response = self
            .http
            .get(format!("{}/users", self.base))
            .header("Client-Id", &self.client_id)
            .bearer_auth(token.expose())
            .send()
            .await?;
        if !response.status().is_success() {
            return Err(api_error(response).await);
        }
        let page: Page<HelixUser> = response.json().await?;
        page.data
            .into_iter()
            .next()
            .ok_or(CoreError::Api { status: 200, detail: "profil absent de la réponse".into() })
    }
}
