//! ffi.rs — pont C ABI vers le « cerveau » Rust de l'installateur confiné.
//!
//! Le binaire privilégié `puppy-autorite-paquets` (C, syscalls mandataires :
//! seccomp, openat2, abandon de privilèges) ne REFAIT pas la logique de
//! décision : il appelle ces fonctions, qui sont la version testée de `jail` et
//! `installateur`. On garde ainsi la décision en Rust (sûre, 65 tests) et on
//! réserve le C à ce pour quoi il est obligatoire (les appels système
//! privilégiés). Toutes ces fonctions sont PURES : aucune E/S, aucun privilège.
#![allow(dead_code)]

use core::ffi::{c_char, c_int};

use crate::coffre::{self, ErreurCoffre};
use crate::installateur::{admission, Admission, Forensic, Signature};
use crate::jail::{self, Genre, GenreAffiche, Limites};
use crate::secours::{self, MotifRefus, Verdict};

/// 0 = Programme, 1 = Thème, 2 = Icônes (même ordre que `racine_genre` en C).
fn genre_depuis(code: c_int) -> Option<Genre> {
    match code {
        0 => Some(Genre::Programme),
        1 => Some(Genre::Theme),
        2 => Some(Genre::Icones),
        _ => None,
    }
}

/// Lit un identifiant C (NUL-terminé) en &str, ou None si invalide/non-UTF8.
///
/// # Safety
/// `id` doit pointer sur une chaîne C valide NUL-terminée.
unsafe fn lire_id<'a>(id: *const c_char) -> Option<&'a str> {
    if id.is_null() {
        return None;
    }
    core::ffi::CStr::from_ptr(id).to_str().ok()
}

/// Écrit `s` (+ NUL) dans le tampon C `out`/`cap`. Renvoie true si ça tient.
///
/// # Safety
/// `out` doit pointer sur au moins `cap` octets inscriptibles.
unsafe fn ecrire(out: *mut c_char, cap: usize, s: &str) -> bool {
    let bytes = s.as_bytes();
    if out.is_null() || bytes.len() + 1 > cap {
        return false;
    }
    core::ptr::copy_nonoverlapping(bytes.as_ptr(), out as *mut u8, bytes.len());
    *out.add(bytes.len()) = 0;
    true
}

/// Calcule les trois chemins confinés (cible, précédent, stagiaire) pour
/// (genre, id). Renvoie 0 si OK (les tampons sont remplis), -1 si refusé
/// (id/genre invalide) ou si un tampon est trop court. Les chemins obtenus sont
/// garantis sous une racine autorisée (vérifiable ensuite par
/// `pupdate_chemin_autorise`).
///
/// # Safety
/// `id` chaîne C valide ; `cible`/`precedent`/`stagiaire` chacun ≥ `cap` octets.
#[no_mangle]
pub unsafe extern "C" fn pupdate_chemins(
    genre: c_int,
    id: *const c_char,
    cible: *mut c_char,
    precedent: *mut c_char,
    stagiaire: *mut c_char,
    cap: usize,
) -> c_int {
    let (Some(g), Some(idv)) = (genre_depuis(genre), lire_id(id)) else {
        return -1;
    };
    let c = match jail::chemins(g, idv) {
        Ok(c) => c,
        Err(_) => return -1,
    };
    if ecrire(cible, cap, &c.cible) && ecrire(precedent, cap, &c.precedent) && ecrire(stagiaire, cap, &c.stagiaire) {
        0
    } else {
        -1
    }
}

/// Chemin ISO (retour à la version d'origine) pour (genre, id). 0 si OK, -1 si
/// pas de miroir ISO pour ce genre, ou refus/tampon court.
///
/// # Safety
/// `id` chaîne C valide ; `iso` ≥ `cap` octets.
#[no_mangle]
pub unsafe extern "C" fn pupdate_chemin_iso(genre: c_int, id: *const c_char, iso: *mut c_char, cap: usize) -> c_int {
    let (Some(g), Some(idv)) = (genre_depuis(genre), lire_id(id)) else {
        return -1;
    };
    match jail::chemins(g, idv).ok().and_then(|c| c.iso) {
        Some(p) if ecrire(iso, cap, &p) => 0,
        _ => -1,
    }
}

/// LA muselière : ce chemin absolu (C) est-il autorisé à l'autorité confinée ?
/// 1 = oui (sous une racine d'apps, hors préfixe interdit), 0 = non.
///
/// # Safety
/// `chemin` doit être une chaîne C valide NUL-terminée.
#[no_mangle]
pub unsafe extern "C" fn pupdate_chemin_autorise(chemin: *const c_char) -> c_int {
    match lire_id(chemin) {
        Some(p) if jail::chemin_autorise(p) => 1,
        _ => 0,
    }
}

/// Anti-bombe, une entrée : chemin relatif (C) + taille décompressée, bornes par
/// défaut. 0 = acceptable, sinon le code de `RefusBombe` (>=1).
///
/// # Safety
/// `chemin` doit être une chaîne C valide NUL-terminée.
#[no_mangle]
pub unsafe extern "C" fn pupdate_verifier_entree(chemin: *const c_char, taille: u64) -> c_int {
    let Some(c) = lire_id(chemin) else { return code_bombe_illegal() };
    match jail::verifier_entree(c, taille, &Limites::DEFAUT) {
        Ok(()) => 0,
        Err(r) => code_bombe(r),
    }
}

/// Anti-bombe, cumul : nombre d'entrées, total décompressé, taille compressée.
/// 0 = acceptable, sinon le code de `RefusBombe`.
#[no_mangle]
pub extern "C" fn pupdate_verifier_cumul(nb_entrees: u32, total_decompresse: u64, taille_compressee: u64) -> c_int {
    match jail::verifier_cumul(nb_entrees, total_decompresse, taille_compressee, &Limites::DEFAUT) {
        Ok(()) => 0,
        Err(r) => code_bombe(r),
    }
}

/// Porte d'admission (règle de Piika). `signature` : 0 absente, 1 valide,
/// 2 fausse. `forensic` : 0 accepte, autre = refuse. `systeme` : 1 si le paquet
/// est de CONFIANCE système (venu du catalogue), 0 sinon. Renvoie :
/// 0 = admis, 1 = admis avec avertissement (app user non signée), 2 = refusé.
#[no_mangle]
pub extern "C" fn pupdate_admission(signature: c_int, forensic: c_int, systeme: c_int) -> c_int {
    let sig = match signature {
        1 => Signature::Valide,
        2 => Signature::Fausse,
        _ => Signature::Absente,
    };
    let f = if forensic == 0 { Forensic::Accepte } else { Forensic::Refuse };
    let a = if systeme == 1 { GenreAffiche::Systeme } else { GenreAffiche::Application };
    match admission(sig, f, a) {
        Admission::Admis => 0,
        Admission::AdmisAvertissement => 1,
        Admission::Refuse => 2,
    }
}

// ---------------------------------------------------------------------------
//  Coffre de chiffrement au repos (coffre.rs). Le lanceur de confiance (C)
//  descelle la clé au TPM puis appelle `pupdate_coffre_ouvrir` pour obtenir le
//  binaire en clair en mémoire ; `puppy-maj` appelle `pupdate_coffre_sceller`
//  lors de la rotation. Codes de retour :
//    0 OK ; 1 TropCourt ; 2 MauvaiseMagie ; 3 TailleAberrante ; 4 Downgrade ;
//    5 Authentification ; 6 TropGrand ; 7 tampon de sortie trop petit ;
//    8 argument nul.
// ---------------------------------------------------------------------------
fn code_coffre(e: ErreurCoffre) -> c_int {
    match e {
        ErreurCoffre::TropCourt => 1,
        ErreurCoffre::MauvaiseMagie => 2,
        ErreurCoffre::TailleAberrante => 3,
        ErreurCoffre::Downgrade => 4,
        ErreurCoffre::Authentification => 5,
        ErreurCoffre::TropGrand => 6,
    }
}

/// Ouvre un coffre : `cle` (32 o), `blob`/`blob_len`, `generation_min`. Écrit le
/// clair dans `out`/`out_cap`, sa longueur dans `*out_len`, la génération dans
/// `*out_gen`. 0 = OK.
///
/// # Safety
/// `cle` ≥ 32 o ; `blob` ≥ `blob_len` o ; `out` ≥ `out_cap` o ; `out_len` et
/// `out_gen` non nuls et inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn pupdate_coffre_ouvrir(
    cle: *const u8,
    blob: *const u8,
    blob_len: usize,
    generation_min: u32,
    out: *mut u8,
    out_cap: usize,
    out_len: *mut usize,
    out_gen: *mut u32,
) -> c_int {
    if cle.is_null() || blob.is_null() || out.is_null() || out_len.is_null() || out_gen.is_null() {
        return 8;
    }
    let mut k = [0u8; 32];
    core::ptr::copy_nonoverlapping(cle, k.as_mut_ptr(), 32);
    let blob_s = core::slice::from_raw_parts(blob, blob_len);
    match coffre::ouvrir(&k, blob_s, generation_min) {
        Ok(c) => {
            if c.clair.len() > out_cap {
                return 7;
            }
            core::ptr::copy_nonoverlapping(c.clair.as_ptr(), out, c.clair.len());
            *out_len = c.clair.len();
            *out_gen = c.generation;
            0
        }
        Err(e) => code_coffre(e),
    }
}

/// Dérive la clé de coffre (32 o -> `out_cle`, 32 o) depuis le secret de machine
/// (`secret`, 32 o), le nom d'application (`app`, chaîne C) et `generation`.
/// 0 = OK, 8 = argument nul.
///
/// # Safety
/// `secret` ≥ 32 o ; `app` chaîne C valide ; `out_cle` ≥ 32 o.
#[no_mangle]
pub unsafe extern "C" fn pupdate_coffre_deriver(
    secret: *const u8,
    app: *const c_char,
    generation: u32,
    out_cle: *mut u8,
) -> c_int {
    if secret.is_null() || out_cle.is_null() {
        return 8;
    }
    let Some(a) = lire_id(app) else { return 8 };
    let mut s = [0u8; 32];
    core::ptr::copy_nonoverlapping(secret, s.as_mut_ptr(), 32);
    let cle = coffre::deriver_cle(&s, a, generation);
    core::ptr::copy_nonoverlapping(cle.as_ptr(), out_cle, 32);
    0
}

/// Scelle `clair`/`clair_len` avec `cle` (32 o), `generation`, `nonce` (24 o).
/// Écrit le blob dans `out`/`out_cap`, sa longueur dans `*out_len`. 0 = OK.
///
/// # Safety
/// `cle` ≥ 32 o ; `nonce` ≥ 24 o ; `clair` ≥ `clair_len` o ; `out` ≥ `out_cap`
/// o ; `out_len` non nul.
#[no_mangle]
pub unsafe extern "C" fn pupdate_coffre_sceller(
    cle: *const u8,
    generation: u32,
    nonce: *const u8,
    clair: *const u8,
    clair_len: usize,
    out: *mut u8,
    out_cap: usize,
    out_len: *mut usize,
) -> c_int {
    if cle.is_null() || nonce.is_null() || out.is_null() || out_len.is_null() || (clair.is_null() && clair_len != 0) {
        return 8;
    }
    let mut k = [0u8; 32];
    core::ptr::copy_nonoverlapping(cle, k.as_mut_ptr(), 32);
    let mut nz = [0u8; 24];
    core::ptr::copy_nonoverlapping(nonce, nz.as_mut_ptr(), 24);
    let clair_s = if clair_len == 0 { &[][..] } else { core::slice::from_raw_parts(clair, clair_len) };
    match coffre::sceller(&k, generation, &nz, clair_s) {
        Ok(blob) => {
            if blob.len() > out_cap {
                return 7;
            }
            core::ptr::copy_nonoverlapping(blob.as_ptr(), out, blob.len());
            *out_len = blob.len();
            0
        }
        Err(e) => code_coffre(e),
    }
}

// ---------------------------------------------------------------------------
//  Console de secours (secours.rs). Le binaire privilégié puprecovery vérifie
//  la signature (Monocypher) puis demande ICI la décision de réinstallation.
//  Retour : 0 = réinstaller ; sinon motif :
//    1 IdInconnu ; 2 Vide ; 3 Bombe (bornes) ; 4 SignatureInvalide ;
//    5 EmpreinteNonConforme (signé ≠ épingle) ; 6 RetourArrière ; 8 arg nul.
// ---------------------------------------------------------------------------
fn code_secours(m: MotifRefus) -> c_int {
    match m {
        MotifRefus::IdInconnu => 1,
        MotifRefus::Vide => 2,
        MotifRefus::Bombe(_) => 3,
        MotifRefus::SignatureInvalide => 4,
        MotifRefus::EmpreinteNonConforme => 5,
        MotifRefus::RetourArriere => 6,
    }
}

/// Décide si la console doit réinstaller l'app `id` depuis sa copie dorée.
/// `signature_valide` : 1 si la signature Ed25519 a été vérifiée. `empreinte` :
/// SHA-256 (32 o) calculé sur la copie dorée. 0 = réinstaller, sinon motif.
///
/// # Safety
/// `id` chaîne C valide ; `empreinte` ≥ 32 o.
#[no_mangle]
pub unsafe extern "C" fn pupdate_secours_analyser(
    id: *const c_char,
    signature_valide: c_int,
    empreinte: *const u8,
    generation: u32,
    taille: u64,
) -> c_int {
    let (Some(idv), false) = (lire_id(id), empreinte.is_null()) else {
        return 8;
    };
    let mut emp = [0u8; 32];
    core::ptr::copy_nonoverlapping(empreinte, emp.as_mut_ptr(), 32);
    match secours::analyser_id(idv, signature_valide == 1, &emp, generation, taille) {
        Verdict::Reinstaller => 0,
        Verdict::Refuser(m) => code_secours(m),
    }
}

/// Pré-décision AVANT tout parsing du paquet : ne regarde que l'identifiant, la
/// taille, l'empreinte (vs épingle) et la génération ; PAS la signature (qui
/// exige de parser le paquet). 0 = « peut parser + vérifier la signature »,
/// sinon motif (jamais 4 SignatureInvalide ici). Sert à refuser une copie dorée
/// altérée AVANT de la confier au parseur C (réduction de surface d'attaque).
/// `pupdate_secours_analyser` reste le juge final (il revérifie tout).
///
/// # Safety
/// `id` chaîne C valide ; `empreinte` ≥ 32 o.
#[no_mangle]
pub unsafe extern "C" fn pupdate_secours_pre_analyser(
    id: *const c_char,
    empreinte: *const u8,
    generation: u32,
    taille: u64,
) -> c_int {
    let (Some(idv), false) = (lire_id(id), empreinte.is_null()) else {
        return 8;
    };
    let mut emp = [0u8; 32];
    core::ptr::copy_nonoverlapping(empreinte, emp.as_mut_ptr(), 32);
    match secours::verdict_avant_parsing_id(idv, &emp, generation, taille) {
        Verdict::Reinstaller => 0,
        Verdict::Refuser(m) => code_secours(m),
    }
}

/// Nombre de slogans du dico qui tourne.
#[no_mangle]
pub extern "C" fn pupdate_slogans_nombre() -> u32 {
    crate::slogans::nombre() as u32
}

/// Écrit le slogan d'index `i` (en rotation) dans `out`/`cap` (+ NUL).
/// Renvoie la longueur écrite (sans le NUL), ou -1 si le tampon est trop petit
/// ou nul.
///
/// # Safety
/// `out` doit pointer sur au moins `cap` octets inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn pupdate_slogan(i: u32, out: *mut c_char, cap: usize) -> c_int {
    let s = crate::slogans::slogan(i as usize);
    if ecrire(out, cap, s) {
        s.len() as c_int
    } else {
        -1
    }
}

// codes stables de RefusBombe pour le C (1..=7)
fn code_bombe(r: jail::RefusBombe) -> c_int {
    use jail::RefusBombe::*;
    match r {
        CheminIllegal => 1,
        CheminTropLong => 2,
        TropProfond => 3,
        FichierTropGros => 4,
        TropDeFichiers => 5,
        TropGros => 6,
        RatioExcessif => 7,
    }
}
fn code_bombe_illegal() -> c_int {
    1
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    #[test]
    fn chemins_via_ffi() {
        let id = CString::new("bloc-notes").unwrap();
        let (mut c, mut p, mut s) = ([0 as c_char; 256], [0 as c_char; 256], [0 as c_char; 256]);
        let r = unsafe { pupdate_chemins(0, id.as_ptr(), c.as_mut_ptr(), p.as_mut_ptr(), s.as_mut_ptr(), 256) };
        assert_eq!(r, 0);
        let lu = |b: &[c_char]| unsafe { core::ffi::CStr::from_ptr(b.as_ptr()).to_str().unwrap().to_string() };
        assert_eq!(lu(&c), "/system/programmes/bloc-notes");
        assert_eq!(lu(&p), "/system/programmes/bloc-notes.precedent");
        assert_eq!(lu(&s), "/system/programmes/bloc-notes.part");
    }

    #[test]
    fn id_invalide_via_ffi() {
        let id = CString::new("../evil").unwrap();
        let (mut c, mut p, mut s) = ([0 as c_char; 256], [0 as c_char; 256], [0 as c_char; 256]);
        let r = unsafe { pupdate_chemins(0, id.as_ptr(), c.as_mut_ptr(), p.as_mut_ptr(), s.as_mut_ptr(), 256) };
        assert_eq!(r, -1);
    }

    #[test]
    fn muselière_via_ffi() {
        let bon = CString::new("/system/programmes/x/bin/app").unwrap();
        let mauvais = CString::new("/system/versions/0.19/bin/puppy-bureau").unwrap();
        assert_eq!(unsafe { pupdate_chemin_autorise(bon.as_ptr()) }, 1);
        assert_eq!(unsafe { pupdate_chemin_autorise(mauvais.as_ptr()) }, 0);
    }

    #[test]
    fn admission_via_ffi() {
        // système non signé -> 2 (refusé)
        assert_eq!(pupdate_admission(0, 0, 1), 2);
        // user non signé -> 1 (avertissement)
        assert_eq!(pupdate_admission(0, 0, 0), 1);
        // falsifié -> 2
        assert_eq!(pupdate_admission(2, 0, 0), 2);
        // signé valide -> 0
        assert_eq!(pupdate_admission(1, 0, 1), 0);
    }

    #[test]
    fn anti_bombe_via_ffi() {
        let bon = CString::new("bin/app").unwrap();
        let mauvais = CString::new("a/../b").unwrap();
        assert_eq!(unsafe { pupdate_verifier_entree(bon.as_ptr(), 1000) }, 0);
        assert_eq!(unsafe { pupdate_verifier_entree(mauvais.as_ptr(), 1000) }, 1); // CheminIllegal
        assert_eq!(pupdate_verifier_cumul(1, 100 * 1024 * 1024, 1024), 7); // RatioExcessif
    }

    #[test]
    fn coffre_via_ffi() {
        let cle = [7u8; 32];
        let nonce = [1u8; 24];
        let clair = b"binaire privilegie fictif";
        // sceller
        let mut blob = [0u8; 256];
        let mut nblob = 0usize;
        let r = unsafe {
            pupdate_coffre_sceller(cle.as_ptr(), 3, nonce.as_ptr(), clair.as_ptr(), clair.len(), blob.as_mut_ptr(), blob.len(), &mut nblob)
        };
        assert_eq!(r, 0);
        // ouvrir
        let mut out = [0u8; 256];
        let (mut nout, mut gen) = (0usize, 0u32);
        let r = unsafe {
            pupdate_coffre_ouvrir(cle.as_ptr(), blob.as_ptr(), nblob, 1, out.as_mut_ptr(), out.len(), &mut nout, &mut gen)
        };
        assert_eq!(r, 0);
        assert_eq!(gen, 3);
        assert_eq!(&out[..nout], clair);
        // mauvaise clé -> 5 (Authentification)
        let fausse = [8u8; 32];
        let r = unsafe {
            pupdate_coffre_ouvrir(fausse.as_ptr(), blob.as_ptr(), nblob, 1, out.as_mut_ptr(), out.len(), &mut nout, &mut gen)
        };
        assert_eq!(r, 5);
        // retour arrière -> 4 (Downgrade)
        let r = unsafe {
            pupdate_coffre_ouvrir(cle.as_ptr(), blob.as_ptr(), nblob, 9, out.as_mut_ptr(), out.len(), &mut nout, &mut gen)
        };
        assert_eq!(r, 4);
        // tampon de sortie trop petit -> 7
        let mut petit = [0u8; 4];
        let r = unsafe {
            pupdate_coffre_ouvrir(cle.as_ptr(), blob.as_ptr(), nblob, 1, petit.as_mut_ptr(), petit.len(), &mut nout, &mut gen)
        };
        assert_eq!(r, 7);
    }

    #[test]
    fn secours_via_ffi() {
        let id = CString::new("navigateur").unwrap(); // hors des deux apps
        let emp = [0u8; 32];
        assert_eq!(unsafe { pupdate_secours_analyser(id.as_ptr(), 1, emp.as_ptr(), 1, 10) }, 1); // IdInconnu
        // app connue, non signée -> 4
        let pa = CString::new("paquets-autorite").unwrap();
        assert_eq!(unsafe { pupdate_secours_analyser(pa.as_ptr(), 0, emp.as_ptr(), 1, 10) }, 4);
        // app connue, signée mais empreinte ≠ épingle (zéro par défaut) -> 5
        let vraie = [0x42u8; 32];
        assert_eq!(unsafe { pupdate_secours_analyser(pa.as_ptr(), 1, vraie.as_ptr(), 1, 10) }, 5);
    }

    #[test]
    fn secours_pre_analyser_via_ffi() {
        let emp = [0u8; 32];
        // id hors des deux apps -> 1 (IdInconnu), sans jamais parser
        let id = CString::new("navigateur").unwrap();
        assert_eq!(unsafe { pupdate_secours_pre_analyser(id.as_ptr(), emp.as_ptr(), 1, 10) }, 1);
        // app connue, empreinte ≠ épingle (zéro par défaut) -> 5, AVANT tout parsing
        let pa = CString::new("paquets-autorite").unwrap();
        let vraie = [0x42u8; 32];
        assert_eq!(unsafe { pupdate_secours_pre_analyser(pa.as_ptr(), vraie.as_ptr(), 1, 10) }, 5);
        // taille nulle -> 2 (Vide)
        assert_eq!(unsafe { pupdate_secours_pre_analyser(pa.as_ptr(), emp.as_ptr(), 1, 0) }, 2);
        // empreinte nulle = argument nul -> 8
        assert_eq!(unsafe { pupdate_secours_pre_analyser(pa.as_ptr(), core::ptr::null(), 1, 10) }, 8);
    }
}
