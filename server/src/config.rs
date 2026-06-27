use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Config {
    pub bind: Option<String>,
    pub password_hash: Option<String>,
    pub data_dir: Option<String>,
    /// Maximum file size in megabytes (default: 2048 = 2 GB).
    pub max_file_size_mb: Option<u64>,
    /// Bind address for the web UI (default: 127.0.0.1:9877 — localhost only).
    /// Set to "0.0.0.0:9877" to expose on the LAN (no TLS — use at own risk).
    pub web_bind: Option<String>,
    /// Optional mirror peer — this server will connect to the peer and sync bidirectionally.
    pub mirror: Option<MirrorConfig>,
    /// Optional TLS — when enabled, the sync WebSocket serves wss:// instead of ws://.
    pub tls: Option<TlsConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TlsConfig {
    /// Master switch. When true the sync listener uses TLS (wss://).
    #[serde(default)]
    pub enabled: bool,
    /// PEM certificate chain path. If missing when enabled, a self-signed cert
    /// is generated here (alongside `key_path`).
    pub cert_path: Option<String>,
    /// PEM private key path (PKCS#8). Generated with the cert if absent.
    pub key_path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MirrorConfig {
    /// host:port of the peer server, e.g. "192.168.1.100:9876"
    pub host: String,
    /// SHA-256 hex hash of the peer's password (use `stash-server set-password` on the peer)
    pub password_hash: String,
    /// Connect to the peer over TLS (wss://). The peer must have `[tls] enabled = true`.
    #[serde(default)]
    pub tls: bool,
    /// Optional SHA-256 fingerprint (uppercase colon-separated) of the peer's certificate to pin.
    /// When set, a peer cert that doesn't match is rejected; when omitted, TLS is encrypt-only.
    pub fingerprint: Option<String>,
}

impl Config {
    pub fn load(path: &str) -> anyhow::Result<Self> {
        let text = std::fs::read_to_string(path)
            .map_err(|e| anyhow::anyhow!("Cannot read config file '{}': {}", path, e))?;
        let cfg: Config = toml::from_str(&text)
            .map_err(|e| anyhow::anyhow!("Invalid config file '{}': {}", path, e))?;
        Ok(cfg)
    }

    pub fn save(path: &str, cfg: &Config) -> anyhow::Result<()> {
        let text = toml::to_string_pretty(cfg)?;
        std::fs::write(path, text)?;
        Ok(())
    }
}

/// Returns the SHA-256 hex of a password. This is what clients store and send during WS auth.
pub fn sha256_hex(password: &str) -> String {
    use sha2::{Digest, Sha256};
    hex::encode(Sha256::digest(password.as_bytes()))
}

/// Returns an Argon2id PHC string of SHA-256(password), suitable for storage in the config file.
pub fn hash_password(password: &str) -> String {
    use argon2::{
        password_hash::{PasswordHasher, SaltString},
        Argon2,
    };
    use rand_core::OsRng;
    use sha2::{Digest, Sha256};
    let sha256: Vec<u8> = Sha256::digest(password.as_bytes()).to_vec();
    #[cfg(not(test))]
    let argon2 = Argon2::default();
    #[cfg(test)]
    let argon2 = {
        use argon2::{Algorithm, Version};
        Argon2::new(
            Algorithm::Argon2id,
            Version::V0x13,
            argon2::Params::new(1024, 1, 1, None).expect("params"),
        )
    };
    let salt = SaltString::generate(&mut OsRng);
    argon2
        .hash_password(&sha256, &salt)
        .expect("argon2 hash failed")
        .to_string()
}

/// Returns true if the stored hash looks like a legacy SHA-256 hash (pre-Argon2id).
pub fn is_legacy_hash(hash: &str) -> bool {
    !hash.starts_with('$') && hash.len() == 64 && hash.chars().all(|c| c.is_ascii_hexdigit())
}
