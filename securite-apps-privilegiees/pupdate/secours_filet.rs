//! secours_filet.rs — le FILET DE SECOURS étagé.
//!
//! « Des fois même le recovery peut tomber en rade, donc une solution de sortie
//! est nécessaire » (Piika 06/11). On empile des couches de récupération : quand
//! l'une échoue, on descend vers une couche PLUS SIMPLE et PLUS DE CONFIANCE que
//! celle qu'elle rattrape. Chaque étage dépend de moins de choses que le
//! précédent, pour qu'il reste toujours une porte de sortie.
//!
//!   1. ConsoleDansOS   : la console `puprecovery`, depuis l'OS démarré.
//!                        Dépend de : OS qui démarre + lanceur.
//!   2. RecoveryIso     : le recovery embarqué dans l'ISO (hors-ligne), qui
//!                        répare sans démarrer l'OS (voir `secours_iso.rs`).
//!                        Dépend de : pouvoir booter l'ISO.
//!   3. ReinstallSignee : dernier recours, réinstallation propre depuis l'ISO
//!                        signée (mot de passe conservé pour préserver les
//!                        données ; format total seulement sur choix explicite).
//!                        Dépend de : l'ISO signée elle-même.
//!   4. PlusDeFilet     : tout a échoué — on le DIT clairement, on ne casse rien,
//!                        on ne bascule jamais en clair ni en mode dégradé muet.
//!
//! Module PUR : aucune E/S. La décision « quelle couche essayer maintenant » est
//! ici (testée) ; l'orchestration réelle est ailleurs.
#![allow(dead_code)]

/// Les couches du filet, de la plus riche (dépend du plus de choses) à la plus
/// fiable (dépend du moins).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Couche {
    ConsoleDansOS,
    RecoveryIso,
    ReinstallSignee,
    PlusDeFilet,
}

impl Couche {
    /// La couche immédiatement en dessous (plus simple, plus de confiance).
    fn dessous(self) -> Couche {
        match self {
            Couche::ConsoleDansOS => Couche::RecoveryIso,
            Couche::RecoveryIso => Couche::ReinstallSignee,
            Couche::ReinstallSignee => Couche::PlusDeFilet,
            Couche::PlusDeFilet => Couche::PlusDeFilet, // plancher : on y reste
        }
    }

    /// Libellé court et humain (la police bitmap n'a pas « œ » : on l'évite).
    pub fn libelle(self) -> &'static str {
        match self {
            Couche::ConsoleDansOS => "console de secours (dans PuppyOS)",
            Couche::RecoveryIso => "recovery de l'ISO (repare sans demarrer)",
            Couche::ReinstallSignee => "reinstallation propre depuis l'ISO signee",
            Couche::PlusDeFilet => "plus de filet : intervention manuelle requise",
        }
    }
}

/// Quelle couche essayer ensuite ? Si la couche actuelle a échoué, on descend
/// d'un cran ; sinon on reste sur la couche actuelle (elle travaille encore).
/// On ne REMONTE jamais tout seul : remonter, c'est redémarrer depuis une couche
/// qui dépend de plus de choses, ce qui n'a de sens que sur décision explicite.
pub fn couche_suivante(actuelle: Couche, a_echoue: bool) -> Couche {
    if a_echoue {
        actuelle.dessous()
    } else {
        actuelle
    }
}

/// Le point d'entrée : la première couche à tenter (toujours la plus proche de
/// l'utilisateur quand l'OS démarre encore).
pub fn premiere_couche() -> Couche {
    Couche::ConsoleDansOS
}

/// Vrai quand il n'y a plus aucune couche automatique : il faut le dire à
/// l'utilisateur, sans jamais dégrader la sécurité en silence.
pub fn filet_epuise(c: Couche) -> bool {
    c == Couche::PlusDeFilet
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn descend_quand_une_couche_echoue() {
        assert_eq!(couche_suivante(Couche::ConsoleDansOS, true), Couche::RecoveryIso);
        assert_eq!(couche_suivante(Couche::RecoveryIso, true), Couche::ReinstallSignee);
        assert_eq!(couche_suivante(Couche::ReinstallSignee, true), Couche::PlusDeFilet);
    }

    #[test]
    fn reste_sur_place_si_pas_d_echec() {
        assert_eq!(couche_suivante(Couche::ConsoleDansOS, false), Couche::ConsoleDansOS);
        assert_eq!(couche_suivante(Couche::RecoveryIso, false), Couche::RecoveryIso);
    }

    #[test]
    fn plancher_ne_descend_plus() {
        // même en échec, PlusDeFilet reste PlusDeFilet (jamais de débordement)
        assert_eq!(couche_suivante(Couche::PlusDeFilet, true), Couche::PlusDeFilet);
        assert!(filet_epuise(Couche::PlusDeFilet));
        assert!(!filet_epuise(Couche::RecoveryIso));
    }

    #[test]
    fn chaine_complete_jusqu_au_plancher() {
        // on simule l'échec de chaque couche successivement
        let mut c = premiere_couche();
        assert_eq!(c, Couche::ConsoleDansOS);
        c = couche_suivante(c, true);
        c = couche_suivante(c, true);
        c = couche_suivante(c, true);
        assert_eq!(c, Couche::PlusDeFilet);
        assert!(filet_epuise(c));
    }

    #[test]
    fn libelles_non_vides_sans_oe() {
        for c in [Couche::ConsoleDansOS, Couche::RecoveryIso, Couche::ReinstallSignee, Couche::PlusDeFilet] {
            assert!(!c.libelle().is_empty());
            assert!(!c.libelle().contains('\u{0153}'));
        }
    }
}
