//! pupdate.rs — racine de la bibliothèque PuppyUpdate / PuppyRegistry.
//!
//! Elle réunit les modules du chantier (voir RAPPORT-AUDIT-PUPPYUPDATE-REGISTRY.md) :
//!   - registre : PuppyRegistry (inventaire + états, parseur de catalogue non fiable)
//!   - verif    : PuppySignatureVerifier (Ed25519 + SHA-256, adossé à Monocypher C)
//!
//! Elle se compile en bibliothèque statique (`--crate-type=staticlib`) reliée
//! par un shim C, comme le Media Center Rust ; le point d'entrée C appelle
//! `pupdate_executer`. Un mode `--demo` (sous cfg demo) prouve toute la chaîne
//! de bout en bout sans réseau ni privilège : on fabrique une paire de clés de
//! test, on signe un catalogue, on le vérifie, on le parse, et on construit le
//! registre d'un parc d'exemple. Aucun dépôt n'est contacté, rien n'est publié.
#![allow(dead_code)]

pub mod apps;
pub mod coffre;
pub mod ffi;
pub mod format;
pub mod installateur;
pub mod jail;
pub mod moteur;
pub mod registre;
pub mod secours;
pub mod verif;

// ---- point d'entrée appelé par le shim C (phase ultérieure : UI/outil) ----
use core::ffi::{c_char, c_int};

#[no_mangle]
pub extern "C" fn pupdate_executer(_argc: c_int, _argv: *const *const c_char) -> c_int {
    // Pour l'instant : rien d'actif (lecture seule, pas encore d'UI câblée).
    // La vraie orchestration viendra avec PuppyUpdate (moteur) et l'UI.
    0
}

// ===========================================================================
//                         DÉMO DE BOUT EN BOUT
//  rustc --edition 2021 --cfg demo system/pupdate/pupdate.rs \
//        -C link-arg=crypto.o -C link-arg=monocypher.o -C link-arg=mced.o \
//        -o demo && ./demo
// ===========================================================================
#[cfg(demo)]
mod demonstration {
    use super::*;
    use registre::{construire, parser_catalogue, Categorie, Etat, Installe, MotifAbsence};

    extern "C" {
        fn crypto_ed25519_key_pair(secret: *mut u8, public: *mut u8, seed: *mut u8);
        fn crypto_ed25519_sign(sig: *mut u8, secret: *const u8, msg: *const u8, n: usize);
    }

    const CATALOGUE: &str = "\
# catalogue applications-puppy/puppyos — exemple de démonstration
catalogue=1
sequence=42
genere=2026-11-06T00:00:00Z
composant=dock
  categorie=systeme
  version=0.19
  fichier=Dock-0.19.puppy
  sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  obligatoire=oui
  min_os=0.19
composant=mediacenter
  categorie=utilisateur
  version=0.2
  fichier=MediaRust-0.2.puppy
  sha256=fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210
  obligatoire=non
";

    pub fn executer() {
        // 1. clé de test (en vrai : la clé des paquets de Dylan, hors de l'OS)
        let mut secret = [0u8; 64];
        let mut public = [0u8; 32];
        let mut seed = [42u8; 32];
        unsafe { crypto_ed25519_key_pair(secret.as_mut_ptr(), public.as_mut_ptr(), seed.as_mut_ptr()) };

        // 2. signer le catalogue (côté PC)
        let m = verif_message(CATALOGUE.as_bytes());
        let mut sig = [0u8; 64];
        unsafe { crypto_ed25519_sign(sig.as_mut_ptr(), secret.as_ptr(), m.as_ptr(), m.len()) };

        // 3. côté OS : vérifier AVANT de faire confiance
        let ok = verif::verifier_catalogue(CATALOGUE.as_bytes(), &sig, &public);
        println!("Signature du catalogue : {}", if ok { "VALIDE ✓" } else { "REFUSÉE ✗" });
        assert!(ok);

        // preuve : un octet modifié casse la vérification
        let mut falsifie = CATALOGUE.as_bytes().to_vec();
        falsifie[30] ^= 1;
        let casse = verif::verifier_catalogue(&falsifie, &sig, &public);
        println!("Catalogue falsifié (1 octet) : {}", if casse { "accepté ✗✗✗" } else { "rejeté ✓" });
        assert!(!casse);

        // 4. parser le catalogue vérifié
        let cat = parser_catalogue(CATALOGUE).expect("catalogue valide");
        println!("Catalogue : séquence {}, {} composant(s)", cat.sequence, cat.entrees.len());

        // 5. parc installé d'exemple
        let installes = vec![
            Installe { id: "dock".into(), nom: "Dock".into(), version: "0.18".into(), categorie: Categorie::Systeme },
            Installe { id: "mediacenter".into(), nom: "Media Center".into(), version: "0.2".into(), categorie: Categorie::Utilisateur },
            Installe { id: "bloc-notes".into(), nom: "Bloc-notes".into(), version: "1.0".into(), categorie: Categorie::Utilisateur },
        ];

        // 6. construire le registre (OS en 0.19)
        let fiches = construire(&installes, Some(&cat), "0.19", MotifAbsence::Inconnu);

        println!("\n  {:<14} {:<12} {:>8} {:>8}  état", "composant", "catégorie", "installé", "dispo");
        println!("  {}", "-".repeat(60));
        for f in &fiches {
            let cat_txt = match f.categorie { Categorie::Systeme => "système", Categorie::Utilisateur => "utilisateur" };
            let marque = match f.etat {
                Etat::Obligatoire => " ⚠ obligatoire",
                Etat::MajDisponible => " ↑",
                _ => "",
            };
            println!(
                "  {:<14} {:<12} {:>8} {:>8}  {}{}",
                f.id,
                cat_txt,
                f.version_installee.clone().unwrap_or_else(|| "—".into()),
                f.version_disponible.clone().unwrap_or_else(|| "—".into()),
                f.etat.libelle(),
                marque,
            );
        }

        // 7. contrôles attendus
        let get = |id: &str| fiches.iter().find(|x| x.id == id).unwrap();
        assert_eq!(get("dock").etat, Etat::Obligatoire);     // 0.18 -> 0.19 obligatoire
        assert_eq!(get("mediacenter").etat, Etat::AJour);    // 0.2 == 0.2
        assert_eq!(get("bloc-notes").etat, Etat::Installe);  // hors catalogue
        println!("\nTous les états attendus sont corrects ✓");
    }

    // même construction de message que verif.rs (préfixe de domaine + sha256)
    fn verif_message(texte: &[u8]) -> [u8; 42] {
        extern "C" { fn sha256(data: *const u8, n: usize, out: *mut u8); }
        let mut h = [0u8; 32];
        unsafe { sha256(texte.as_ptr(), texte.len(), h.as_mut_ptr()) };
        let mut m = [0u8; 42];
        m[..10].copy_from_slice(b"CATALOGUE1");
        m[10..].copy_from_slice(&h);
        m
    }
}

#[cfg(demo)]
fn main() {
    demonstration::executer();
}
