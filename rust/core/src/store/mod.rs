//! Base SQLite locale. Ne contient jamais de jeton : uniquement des métadonnées.

use std::collections::HashMap;
use std::path::Path;
use std::sync::Mutex;

use rusqlite::{params, Connection, OptionalExtension};

use crate::accounts::{Account, AccountState};
use crate::error::CoreError;

/// Une entrée par version de schéma, appliquées dans l'ordre ; `PRAGMA user_version` fait foi.
const MIGRATIONS: &[&str] = &["
    CREATE TABLE accounts (
        id               TEXT PRIMARY KEY,
        login            TEXT NOT NULL,
        display_name     TEXT NOT NULL,
        avatar_url       TEXT NOT NULL,
        scopes           TEXT NOT NULL,
        state            TEXT NOT NULL,
        added_at         INTEGER NOT NULL,
        last_used_at     INTEGER NOT NULL
    );
    CREATE TABLE app_state (
        key   TEXT PRIMARY KEY,
        value TEXT NOT NULL
    );
", "
    -- `scope` : id du compte, ou `guest`. Chaque profil a ses propres reprises.
    CREATE TABLE vod_progress (
        scope       TEXT NOT NULL,
        video_id    TEXT NOT NULL,
        position_s  INTEGER NOT NULL,
        duration_s  INTEGER NOT NULL,
        finished    INTEGER NOT NULL,
        updated_at  INTEGER NOT NULL,
        PRIMARY KEY (scope, video_id)
    );
", "
    -- `scope` : `global`, `guest` ou id du compte. Valeurs en texte, interprétées côté app.
    CREATE TABLE settings (
        scope  TEXT NOT NULL,
        key    TEXT NOT NULL,
        value  TEXT NOT NULL,
        PRIMARY KEY (scope, key)
    );
", "
    -- Réponses d'API décoratives (badges, émotes) gardées entre deux lancements.
    CREATE TABLE meta_cache (
        key        TEXT PRIMARY KEY,
        body       TEXT NOT NULL,
        fetched_at INTEGER NOT NULL,
        ttl_s      INTEGER NOT NULL
    );
"];

const ACTIVE_ACCOUNT: &str = "active_account_id";

pub struct Db(Mutex<Connection>);

impl Db {
    pub fn open(path: &Path) -> Result<Self, CoreError> {
        Self::init(Connection::open(path)?)
    }

    #[cfg(test)]
    pub fn in_memory() -> Result<Self, CoreError> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(mut conn: Connection) -> Result<Self, CoreError> {
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        let version: u32 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
        for (index, sql) in MIGRATIONS.iter().enumerate().skip(version as usize) {
            let tx = conn.transaction()?;
            tx.execute_batch(sql)?;
            tx.pragma_update(None, "user_version", (index + 1) as u32)?;
            tx.commit()?;
        }
        Ok(Self(Mutex::new(conn)))
    }

    fn conn(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.0.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    pub fn accounts(&self) -> Result<Vec<Account>, CoreError> {
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT id, login, display_name, avatar_url, state FROM accounts ORDER BY added_at, id",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(Account {
                id: r.get(0)?,
                login: r.get(1)?,
                display_name: r.get(2)?,
                avatar_url: r.get(3)?,
                state: AccountState::from_db(&r.get::<_, String>(4)?),
            })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    pub fn account_exists(&self, id: &str) -> Result<bool, CoreError> {
        Ok(self
            .conn()
            .query_row("SELECT 1 FROM accounts WHERE id = ?1", [id], |_| Ok(()))
            .optional()?
            .is_some())
    }

    /// Ajoute le compte, ou met à jour son profil s'il se reconnecte.
    pub fn upsert_account(&self, account: &Account, scopes: &str, now: i64) -> Result<(), CoreError> {
        self.conn().execute(
            "INSERT INTO accounts (id, login, display_name, avatar_url, scopes, state, added_at, last_used_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?7)
             ON CONFLICT(id) DO UPDATE SET
                 login = excluded.login, display_name = excluded.display_name,
                 avatar_url = excluded.avatar_url, scopes = excluded.scopes,
                 state = excluded.state, last_used_at = excluded.last_used_at",
            params![
                account.id, account.login, account.display_name, account.avatar_url,
                scopes, account.state.as_db(), now
            ],
        )?;
        Ok(())
    }

    /// Renvoie `true` si l'état a réellement changé.
    pub fn set_account_state(&self, id: &str, state: AccountState) -> Result<bool, CoreError> {
        let changed = self.conn().execute(
            "UPDATE accounts SET state = ?2 WHERE id = ?1 AND state <> ?2",
            params![id, state.as_db()],
        )?;
        Ok(changed > 0)
    }

    /// Une VOD vue à 95 % compte comme terminée : le générique de fin ne bloque pas la reprise.
    pub fn save_vod_progress(&self, scope: &str, video_id: &str, position_s: u32, duration_s: u32, now: i64) -> Result<bool, CoreError> {
        let finished = duration_s > 0 && position_s as u64 * 100 >= duration_s as u64 * 95;
        self.conn().execute(
            "INSERT INTO vod_progress (scope, video_id, position_s, duration_s, finished, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(scope, video_id) DO UPDATE SET
                 position_s = excluded.position_s, duration_s = excluded.duration_s,
                 finished = excluded.finished, updated_at = excluded.updated_at",
            params![scope, video_id, position_s, duration_s, finished, now],
        )?;
        Ok(finished)
    }

    /// Position et fin de lecture de chaque VOD déjà vue parmi `video_ids`.
    pub fn vod_progress(&self, scope: &str, video_ids: &[&str]) -> Result<HashMap<String, (u32, bool)>, CoreError> {
        let conn = self.conn();
        let mut stmt = conn.prepare_cached("SELECT position_s, finished FROM vod_progress WHERE scope = ?1 AND video_id = ?2")?;
        let mut out = HashMap::new();
        for id in video_ids {
            if let Some(row) = stmt.query_row(params![scope, id], |r| Ok((r.get(0)?, r.get(1)?))).optional()? {
                out.insert((*id).to_owned(), row);
            }
        }
        Ok(out)
    }

    pub fn settings(&self, scope: &str) -> Result<HashMap<String, String>, CoreError> {
        let conn = self.conn();
        let mut stmt = conn.prepare_cached("SELECT key, value FROM settings WHERE scope = ?1")?;
        let rows = stmt.query_map([scope], |r| Ok((r.get(0)?, r.get(1)?)))?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    pub fn set_setting(&self, scope: &str, key: &str, value: &str) -> Result<(), CoreError> {
        self.conn().execute(
            "INSERT INTO settings (scope, key, value) VALUES (?1, ?2, ?3)
             ON CONFLICT(scope, key) DO UPDATE SET value = excluded.value",
            params![scope, key, value],
        )?;
        Ok(())
    }

    /// `None` si absent ou expiré.
    pub fn meta_get(&self, key: &str, now: i64) -> Result<Option<String>, CoreError> {
        Ok(self
            .conn()
            .prepare_cached("SELECT body FROM meta_cache WHERE key = ?1 AND fetched_at + ttl_s > ?2")?
            .query_row(params![key, now], |r| r.get(0))
            .optional()?)
    }

    /// Enregistre `body` et purge au passage les entrées expirées.
    pub fn meta_put(&self, key: &str, body: &str, now: i64, ttl_s: i64) -> Result<(), CoreError> {
        let conn = self.conn();
        conn.execute("DELETE FROM meta_cache WHERE fetched_at + ttl_s <= ?1", [now])?;
        conn.execute(
            "INSERT OR REPLACE INTO meta_cache (key, body, fetched_at, ttl_s) VALUES (?1, ?2, ?3, ?4)",
            params![key, body, now, ttl_s],
        )?;
        Ok(())
    }

    pub fn delete_account(&self, id: &str) -> Result<(), CoreError> {
        let mut conn = self.conn();
        let tx = conn.transaction()?;
        tx.execute("DELETE FROM accounts WHERE id = ?1", [id])?;
        tx.execute("DELETE FROM vod_progress WHERE scope = ?1", [id])?;
        tx.execute("DELETE FROM settings WHERE scope = ?1", [id])?;
        tx.execute("DELETE FROM app_state WHERE key = ?1 AND value = ?2", params![ACTIVE_ACCOUNT, id])?;
        tx.commit()?;
        Ok(())
    }

    pub fn active_account_id(&self) -> Result<Option<String>, CoreError> {
        Ok(self
            .conn()
            .query_row("SELECT value FROM app_state WHERE key = ?1", [ACTIVE_ACCOUNT], |r| r.get(0))
            .optional()?)
    }

    /// `None` = mode invité.
    pub fn set_active_account(&self, id: Option<&str>, now: i64) -> Result<(), CoreError> {
        let mut conn = self.conn();
        let tx = conn.transaction()?;
        match id {
            Some(id) => {
                tx.execute(
                    "INSERT INTO app_state (key, value) VALUES (?1, ?2)
                     ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    params![ACTIVE_ACCOUNT, id],
                )?;
                tx.execute("UPDATE accounts SET last_used_at = ?2 WHERE id = ?1", params![id, now])?;
            }
            None => {
                tx.execute("DELETE FROM app_state WHERE key = ?1", [ACTIVE_ACCOUNT])?;
            }
        }
        tx.commit()?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn account(id: &str) -> Account {
        Account {
            id: id.into(),
            login: format!("login{id}"),
            display_name: format!("Name{id}"),
            avatar_url: String::new(),
            state: AccountState::Ok,
        }
    }

    #[test]
    fn reopening_keeps_data_and_does_not_rerun_migrations() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("lumen.db");
        {
            let db = Db::open(&path).unwrap();
            db.upsert_account(&account("1"), "a b", 10).unwrap();
            db.set_active_account(Some("1"), 11).unwrap();
        }
        let db = Db::open(&path).unwrap();
        assert_eq!(db.accounts().unwrap(), vec![account("1")]);
        assert_eq!(db.active_account_id().unwrap().as_deref(), Some("1"));
    }

    #[test]
    fn upsert_keeps_insertion_order_when_an_account_reconnects() {
        let db = Db::in_memory().unwrap();
        db.upsert_account(&account("1"), "", 10).unwrap();
        db.upsert_account(&account("2"), "", 20).unwrap();
        db.set_account_state("1", AccountState::NeedsReauth).unwrap();
        db.upsert_account(&account("1"), "", 30).unwrap();
        let ids: Vec<_> = db.accounts().unwrap().into_iter().map(|a| (a.id, a.state)).collect();
        assert_eq!(ids, vec![("1".into(), AccountState::Ok), ("2".into(), AccountState::Ok)]);
    }

    #[test]
    fn vod_progress_is_per_profile_and_marks_the_end() {
        let db = Db::in_memory().unwrap();
        db.save_vod_progress("guest", "v1", 120, 3_600, 1).unwrap();
        db.save_vod_progress("guest", "v2", 3_500, 3_600, 1).unwrap();
        db.save_vod_progress("42", "v1", 30, 3_600, 1).unwrap();
        db.save_vod_progress("guest", "v1", 200, 3_600, 2).unwrap();
        let guest = db.vod_progress("guest", &["v1", "v2", "v3"]).unwrap();
        assert_eq!(guest.get("v1"), Some(&(200, false)));
        assert_eq!(guest.get("v2"), Some(&(3_500, true)));
        assert!(!guest.contains_key("v3"));
        assert_eq!(db.vod_progress("42", &["v1"]).unwrap().get("v1"), Some(&(30, false)));
        db.upsert_account(&account("42"), "", 1).unwrap();
        db.delete_account("42").unwrap();
        assert!(db.vod_progress("42", &["v1"]).unwrap().is_empty(), "retirer un compte efface ses reprises");
    }

    #[test]
    fn deleting_the_active_account_falls_back_to_guest() {
        let db = Db::in_memory().unwrap();
        db.upsert_account(&account("1"), "", 10).unwrap();
        db.upsert_account(&account("2"), "", 10).unwrap();
        db.set_active_account(Some("1"), 10).unwrap();
        db.delete_account("2").unwrap();
        assert_eq!(db.active_account_id().unwrap().as_deref(), Some("1"));
        db.delete_account("1").unwrap();
        assert_eq!(db.active_account_id().unwrap(), None);
    }

    #[test]
    fn meta_cache_expires() {
        let db = Db::in_memory().unwrap();
        db.meta_put("badges:global", "{}", 100, 60).unwrap();
        assert_eq!(db.meta_get("badges:global", 159).unwrap().as_deref(), Some("{}"));
        assert_eq!(db.meta_get("badges:global", 160).unwrap(), None);
        db.meta_put("other", "x", 200, 60).unwrap();
        assert_eq!(db.meta_get("badges:global", 0).unwrap(), None, "purgée au passage");
    }

    #[test]
    fn settings_are_overwritten_per_scope() {
        let db = Db::in_memory().unwrap();
        db.set_setting("global", "accent", "violet").unwrap();
        db.set_setting("global", "accent", "blue").unwrap();
        db.set_setting("42", "accent", "green").unwrap();
        assert_eq!(db.settings("global").unwrap().get("accent").map(String::as_str), Some("blue"));
        db.upsert_account(&account("42"), "", 1).unwrap();
        db.delete_account("42").unwrap();
        assert!(db.settings("42").unwrap().is_empty());
    }
}
