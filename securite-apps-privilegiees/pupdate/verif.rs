//! verif.rs — PuppySignatureVerifier : la vérification cryptographique du
//! catalogue signé, en Rust, adossée à la crypto C déjà éprouvée de PuppyOS
//! (Monocypher : Ed25519 + SHA-256). On NE réimplémente PAS la crypto — on
//! appelle `crypto_ed25519_check` et `sha256` de libpuppy via FFI.
//!
//! Règle de la directive appliquée ici :
//!   - une empreinte SHA-256 seule ne prouve JAMAIS l'authenticité : elle ne
//!     sert qu'à détecter une corruption de transport. L'authenticité vient de
//!     la signature Ed25519, vérifiée contre une clé publique de confiance
//!     compilée dans l'OS (CLE_PUBLIQUE_PAQUETS).
//!   - on vérifie la signature AVANT de faire confiance au contenu du catalogue.
//!
//! Format de la signature du catalogue (voir RAPPORT §4) :
//!   catalogue.sig = Ed25519( "CATALOGUE1" | SHA-256(catalogue.txt) )
//! soit un message de 10 + 32 = 42 octets, signé par la clé des paquets.
#![allow(dead_code)]

use core::ffi::c_int;

const PREFIXE: &[u8; 10] = b"CATALOGUE1";
const MSG_LEN: usize = 10 + 32; // préfixe + empreinte

extern "C" {
    /// 0 si la signature est valide (Monocypher).
    fn crypto_ed25519_check(sig: *const u8, pk: *const u8, msg: *const u8, n: usize) -> c_int;
    /// SHA-256 d'un bloc en une passe (crypto.h).
    fn sha256(data: *const u8, n: usize, out: *mut u8);
}

/// Construit le message signé (préfixe de domaine + empreinte du texte).
/// Le préfixe « CATALOGUE1 » empêche qu'une signature faite pour un autre objet
/// (un .puppy, un manifeste de version) soit rejouée comme signature de
/// catalogue : chaque usage a son propre préfixe de domaine.
fn message(texte: &[u8]) -> [u8; MSG_LEN] {
    let mut h = [0u8; 32];
    // SAFETY : sha256 écrit exactement 32 octets dans `out`, lit `texte` en
    // lecture seule ; les pointeurs sont valides pour la durée de l'appel.
    unsafe { sha256(texte.as_ptr(), texte.len(), h.as_mut_ptr()) };
    let mut m = [0u8; MSG_LEN];
    m[..10].copy_from_slice(PREFIXE);
    m[10..].copy_from_slice(&h);
    m
}

/// Vérifie la signature Ed25519 du catalogue. `true` seulement si la signature
/// est valide pour `cle_pub`. Aucune autre voie de confiance.
pub fn verifier_catalogue(texte: &[u8], signature: &[u8; 64], cle_pub: &[u8; 32]) -> bool {
    let m = message(texte);
    // SAFETY : tous les tampons ont les tailles exigées par l'API (64/32/42) et
    // vivent pour la durée de l'appel ; `check` ne fait que lire.
    unsafe { crypto_ed25519_check(signature.as_ptr(), cle_pub.as_ptr(), m.as_ptr(), m.len()) == 0 }
}

/// SHA-256 d'un bloc, en hexadécimal minuscule — utile pour comparer au champ
/// `sha256=` du catalogue après téléchargement d'un paquet (corruption de
/// transport uniquement ; NE prouve pas l'authenticité).
pub fn empreinte_hex(donnees: &[u8]) -> [u8; 64] {
    let mut h = [0u8; 32];
    unsafe { sha256(donnees.as_ptr(), donnees.len(), h.as_mut_ptr()) };
    let mut hex = [0u8; 64];
    const CHIFFRES: &[u8; 16] = b"0123456789abcdef";
    for (i, b) in h.iter().enumerate() {
        hex[i * 2] = CHIFFRES[(b >> 4) as usize];
        hex[i * 2 + 1] = CHIFFRES[(b & 0x0f) as usize];
    }
    hex
}

/// Compare une empreinte attendue (champ `sha256=`, texte) à des données reçues.
/// Comparaison simple suffisante ici : le sha256 n'est qu'un garde-fou de
/// transport, la vraie barrière est la signature du .puppy en aval.
pub fn empreinte_correspond(donnees: &[u8], attendu_hex: &str) -> bool {
    if attendu_hex.len() != 64 {
        return false;
    }
    let calc = empreinte_hex(donnees);
    attendu_hex.as_bytes().eq_ignore_ascii_case(&calc)
}

// ===========================================================================
//                                AUTOTESTS
//  On relie la crypto C de PuppyOS. Construction du test :
//    cc -c -I libs/libpuppy libs/libpuppy/crypto.c -o crypto.o
//    cc -c libs/libpuppy/monocypher.c -o monocypher.o
//    cc -c libs/libpuppy/monocypher-ed25519.c -o mced.o
//    rustc --edition 2021 --test system/pupdate/verif.rs \
//          -C link-arg=crypto.o -C link-arg=monocypher.o -C link-arg=mced.o \
//          -o verif_test && ./verif_test
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    extern "C" {
        fn crypto_ed25519_key_pair(secret: *mut u8, public: *mut u8, seed: *mut u8);
        fn crypto_ed25519_sign(sig: *mut u8, secret: *const u8, msg: *const u8, n: usize);
    }

    fn paire() -> ([u8; 64], [u8; 32]) {
        let mut secret = [0u8; 64];
        let mut public = [0u8; 32];
        let mut seed = [7u8; 32]; // graine fixe pour un test reproductible
        unsafe { crypto_ed25519_key_pair(secret.as_mut_ptr(), public.as_mut_ptr(), seed.as_mut_ptr()) };
        (secret, public)
    }

    fn signer_catalogue(texte: &[u8], secret: &[u8; 64]) -> [u8; 64] {
        let m = message(texte);
        let mut sig = [0u8; 64];
        unsafe { crypto_ed25519_sign(sig.as_mut_ptr(), secret.as_ptr(), m.as_ptr(), m.len()) };
        sig
    }

    #[test]
    fn signature_valide_acceptee() {
        let (secret, public) = paire();
        let texte = b"catalogue=1\nsequence=42\n";
        let sig = signer_catalogue(texte, &secret);
        assert!(verifier_catalogue(texte, &sig, &public));
    }

    #[test]
    fn texte_modifie_rejete() {
        let (secret, public) = paire();
        let texte = b"catalogue=1\nsequence=42\n";
        let sig = signer_catalogue(texte, &secret);
        // un octet changé -> signature invalide
        let mut falsifie = texte.to_vec();
        falsifie[20] ^= 1;
        assert!(!verifier_catalogue(&falsifie, &sig, &public));
    }

    #[test]
    fn mauvaise_cle_rejete() {
        let (secret, _public) = paire();
        let (_s2, autre_public) = {
            let mut secret = [0u8; 64];
            let mut public = [0u8; 32];
            let mut seed = [9u8; 32];
            unsafe { crypto_ed25519_key_pair(secret.as_mut_ptr(), public.as_mut_ptr(), seed.as_mut_ptr()) };
            (secret, public)
        };
        let texte = b"catalogue=1\nsequence=1\n";
        let sig = signer_catalogue(texte, &secret);
        // signé par `secret`, vérifié avec une autre clé publique -> refus
        assert!(!verifier_catalogue(texte, &sig, &autre_public));
    }

    #[test]
    fn signature_zero_rejete() {
        let (_secret, public) = paire();
        let texte = b"catalogue=1\nsequence=1\n";
        let sig = [0u8; 64];
        assert!(!verifier_catalogue(texte, &sig, &public));
    }

    #[test]
    fn empreinte_hex_connue() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        let hex = empreinte_hex(b"abc");
        assert_eq!(
            core::str::from_utf8(&hex).unwrap(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert!(empreinte_correspond(b"abc",
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"));
        assert!(!empreinte_correspond(b"abd",
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
    }

    #[test]
    fn prefixe_domaine_empeche_rejeu() {
        // Une signature faite sur le message BRUT (sans préfixe CATALOGUE1) ne
        // doit pas valider via verifier_catalogue (qui préfixe toujours).
        let (secret, public) = paire();
        let texte = b"catalogue=1\nsequence=1\n";
        let mut h = [0u8; 32];
        unsafe { sha256(texte.as_ptr(), texte.len(), h.as_mut_ptr()) };
        // signe directement l'empreinte sans préfixe
        let mut sig = [0u8; 64];
        unsafe { crypto_ed25519_sign(sig.as_mut_ptr(), secret.as_ptr(), h.as_ptr(), h.len()) };
        assert!(!verifier_catalogue(texte, &sig, &public));
    }
}
