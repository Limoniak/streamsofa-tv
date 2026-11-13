/// Erreurs remontées à Kotlin. Chaque variante correspond à un état d'UI distinct.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("stockage sécurisé indisponible : {reason}")]
    SecretStore { reason: String },
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
