use std::fmt;

use serde::{Deserialize, Serialize};
use zeroize::Zeroize;

/// Chaîne sensible (jeton OAuth, code d'appareil) : jamais affichée, effacée à la libération.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Secret(String);

impl Secret {
    #[cfg(test)]
    pub fn new(value: impl Into<String>) -> Self {
        Self(value.into())
    }

    /// Seul point d'accès à la valeur : chaque appel est un endroit à auditer.
    pub fn expose(&self) -> &str {
        &self.0
    }
}

impl fmt::Debug for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Secret(***)")
    }
}

impl Drop for Secret {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn debug_never_prints_the_value() {
        let secret = Secret::new("oauth-abcdef123456");
        assert!(!format!("{secret:?}").contains("abcdef"));
        assert!(!format!("{:?}", Some(&secret)).contains("abcdef"));
    }
}
