//! clavier_securise.rs — CLAVIER ULTRA-PROTÉGÉ pendant la saisie des mots de passe.
//!
//! Demande Piika (06/11) : pendant la saisie d'un mot de passe (session,
//! déverrouillage, et désormais les invites de déchiffrement de l'explorateur),
//! le clavier doit être protégé de la **falsification** et du **piratage**,
//! qu'il soit **local** ou **réseau**. Même esprit que `puppy-deverrouille` /
//! `puppy-verrou`.
//!
//! Ce module est le CERVEAU (pur, testé) de cette protection. Il ne touche pas
//! le matériel : il décide quelles frappes accepter et quelles obligations
//! imposer à l'appelant (le C fait la capture exclusive, coupe l'écho, etc.).
//!
//! Trois principes :
//!
//! 1. UNE SEULE SOURCE DE CONFIANCE. Au début de la saisie protégée, on fige la
//!    liste des claviers physiques déjà présents et approuvés. Pendant la
//!    saisie, on n'accepte QUE leurs frappes.
//!
//! 2. TOUT LE RESTE EST REJETÉ. Un événement injecté par logiciel (uinput,
//!    X-test…), une entrée venue du réseau (KVM distant, prise de contrôle), ou
//!    un périphérique branché À CHAUD pendant la saisie (BadUSB, keylogger
//!    matériel qui se fait passer pour un clavier) → frappe refusée, motif donné.
//!
//! 3. RIEN NE FUIT. À l'ouverture : capture exclusive, écho coupé, journalisation
//!    coupée. À la fermeture (ou annulation) : le tampon du mot de passe est
//!    effacé (zéroïsation), la capture rendue.
//!
//! Module PUR : aucune E/S, aucun privilège, jamais de panique.
#![allow(dead_code)]

/// Provenance d'une frappe pendant la saisie protégée.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    /// Un clavier physique présent ET approuvé au démarrage de la saisie.
    ClavierInitialApprouve,
    /// Un périphérique d'entrée branché À CHAUD pendant la saisie (suspect :
    /// BadUSB, keylogger qui s'annonce comme clavier) → jamais accepté en cours.
    PeripheriqueChaudBranche,
    /// Événement synthétique injecté par logiciel (uinput, X-test, auto-type…).
    EvenementSynthetique,
    /// Entrée venue du réseau / d'un canal distant (KVM, prise de contrôle).
    EntreeReseau,
    /// Provenance inconnue / non prouvée.
    Inconnue,
}

/// Décision sur une frappe.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    /// Frappe acceptée (source de confiance, figée au démarrage).
    Accepter,
    /// Frappe refusée, avec le motif (on dit pourquoi, sans mentir).
    Rejeter { motif: &'static str },
}

/// Décide d'une frappe pendant la saisie protégée d'un mot de passe.
/// SEUL un clavier initial approuvé est accepté ; tout le reste est rejeté.
pub fn pendant_saisie_protegee(src: Source) -> Verdict {
    match src {
        Source::ClavierInitialApprouve => Verdict::Accepter,
        Source::PeripheriqueChaudBranche => Verdict::Rejeter {
            motif: "peripherique branche pendant la saisie : non approuve, frappe ignoree",
        },
        Source::EvenementSynthetique => Verdict::Rejeter {
            motif: "evenement synthetique (injection logicielle) : refuse",
        },
        Source::EntreeReseau => Verdict::Rejeter {
            motif: "entree distante (reseau) : refusee pendant une saisie de mot de passe",
        },
        Source::Inconnue => Verdict::Rejeter {
            motif: "provenance non prouvee : refusee",
        },
    }
}

/// Vrai si cette source est acceptée pendant la saisie protégée.
pub fn accepter(src: Source) -> bool {
    matches!(pendant_saisie_protegee(src), Verdict::Accepter)
}

/// Obligations que l'appelant (C) DOIT honorer au démarrage de la saisie
/// protégée. Toutes vraies : c'est un contrat, pas une option.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Ouverture {
    /// Capturer les claviers approuvés en EXCLUSIF (EVIOCGRAB) : aucun autre
    /// processus ne voit les frappes.
    pub capture_exclusive: bool,
    /// Couper l'écho (rien ne s'affiche en clair).
    pub echo_coupe: bool,
    /// Couper toute journalisation des frappes.
    pub journalisation_coupee: bool,
    /// Figer la liste des périphériques d'entrée approuvés (tout nouveau est suspect).
    pub figer_peripheriques: bool,
    /// Refuser toute source d'entrée réseau/distante pendant la saisie.
    pub refuser_entree_reseau: bool,
}

/// Le contrat d'ouverture : TOUT est imposé.
pub fn au_demarrage() -> Ouverture {
    Ouverture {
        capture_exclusive: true,
        echo_coupe: true,
        journalisation_coupee: true,
        figer_peripheriques: true,
        refuser_entree_reseau: true,
    }
}

/// Obligations à la fin de la saisie (validée, annulée, ou expirée). Toutes vraies.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Fermeture {
    /// Effacer (zéroïser) le tampon du mot de passe, quoi qu'il arrive.
    pub effacer_tampon: bool,
    /// Rendre la capture exclusive des claviers.
    pub rendre_capture: bool,
    /// Rétablir l'écho.
    pub retablir_echo: bool,
}

/// Le contrat de fermeture : on efface TOUJOURS le tampon, on rend la capture.
pub fn a_la_fermeture() -> Fermeture {
    Fermeture {
        effacer_tampon: true,
        rendre_capture: true,
        retablir_echo: true,
    }
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seul_le_clavier_initial_est_accepte() {
        assert_eq!(pendant_saisie_protegee(Source::ClavierInitialApprouve), Verdict::Accepter);
        assert!(accepter(Source::ClavierInitialApprouve));
    }

    #[test]
    fn tout_le_reste_est_refuse_avec_motif() {
        for src in [
            Source::PeripheriqueChaudBranche,
            Source::EvenementSynthetique,
            Source::EntreeReseau,
            Source::Inconnue,
        ] {
            match pendant_saisie_protegee(src) {
                Verdict::Rejeter { motif } => assert!(!motif.is_empty()),
                Verdict::Accepter => panic!("{src:?} n'aurait jamais du etre accepte"),
            }
            assert!(!accepter(src));
        }
    }

    #[test]
    fn injection_logicielle_jamais_acceptee() {
        // le cœur de la protection anti-piratage local
        assert!(!accepter(Source::EvenementSynthetique));
    }

    #[test]
    fn entree_reseau_jamais_acceptee() {
        // le cœur de la protection anti-piratage réseau
        assert!(!accepter(Source::EntreeReseau));
    }

    #[test]
    fn peripherique_branche_a_chaud_est_suspect() {
        // anti-falsification : un clavier qui apparaît pendant la saisie n'est
        // pas de confiance (BadUSB, keylogger déguisé)
        assert!(!accepter(Source::PeripheriqueChaudBranche));
    }

    #[test]
    fn contrat_ouverture_tout_impose() {
        let o = au_demarrage();
        assert!(o.capture_exclusive);
        assert!(o.echo_coupe);
        assert!(o.journalisation_coupee);
        assert!(o.figer_peripheriques);
        assert!(o.refuser_entree_reseau);
    }

    #[test]
    fn contrat_fermeture_efface_toujours_le_tampon() {
        let f = a_la_fermeture();
        assert!(f.effacer_tampon); // jamais de fuite du mot de passe
        assert!(f.rendre_capture);
        assert!(f.retablir_echo);
    }
}
