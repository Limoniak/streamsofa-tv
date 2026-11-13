use qrcode::{Color, EcLevel, QrCode};

use crate::error::CoreError;

/// QR code prêt à dessiner : `size` × `size` modules, ligne par ligne, `true` = sombre.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct QrMatrix {
    pub size: u32,
    pub modules: Vec<bool>,
}

pub fn encode(text: &str) -> Result<QrMatrix, CoreError> {
    // Niveau M : bon compromis entre densité et lisibilité depuis un canapé.
    let code = QrCode::with_error_correction_level(text.as_bytes(), EcLevel::M)
        .map_err(|e| CoreError::Internal { reason: e.to_string() })?;
    Ok(QrMatrix {
        size: code.width() as u32,
        modules: code.to_colors().into_iter().map(|c| c == Color::Dark).collect(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matrix_is_square_and_has_finder_patterns() {
        let qr = encode("https://www.twitch.tv/activate?device-code=ABCDEFGH").unwrap();
        let n = qr.size as usize;
        assert_eq!(qr.modules.len(), n * n);
        // Les trois repères d'angle commencent par un module sombre.
        assert!(qr.modules[0] && qr.modules[n - 1] && qr.modules[n * (n - 1)]);
    }
}
