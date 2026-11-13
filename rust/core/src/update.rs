//! Mise à jour de l'app depuis les GitHub Releases du projet (APK sideload).
//! L'installation reste côté Android, qui vérifie que la signature correspond à l'app installée.

use std::path::Path;
use std::time::Duration;

use serde::Deserialize;

use crate::error::CoreError;

/// Dépôt dont les releases publient les APK (`lumen-<version>-<abi>.apk`).
pub(crate) const REPO: &str = "Limoniak/lumen-tv";

/// Une version plus récente que celle installée, avec l'APK adapté à l'appareil.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AppUpdate {
    pub version: String,
    /// Notes de version (Markdown brut).
    pub notes: String,
    pub apk_url: String,
    pub apk_size: u64,
}

#[derive(Deserialize)]
struct GithubRelease {
    tag_name: String,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    assets: Vec<GithubAsset>,
}

#[derive(Deserialize)]
struct GithubAsset {
    name: String,
    browser_download_url: String,
    size: u64,
}

/// `v1.2.3`, `1.2.3-beta` → `[1, 2, 3]`. `None` si ce n'est pas un numéro de version.
fn parse_version(text: &str) -> Option<Vec<u64>> {
    let core = text.trim().trim_start_matches('v').split(['-', '+']).next()?;
    core.split('.').map(|part| part.parse().ok()).collect()
}

fn is_newer(candidate: &str, current: &str) -> bool {
    match (parse_version(candidate), parse_version(current)) {
        (Some(mut a), Some(mut b)) => {
            let len = a.len().max(b.len());
            a.resize(len, 0);
            b.resize(len, 0);
            a > b
        }
        _ => false,
    }
}

/// L'APK de la première ABI supportée (par ordre de préférence de l'appareil), sinon l'universel.
fn pick_apk<'a>(assets: &'a [GithubAsset], abis: &[String]) -> Option<&'a GithubAsset> {
    let find = |suffix: &str| assets.iter().find(|a| a.name.ends_with(&format!("-{suffix}.apk")));
    abis.iter().find_map(|abi| find(abi)).or_else(|| find("universal"))
}

/// Dernière release publiée, si elle est plus récente que `current_version` et propose un APK installable.
pub(crate) async fn check(http: &reqwest::Client, api: &str, current_version: &str, abis: &[String]) -> Result<Option<AppUpdate>, CoreError> {
    let response = http
        .get(format!("{api}/repos/{REPO}/releases/latest"))
        .header("Accept", "application/vnd.github+json")
        .send()
        .await?;
    match response.status().as_u16() {
        200..=299 => {}
        // Aucune release publiée pour l'instant.
        404 => return Ok(None),
        status => return Err(CoreError::Api { status, detail: "releases GitHub".into() }),
    }
    let release: GithubRelease = response.json().await?;
    if release.draft || release.prerelease || !is_newer(&release.tag_name, current_version) {
        return Ok(None);
    }
    Ok(pick_apk(&release.assets, abis).map(|asset| AppUpdate {
        version: release.tag_name.trim_start_matches('v').to_owned(),
        notes: release.body.clone().unwrap_or_default(),
        apk_url: asset.browser_download_url.clone(),
        apk_size: asset.size,
    }))
}

/// Télécharge `url` dans `dest` via un fichier temporaire : un APK tronqué n'est jamais proposé.
pub(crate) async fn download(http: &reqwest::Client, url: &str, dest: &Path) -> Result<(), CoreError> {
    let storage = |e: std::io::Error| CoreError::Storage { reason: e.to_string() };
    // Le délai global du client (20 s) est fait pour l'API, pas pour un APK sur une box lente.
    let mut response = http.get(url).timeout(Duration::from_secs(600)).send().await?;
    let status = response.status().as_u16();
    if !(200..=299).contains(&status) {
        return Err(CoreError::Api { status, detail: "téléchargement de la mise à jour".into() });
    }
    if let Some(dir) = dest.parent() {
        tokio::fs::create_dir_all(dir).await.map_err(storage)?;
    }
    let partial = dest.with_extension("part");
    let mut file = tokio::fs::File::create(&partial).await.map_err(storage)?;
    use tokio::io::AsyncWriteExt;
    while let Some(chunk) = response.chunk().await? {
        file.write_all(&chunk).await.map_err(storage)?;
    }
    file.flush().await.map_err(storage)?;
    drop(file);
    tokio::fs::rename(&partial, dest).await.map_err(storage)
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn asset(name: &str) -> GithubAsset {
        GithubAsset { name: name.into(), browser_download_url: format!("https://dl/{name}"), size: 1 }
    }

    #[test]
    fn compares_versions_numerically() {
        assert!(is_newer("v0.10.0", "0.9.3"));
        assert!(is_newer("v1.0", "0.9.9"));
        assert!(!is_newer("v0.1.0", "0.1.0"));
        assert!(!is_newer("v0.1", "0.1.0"));
        assert!(!is_newer("v0.0.9", "0.1.0"));
        assert!(is_newer("v0.2.0", "0.1.0-dev"));
        assert!(!is_newer("nightly", "0.1.0"));
    }

    #[test]
    fn picks_the_apk_of_the_preferred_abi() {
        let assets = [asset("lumen-0.2.0-universal.apk"), asset("lumen-0.2.0-armeabi-v7a.apk"), asset("lumen-0.2.0-arm64-v8a.apk")];
        let abis = |list: &[&str]| list.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        assert_eq!(pick_apk(&assets, &abis(&["arm64-v8a", "armeabi-v7a"])).unwrap().name, "lumen-0.2.0-arm64-v8a.apk");
        assert_eq!(pick_apk(&assets, &abis(&["armeabi-v7a"])).unwrap().name, "lumen-0.2.0-armeabi-v7a.apk");
        assert_eq!(pick_apk(&assets, &abis(&["x86_64"])).unwrap().name, "lumen-0.2.0-universal.apk");
        assert!(pick_apk(&[asset("notes.txt")], &abis(&["x86_64"])).is_none());
    }

    #[tokio::test]
    async fn offers_only_a_newer_release() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path(format!("/repos/{REPO}/releases/latest")))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "tag_name": "v0.2.0",
                "body": "Nouveautés",
                "draft": false,
                "prerelease": false,
                "assets": [{"name": "lumen-0.2.0-universal.apk", "browser_download_url": "https://dl/u.apk", "size": 42}]
            })))
            .mount(&server)
            .await;
        let http = crate::http::build_client("test").unwrap();
        let abis = vec!["arm64-v8a".to_string()];
        let update = check(&http, &server.uri(), "0.1.0", &abis).await.unwrap().unwrap();
        assert_eq!(update, AppUpdate { version: "0.2.0".into(), notes: "Nouveautés".into(), apk_url: "https://dl/u.apk".into(), apk_size: 42 });
        assert!(check(&http, &server.uri(), "0.2.0", &abis).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn no_release_is_not_an_error() {
        let server = MockServer::start().await;
        Mock::given(method("GET")).respond_with(ResponseTemplate::new(404)).mount(&server).await;
        assert!(check(&crate::http::build_client("test").unwrap(), &server.uri(), "0.1.0", &[]).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn downloads_to_the_destination() {
        let server = MockServer::start().await;
        Mock::given(method("GET")).and(path("/a.apk")).respond_with(ResponseTemplate::new(200).set_body_bytes(vec![7u8; 100_000])).mount(&server).await;
        let dir = tempfile::tempdir().unwrap();
        let dest = dir.path().join("updates/lumen.apk");
        download(&crate::http::build_client("test").unwrap(), &format!("{}/a.apk", server.uri()), &dest).await.unwrap();
        assert_eq!(std::fs::read(&dest).unwrap().len(), 100_000);
        assert!(!dest.with_extension("part").exists());
    }
}
