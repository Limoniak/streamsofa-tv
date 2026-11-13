/// Erreurs remontées à Kotlin. Chaque variante correspond à un état d'UI distinct.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// Aucun Client ID Twitch n'a été fourni à la compilation.
    #[error("Client ID Twitch non configuré")]
    NotConfigured,
    /// Pas de réponse exploitable : hors ligne, DNS, délai dépassé…
    #[error("réseau indisponible : {reason}")]
    Network { reason: String },
    /// Twitch a répondu par une erreur que l'on ne sait pas traiter autrement.
    #[error("erreur Twitch {status} : {detail}")]
    // `detail` et non `message` : ce nom est réservé par Throwable côté Kotlin.
    Api { status: u16, detail: String },
    /// Le compte doit être reconnecté (jeton révoqué, expiré ou perdu).
    #[error("reconnexion requise pour le compte {account_id}")]
    ReauthRequired { account_id: String },
    /// Le code affiché à l'écran n'a pas été validé à temps.
    #[error("le code de connexion a expiré")]
    DeviceCodeExpired,
    #[error("compte inconnu : {account_id}")]
    UnknownAccount { account_id: String },
    /// Fonction réservée aux comptes connectés (lives suivis) alors qu'on est en invité.
    #[error("aucun compte actif")]
    NotSignedIn,
    /// La chaîne n'est pas en live (ou n'existe pas).
    #[error("chaîne hors ligne")]
    Offline,
    /// Twitch refuse la lecture : restriction géographique, réservée aux abonnés…
    #[error("contenu indisponible : {detail}")]
    Unavailable { detail: String },
    /// L'API interne GQL a répondu dans un format inattendu : Twitch l'a probablement modifiée.
    #[error("l'API interne de Twitch a changé ({operation}) : {detail}")]
    GqlChanged { operation: String, detail: String },
    #[error("stockage sécurisé indisponible : {reason}")]
    SecretStore { reason: String },
    #[error("base locale indisponible : {reason}")]
    Storage { reason: String },
    #[error("erreur interne : {reason}")]
    Internal { reason: String },
}

// Permet à une implémentation Kotlin de `SecretStore` de lever n'importe quelle exception
// sans faire paniquer Rust : elle est convertie en `CoreError::SecretStore`.
impl From<uniffi::UnexpectedUniFFICallbackError> for CoreError {
    fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
        CoreError::SecretStore { reason: e.reason }
    }
}

impl From<rusqlite::Error> for CoreError {
    fn from(e: rusqlite::Error) -> Self {
        CoreError::Storage { reason: e.to_string() }
    }
}

impl From<reqwest::Error> for CoreError {
    fn from(e: reqwest::Error) -> Self {
        // `without_url` : une URL peut porter un paramètre sensible, on ne la recopie jamais.
        CoreError::Network { reason: e.without_url().to_string() }
    }
}

impl From<tokio::task::JoinError> for CoreError {
    fn from(e: tokio::task::JoinError) -> Self {
        CoreError::Internal { reason: e.to_string() }
    }
}
