use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::error::CoreError;

/// Configuration fournie par l'application au démarrage.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CoreConfig {
    /// Client ID Twitch de l'app (type « Public »). Vide tant qu'il n'est pas configuré.
    pub client_id: String,
    /// Répertoire privé de l'app : base SQLite et caches.
    pub data_dir: String,
    pub app_version: String,
}

/// Stockage des secrets, implémenté côté Kotlin (Android Keystore).
/// Rust ne voit que des blobs opaques et ne les écrit jamais sur disque.
#[uniffi::export(with_foreign)]
pub trait SecretStore: Send + Sync {
    fn load(&self, key: String) -> Result<Option<Vec<u8>>, CoreError>;
    fn save(&self, key: String, blob: Vec<u8>) -> Result<(), CoreError>;
    fn delete(&self, key: String) -> Result<(), CoreError>;
}

/// Résultat de l'autotest du J1 : prouve que les trois canaux FFI fonctionnent.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SelfTest {
    pub core_version: String,
    /// Le runtime tokio a bien exécuté une tâche asynchrone.
    pub async_ok: bool,
    /// Aller-retour écriture / lecture / suppression via le `SecretStore` Kotlin.
    pub secret_store_ok: bool,
    pub elapsed_ms: u64,
}

#[derive(uniffi::Object)]
pub struct Core {
    config: CoreConfig,
    secrets: Arc<dyn SecretStore>,
}

#[uniffi::export(async_runtime = "tokio")]
impl Core {
    #[uniffi::constructor]
    pub fn new(config: CoreConfig, secrets: Arc<dyn SecretStore>) -> Arc<Self> {
        Arc::new(Self { config, secrets })
    }

    pub fn version(&self) -> String {
        core_version()
    }

    pub fn has_client_id(&self) -> bool {
        !self.config.client_id.trim().is_empty()
    }

    pub async fn self_test(&self) -> Result<SelfTest, CoreError> {
        let start = Instant::now();

        let async_ok = tokio::spawn(async {
            tokio::time::sleep(Duration::from_millis(10)).await;
            true
        })
        .await
        .map_err(|e| CoreError::Internal { reason: e.to_string() })?;

        let secrets = self.secrets.clone();
        // Le Keystore peut bloquer : jamais sur un worker async.
        let secret_store_ok = tokio::task::spawn_blocking(move || secret_roundtrip(&*secrets))
            .await
            .map_err(|e| CoreError::Internal { reason: e.to_string() })??;

        Ok(SelfTest {
            core_version: core_version(),
            async_ok,
            secret_store_ok,
            elapsed_ms: start.elapsed().as_millis() as u64,
        })
    }
}

fn secret_roundtrip(store: &dyn SecretStore) -> Result<bool, CoreError> {
    const KEY: &str = "selftest";
    let blob = b"lumen-selftest".to_vec();
    store.save(KEY.into(), blob.clone())?;
    let read_back = store.load(KEY.into())?;
    store.delete(KEY.into())?;
    Ok(read_back.as_deref() == Some(blob.as_slice()) && store.load(KEY.into())?.is_none())
}

#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::sync::Mutex;

    #[derive(Default)]
    struct MemoryStore(Mutex<HashMap<String, Vec<u8>>>);

    impl SecretStore for MemoryStore {
        fn load(&self, key: String) -> Result<Option<Vec<u8>>, CoreError> {
            Ok(self.0.lock().unwrap().get(&key).cloned())
        }
        fn save(&self, key: String, blob: Vec<u8>) -> Result<(), CoreError> {
            self.0.lock().unwrap().insert(key, blob);
            Ok(())
        }
        fn delete(&self, key: String) -> Result<(), CoreError> {
            self.0.lock().unwrap().remove(&key);
            Ok(())
        }
    }

    /// Store défaillant : `load` ne rend jamais ce qui a été écrit.
    struct BrokenStore;

    impl SecretStore for BrokenStore {
        fn load(&self, _: String) -> Result<Option<Vec<u8>>, CoreError> {
            Ok(None)
        }
        fn save(&self, _: String, _: Vec<u8>) -> Result<(), CoreError> {
            Ok(())
        }
        fn delete(&self, _: String) -> Result<(), CoreError> {
            Err(CoreError::SecretStore { reason: "keystore verrouillé".into() })
        }
    }

    fn core(store: Arc<dyn SecretStore>, client_id: &str) -> Arc<Core> {
        Core::new(
            CoreConfig {
                client_id: client_id.into(),
                data_dir: String::new(),
                app_version: "test".into(),
            },
            store,
        )
    }

    #[tokio::test]
    async fn self_test_passes_with_working_store() {
        let store = Arc::new(MemoryStore::default());
        let result = core(store.clone(), "abc").self_test().await.unwrap();
        assert!(result.async_ok);
        assert!(result.secret_store_ok);
        assert_eq!(result.core_version, env!("CARGO_PKG_VERSION"));
        assert!(store.0.lock().unwrap().is_empty(), "l'autotest ne doit rien laisser");
    }

    #[tokio::test]
    async fn self_test_propagates_store_errors() {
        let err = core(Arc::new(BrokenStore), "abc").self_test().await.unwrap_err();
        assert!(matches!(err, CoreError::SecretStore { .. }));
    }

    #[test]
    fn blank_client_id_is_reported_missing() {
        assert!(!core(Arc::new(MemoryStore::default()), "  ").has_client_id());
        assert!(core(Arc::new(MemoryStore::default()), "abc").has_client_id());
    }
}
