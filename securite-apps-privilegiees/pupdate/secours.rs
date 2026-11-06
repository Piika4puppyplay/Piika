//! secours.rs — cœur de décision de la CONSOLE DE SECOURS (puprecovery).
//!
//! Quand les deux applications privilégiées (paquets-autorite, puppy-maj) ne
//! peuvent plus s'ouvrir (coffre illisible, clés perdues, binaire abîmé), le
//! lanceur REFUSE de les lancer (puppy-lanceur, refus pur). La réparation est
//! confiée à cette console séparée et cloisonnée, qui NE MANIPULE AUCUNE CLÉ et
//! n'a donc AUCUN pouvoir de déchiffrage hors TPM (pas de surface d'attaque « à
//! grand pouvoir », choix de Piika 06/11) : elle se contente de RÉINSTALLER
//! proprement les deux apps à partir d'une COPIE DORÉE signée, embarquée.
//!
//! « MÊME PUPPYOS NE FAIT PAS CONFIANCE À SON CRÉATEUR » : le créateur peut se
//! faire pirater, donc une signature valide est NÉCESSAIRE mais PAS SUFFISANTE.
//! La console porte, en dur, l'EMPREINTE ÉPINGLÉE (SHA-256 connu-bon) de chaque
//! app. Un paquet même parfaitement signé mais dont le contenu ne correspond
//! pas à l'épingle est REFUSÉ. La seule façon de changer une épingle est de
//! mettre à jour la console elle-même (signée, fs-verity, démarrage mesuré) :
//! la confiance est ancrée dans l'intégrité de la console, pas dans la seule
//! signature. Épingle à zéro = ne correspond à rien = refus (défaut sûr).
//!
//! La console n'accepte AUCUN fichier entrant : elle ne lit que sa propre copie
//! dorée (de confiance) et ne touche QUE ses deux apps. Pas de parseur exposé à
//! l'extérieur → pas de débordement/bombe venus de l'extérieur. On garde malgré
//! tout des bornes (anti-bombe de jail) : défense en profondeur sur la copie
//! dorée elle-même.
//!
//! Module PUR : aucune E/S, aucun privilège. Le binaire C (puprecovery.c) fait
//! les appels système (lecture read-only de la copie dorée, pose A/B) et
//! délègue toute la DÉCISION ici (testée).
#![allow(dead_code)]

use crate::jail::{self, RefusBombe};

/// Une application réparable, avec son empreinte connue-bonne épinglée.
pub struct AppDoree {
    pub id: &'static str,
    /// SHA-256 attendu du paquet .puppy connu-bon. Zéro = non renseignée
    /// (refus par défaut, tant que le vrai condensat n'est pas scellé au build).
    pub empreinte_epinglee: [u8; 32],
    /// Version minimale acceptée (anti-retour-arrière).
    pub generation_min: u32,
}

/// Les DEUX seules applications que la console sait réparer. Rien d'autre.
/// Les empreintes réelles sont renseignées à la compilation de la console (au
/// moment où les copies dorées existent) ; à zéro ici, la console refuse tout,
/// ce qui est le défaut sûr.
pub static APPS: &[AppDoree] = &[
    AppDoree { id: "paquets-autorite", empreinte_epinglee: [0u8; 32], generation_min: 0 },
    AppDoree { id: "puppy-maj", empreinte_epinglee: [0u8; 32], generation_min: 0 },
];

#[derive(Debug, PartialEq, Eq)]
pub enum MotifRefus {
    IdInconnu,            // pas une des deux apps : la console n'y touche pas
    Vide,                 // copie dorée absente
    Bombe(RefusBombe),    // bornes dépassées (défense en profondeur)
    SignatureInvalide,    // la signature Ed25519 ne tient pas
    EmpreinteNonConforme, // signé mais ≠ épingle : on ne croit pas le créateur sur parole
    RetourArriere,        // version plus vieille que le minimum
}

#[derive(Debug, PartialEq, Eq)]
pub enum Verdict {
    Reinstaller,
    Refuser(MotifRefus),
}

/// Comparaison à temps constant (anti-oracle temporel sur l'empreinte).
fn egal_constant(a: &[u8; 32], b: &[u8; 32]) -> bool {
    let mut d = 0u8;
    for i in 0..32 {
        d |= a[i] ^ b[i];
    }
    d == 0
}

/// Retrouve une app épinglée par son identifiant (sinon None).
pub fn app_epinglee(id: &str) -> Option<&'static AppDoree> {
    APPS.iter().find(|a| a.id == id)
}

/// DÉCISION de réparation pour une app, à partir de :
///  - `app` : l'entrée épinglée (de `app_epinglee`) ;
///  - `signature_valide` : la signature Ed25519 de la copie dorée a déjà été
///    vérifiée (par verif.rs / FFI Monocypher) ;
///  - `empreinte` : le SHA-256 réellement calculé sur la copie dorée ;
///  - `generation` : la version de la copie dorée ;
///  - `taille` : la taille du paquet (bornée, défense en profondeur).
///
/// Ordre : bornes → signature → ÉPINGLE → anti-retour. L'épingle passe APRÈS la
/// signature mais reste décisive : un paquet signé qui ne correspond pas à
/// l'empreinte connue-bonne est refusé (créateur potentiellement piraté).
pub fn analyser(
    app: &AppDoree,
    signature_valide: bool,
    empreinte: &[u8; 32],
    generation: u32,
    taille: u64,
) -> Verdict {
    if taille == 0 {
        return Verdict::Refuser(MotifRefus::Vide);
    }
    let lim = jail::Limites::DEFAUT;
    if taille > lim.taille_fichier_max {
        return Verdict::Refuser(MotifRefus::Bombe(RefusBombe::FichierTropGros));
    }
    if !signature_valide {
        return Verdict::Refuser(MotifRefus::SignatureInvalide);
    }
    // on ne fait pas confiance au créateur : le contenu DOIT valoir l'épingle
    if !egal_constant(empreinte, &app.empreinte_epinglee) {
        return Verdict::Refuser(MotifRefus::EmpreinteNonConforme);
    }
    if generation < app.generation_min {
        return Verdict::Refuser(MotifRefus::RetourArriere);
    }
    Verdict::Reinstaller
}

/// Décision par identifiant : refuse d'emblée tout id hors des deux apps.
pub fn analyser_id(id: &str, signature_valide: bool, empreinte: &[u8; 32], generation: u32, taille: u64) -> Verdict {
    match app_epinglee(id) {
        Some(app) => analyser(app, signature_valide, empreinte, generation, taille),
        None => Verdict::Refuser(MotifRefus::IdInconnu),
    }
}

/// Verdict de la phase AVANT tout parsing structurel du paquet .puppy.
///
/// Durcissement « surface d'attaque du recovery » (Piika 06/11) : le parseur de
/// paquets (en C) ne doit JAMAIS toucher des octets qui ne valent pas déjà
/// l'épingle connue-bonne. Cette phase ne regarde QUE des données SÛRES, sans
/// parseur : identifiant, taille, empreinte SHA-256 (calculée en flux par un
/// simple hachage) et génération (lue dans un petit fichier `.gen` à part). La
/// signature n'est PAS encore regardée (elle exige de parser le paquet).
///
/// Un fichier doré altéré est ainsi rejeté par une comparaison de hachage à
/// temps constant, AVANT le moindre octet de parsing structurel. La signature
/// reste vérifiée ensuite, et `analyser` demeure le juge final (il revalide
/// tout, signature comprise) : ceci est une barrière EN PLUS, pas à la place.
///
/// `Reinstaller` signifie seulement « peut passer au parsing + vérif
/// signature », jamais « réinstalle sans vérifier la signature ».
pub fn verdict_avant_parsing(
    app: &AppDoree,
    empreinte: &[u8; 32],
    generation: u32,
    taille: u64,
) -> Verdict {
    if taille == 0 {
        return Verdict::Refuser(MotifRefus::Vide);
    }
    let lim = jail::Limites::DEFAUT;
    if taille > lim.taille_fichier_max {
        return Verdict::Refuser(MotifRefus::Bombe(RefusBombe::FichierTropGros));
    }
    // on ne fait pas confiance au créateur : le contenu DOIT valoir l'épingle,
    // et on le vérifie AVANT de parser quoi que ce soit.
    if !egal_constant(empreinte, &app.empreinte_epinglee) {
        return Verdict::Refuser(MotifRefus::EmpreinteNonConforme);
    }
    if generation < app.generation_min {
        return Verdict::Refuser(MotifRefus::RetourArriere);
    }
    Verdict::Reinstaller
}

/// Pré-verdict par identifiant (refuse tout id hors des deux apps).
pub fn verdict_avant_parsing_id(id: &str, empreinte: &[u8; 32], generation: u32, taille: u64) -> Verdict {
    match app_epinglee(id) {
        Some(app) => verdict_avant_parsing(app, empreinte, generation, taille),
        None => Verdict::Refuser(MotifRefus::IdInconnu),
    }
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    fn app(pin: u8, gmin: u32) -> AppDoree {
        AppDoree { id: "paquets-autorite", empreinte_epinglee: [pin; 32], generation_min: gmin }
    }

    #[test]
    fn bonne_copie_reinstalle() {
        let a = app(0xAB, 3);
        assert_eq!(analyser(&a, true, &[0xAB; 32], 3, 10_000), Verdict::Reinstaller);
        assert_eq!(analyser(&a, true, &[0xAB; 32], 7, 10_000), Verdict::Reinstaller);
    }

    #[test]
    fn non_signe_refuse() {
        let a = app(0xAB, 1);
        assert_eq!(analyser(&a, false, &[0xAB; 32], 3, 10_000), Verdict::Refuser(MotifRefus::SignatureInvalide));
    }

    #[test]
    fn signe_mais_hors_epingle_refuse() {
        // le cœur du « ne pas croire le créateur » : signé mais ≠ empreinte connue
        let a = app(0xAB, 1);
        assert_eq!(analyser(&a, true, &[0xCD; 32], 3, 10_000), Verdict::Refuser(MotifRefus::EmpreinteNonConforme));
    }

    #[test]
    fn retour_arriere_refuse() {
        let a = app(0xAB, 5);
        assert_eq!(analyser(&a, true, &[0xAB; 32], 4, 10_000), Verdict::Refuser(MotifRefus::RetourArriere));
    }

    #[test]
    fn vide_refuse() {
        let a = app(0xAB, 1);
        assert_eq!(analyser(&a, true, &[0xAB; 32], 3, 0), Verdict::Refuser(MotifRefus::Vide));
    }

    #[test]
    fn trop_gros_refuse() {
        let a = app(0xAB, 1);
        let gros = jail::Limites::DEFAUT.taille_fichier_max + 1;
        assert_eq!(analyser(&a, true, &[0xAB; 32], 3, gros), Verdict::Refuser(MotifRefus::Bombe(RefusBombe::FichierTropGros)));
    }

    #[test]
    fn id_inconnu_refuse() {
        // la console ne touche QUE ses deux apps
        assert_eq!(analyser_id("navigateur", true, &[0u8; 32], 1, 10), Verdict::Refuser(MotifRefus::IdInconnu));
    }

    #[test]
    fn deux_apps_connues() {
        assert!(app_epinglee("paquets-autorite").is_some());
        assert!(app_epinglee("puppy-maj").is_some());
        assert!(app_epinglee("autre").is_none());
    }

    #[test]
    fn epingle_zero_refuse_par_defaut() {
        // table livrée (épingle à zéro) : un vrai paquet (empreinte non nulle)
        // est refusé tant que la vraie épingle n'est pas scellée au build.
        let pa = app_epinglee("paquets-autorite").unwrap();
        assert_eq!(analyser(pa, true, &[0x99; 32], 1, 10_000), Verdict::Refuser(MotifRefus::EmpreinteNonConforme));
    }

    // ---- phase AVANT parsing (durcissement surface d'attaque) ----

    #[test]
    fn avant_parsing_laisse_passer_si_epingle_ok() {
        // empreinte = épingle, taille et génération correctes -> peut parser
        let a = app(0xAB, 3);
        assert_eq!(verdict_avant_parsing(&a, &[0xAB; 32], 5, 10_000), Verdict::Reinstaller);
    }

    #[test]
    fn avant_parsing_refuse_hors_epingle_sans_parser() {
        // le cœur : un doré altéré (≠ épingle) est rejeté AVANT tout parsing,
        // sans que la signature n'ait même besoin d'être regardée.
        let a = app(0xAB, 1);
        assert_eq!(
            verdict_avant_parsing(&a, &[0xCD; 32], 3, 10_000),
            Verdict::Refuser(MotifRefus::EmpreinteNonConforme)
        );
    }

    #[test]
    fn avant_parsing_refuse_vide_et_trop_gros() {
        let a = app(0xAB, 1);
        assert_eq!(verdict_avant_parsing(&a, &[0xAB; 32], 3, 0), Verdict::Refuser(MotifRefus::Vide));
        let gros = jail::Limites::DEFAUT.taille_fichier_max + 1;
        assert_eq!(
            verdict_avant_parsing(&a, &[0xAB; 32], 3, gros),
            Verdict::Refuser(MotifRefus::Bombe(RefusBombe::FichierTropGros))
        );
    }

    #[test]
    fn avant_parsing_refuse_retour_arriere() {
        let a = app(0xAB, 5);
        assert_eq!(
            verdict_avant_parsing(&a, &[0xAB; 32], 4, 10_000),
            Verdict::Refuser(MotifRefus::RetourArriere)
        );
    }

    #[test]
    fn avant_parsing_id_inconnu_refuse() {
        assert_eq!(
            verdict_avant_parsing_id("navigateur", &[0u8; 32], 1, 10),
            Verdict::Refuser(MotifRefus::IdInconnu)
        );
    }

    #[test]
    fn avant_parsing_puis_analyser_meme_decision() {
        // cohérence : si le pré-verdict laisse passer et la signature est bonne,
        // le juge final (analyser) réinstalle ; si le pré-verdict refuse sur
        // l'épingle, analyser refuse aussi sur l'épingle (barrière redondante).
        let a = app(0xAB, 2);
        assert_eq!(verdict_avant_parsing(&a, &[0xAB; 32], 3, 500), Verdict::Reinstaller);
        assert_eq!(analyser(&a, true, &[0xAB; 32], 3, 500), Verdict::Reinstaller);
        assert_eq!(verdict_avant_parsing(&a, &[0x11; 32], 3, 500), Verdict::Refuser(MotifRefus::EmpreinteNonConforme));
        assert_eq!(analyser(&a, true, &[0x11; 32], 3, 500), Verdict::Refuser(MotifRefus::EmpreinteNonConforme));
    }
}
