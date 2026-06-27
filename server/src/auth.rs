use argon2::{password_hash::PasswordHash, Argon2, PasswordVerifier};

/// Verify a WS auth attempt: argon2_hash is the stored PHC string; sha256_hex is the
/// hex-encoded SHA-256 of the password sent by the client.
pub fn verify_hash(argon2_hash: &str, sha256_hex: &str) -> bool {
    let Ok(sha256_bytes) = hex::decode(sha256_hex) else {
        return false;
    };
    let Ok(parsed) = PasswordHash::new(argon2_hash) else {
        return false;
    };
    Argon2::default()
        .verify_password(&sha256_bytes, &parsed)
        .is_ok()
}
