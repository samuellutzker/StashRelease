//! TLS support for the sync WebSocket (wss://).
//!
//! Home servers rarely have a CA-signed certificate, so by default we generate
//! a self-signed one and let clients pin it by its SHA-256 fingerprint
//! (trust-on-first-use). A user-supplied cert/key (e.g. from a reverse proxy or
//! an internal CA) is used as-is if the paths already exist.

use std::io::BufReader;
use std::path::Path;
use std::sync::Arc;

use anyhow::{anyhow, Context};
use sha2::{Digest, Sha256};
use tokio_rustls::rustls::pki_types::{CertificateDer, PrivateKeyDer};
use tokio_rustls::rustls::ServerConfig;
use tokio_rustls::TlsAcceptor;

pub struct Tls {
    pub acceptor: TlsAcceptor,
    /// Uppercase, colon-separated SHA-256 of the leaf certificate (DER).
    pub fingerprint: String,
}

/// Resolve the cert/key paths (defaulting under `<data_dir>/tls/`), generating a
/// self-signed pair if they don't yet exist, then build a `TlsAcceptor`.
pub fn load(
    data_dir: &str,
    cert_path: Option<&str>,
    key_path: Option<&str>,
) -> anyhow::Result<Tls> {
    let default_dir = format!("{}/tls", data_dir);
    let cert_path = cert_path
        .map(|s| s.to_string())
        .unwrap_or_else(|| format!("{}/cert.pem", default_dir));
    let key_path = key_path
        .map(|s| s.to_string())
        .unwrap_or_else(|| format!("{}/key.pem", default_dir));

    if !Path::new(&cert_path).exists() || !Path::new(&key_path).exists() {
        generate_self_signed(&cert_path, &key_path)
            .context("generating self-signed certificate")?;
    }

    let certs = load_certs(&cert_path)?;
    let key = load_key(&key_path)?;
    let leaf = certs
        .first()
        .ok_or_else(|| anyhow!("no certificate found in {}", cert_path))?;
    let fingerprint = fingerprint_of(leaf);

    let config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .context("building rustls ServerConfig")?;

    Ok(Tls {
        acceptor: TlsAcceptor::from(Arc::new(config)),
        fingerprint,
    })
}

fn generate_self_signed(cert_path: &str, key_path: &str) -> anyhow::Result<()> {
    use rcgen::{CertificateParams, DistinguishedName, DnType};

    let mut params =
        CertificateParams::new(vec!["localhost".to_string(), "stash-server".to_string()])?;
    let mut dn = DistinguishedName::new();
    dn.push(DnType::CommonName, "Stash Sync Server");
    params.distinguished_name = dn;

    let key_pair = rcgen::KeyPair::generate()?;
    let cert = params.self_signed(&key_pair)?;

    if let Some(dir) = Path::new(cert_path).parent() {
        std::fs::create_dir_all(dir).ok();
    }
    std::fs::write(cert_path, cert.pem())?;
    std::fs::write(key_path, key_pair.serialize_pem())?;
    Ok(())
}

fn load_certs(path: &str) -> anyhow::Result<Vec<CertificateDer<'static>>> {
    let file = std::fs::File::open(path).with_context(|| format!("opening cert file {}", path))?;
    let mut reader = BufReader::new(file);
    let certs = rustls_pemfile::certs(&mut reader)
        .collect::<Result<Vec<_>, _>>()
        .with_context(|| format!("parsing certs in {}", path))?;
    if certs.is_empty() {
        return Err(anyhow!("no certificates in {}", path));
    }
    Ok(certs)
}

fn load_key(path: &str) -> anyhow::Result<PrivateKeyDer<'static>> {
    let file = std::fs::File::open(path).with_context(|| format!("opening key file {}", path))?;
    let mut reader = BufReader::new(file);
    rustls_pemfile::private_key(&mut reader)
        .with_context(|| format!("parsing key in {}", path))?
        .ok_or_else(|| anyhow!("no private key found in {}", path))
}

/// Uppercase colon-separated hex SHA-256 of a DER certificate — the value
/// clients pin. Matches the common "openssl x509 -fingerprint -sha256" format.
pub fn fingerprint_of(cert: &CertificateDer<'_>) -> String {
    let digest = Sha256::digest(cert.as_ref());
    digest
        .iter()
        .map(|b| format!("{:02X}", b))
        .collect::<Vec<_>>()
        .join(":")
}

// ── Mirror client TLS (connecting to a peer over wss://) ────────────────────────

use tokio_rustls::rustls::client::danger::{
    HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier,
};
use tokio_rustls::rustls::crypto::{
    aws_lc_rs, verify_tls12_signature, verify_tls13_signature, CryptoProvider,
};
use tokio_rustls::rustls::pki_types::ServerName;
use tokio_rustls::rustls::{ClientConfig, DigitallySignedStruct, SignatureScheme};

/// Verifier that pins a peer's self-signed certificate by SHA-256 fingerprint. Peer servers use a
/// self-signed cert (no CA), so we authenticate by the exact cert: if `pinned` is set the cert must
/// match it, otherwise we accept any cert (encrypt-only) but log its fingerprint so the admin can
/// pin it. Handshake signatures are still verified normally, so pinning genuinely prevents MITM.
#[derive(Debug)]
struct PinnedVerifier {
    pinned: Option<String>,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinnedVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: tokio_rustls::rustls::pki_types::UnixTime,
    ) -> Result<ServerCertVerified, tokio_rustls::rustls::Error> {
        let fp = fingerprint_of(end_entity);
        match &self.pinned {
            Some(pin) if !pin.eq_ignore_ascii_case(&fp) => {
                return Err(tokio_rustls::rustls::Error::General(format!(
                    "mirror peer certificate fingerprint mismatch: pinned {pin}, got {fp}"
                )));
            }
            Some(_) => {}
            None => tracing::warn!(
                "[mirror] peer TLS cert NOT pinned (encrypt-only); set mirror.fingerprint = \"{fp}\" to enforce"
            ),
        }
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, tokio_rustls::rustls::Error> {
        verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, tokio_rustls::rustls::Error> {
        verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// A tokio-tungstenite TLS connector for the mirror, pinning the peer cert by `fingerprint`
/// (None = encrypt-only, accept any cert).
pub fn mirror_connector(
    fingerprint: Option<String>,
) -> anyhow::Result<tokio_tungstenite::Connector> {
    let provider = Arc::new(aws_lc_rs::default_provider());
    let config = ClientConfig::builder_with_provider(Arc::clone(&provider))
        .with_safe_default_protocol_versions()?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinnedVerifier {
            pinned: fingerprint,
            provider,
        }))
        .with_no_client_auth();
    Ok(tokio_tungstenite::Connector::Rustls(Arc::new(config)))
}
