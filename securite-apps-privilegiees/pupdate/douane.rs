//! douane.rs — LE 5e CHIEN (« Scrap-Dog »), douanier des entrées non fiables.
//!
//! Demande Piika (06/11) : un chien qui mange les octets bruts dangereux et ne
//! laisse passer que du propre, pour que les buffer-overflows ne vivent jamais
//! dans le C. Découpage des langues : **Rust avale le danger** (parsing des
//! entrées non fiables en mémoire sûre), **le C agit sur du propre** (données
//! déjà bornées et validées). Un seul front door : toute entrée non fiable
//! (fichier ouvert, catalogue, paquet, en-tête de déchiffrement, métadonnées)
//! passe d'abord ici.
//!
//! Ce module est le CERVEAU (pur, testé) de ce poste de douane. Il ne lit aucun
//! octet lui-même : il décide, à partir des bornes déclarées, si une entrée a le
//! droit d'être remise à un chien C — ou si elle est rejetée **avant** que le C
//! ne la touche. Mêmes bornes que l'anti-bombe de l'extracteur : deux remparts
//! cohérents.
//!
//! Module PUR : aucune E/S, aucun privilège, jamais de panique.
#![allow(dead_code)]

/// Noms du 5e chien (choix Piika : « pas glamour », les deux lui vont). Rôle de
/// douanier des entrées non fiables. Libellés d'affichage côté UI/aide.
pub const NOMS: [&str; 2] = ["Scrap-Dog", "Trash-Dog"];

/// Bornes du poste de douane (identiques à l'anti-bombe de `libpuppy/paquet.c`).
pub const TAILLE_MAX: u64 = 256 * 1024 * 1024; // 256 Mio par entrée
pub const CUMUL_MAX: u64 = 512 * 1024 * 1024; // 512 Mio cumulés
pub const PROFONDEUR_MAX: u32 = 32;
pub const ENTREES_MAX: u32 = 20_000;

/// Verdict de la douane sur une entrée non fiable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    /// Dans les bornes et d'un type connu : peut être remise à un chien C.
    Passer,
    /// Hors bornes ou type inconnu : rejetée AVANT que le C ne la touche.
    Rejeter { motif: &'static str },
}

/// Examine une entrée non fiable au poste de douane.
/// `taille` : octets de cette entrée. `cumul` : octets déjà acceptés dans le même
/// lot (archive, catalogue…). `profondeur` : imbrication. `nb_entrees` : nombre
/// d'entrées déjà vues dans le lot. `type_connu` : sait-on interpréter ce type ?
///
/// Rejette (avant tout C) : type inconnu, ou toute borne dépassée. Un type
/// inconnu ne passe JAMAIS : on ne remet au C que ce qu'on sait déjà borner.
pub fn examiner(
    taille: u64,
    cumul: u64,
    profondeur: u32,
    nb_entrees: u32,
    type_connu: bool,
) -> Verdict {
    if !type_connu {
        return Verdict::Rejeter { motif: "type d'entree inconnu : non remis au C (on ne sait pas le borner)" };
    }
    if taille > TAILLE_MAX {
        return Verdict::Rejeter { motif: "entree trop grosse (> 256 Mio) : rejetee avant traitement" };
    }
    // cumul vérifié sans débordement
    if cumul > CUMUL_MAX || cumul.checked_add(taille).map_or(true, |t| t > CUMUL_MAX) {
        return Verdict::Rejeter { motif: "cumul du lot trop gros (> 512 Mio) : rejete" };
    }
    if profondeur > PROFONDEUR_MAX {
        return Verdict::Rejeter { motif: "imbrication trop profonde (> 32) : rejetee" };
    }
    if nb_entrees >= ENTREES_MAX {
        return Verdict::Rejeter { motif: "trop d'entrees dans le lot (>= 20000) : rejete" };
    }
    Verdict::Passer
}

/// Vrai si l'entrée peut être remise à un chien C.
pub fn peut_passer(taille: u64, cumul: u64, profondeur: u32, nb_entrees: u32, type_connu: bool) -> bool {
    matches!(examiner(taille, cumul, profondeur, nb_entrees, type_connu), Verdict::Passer)
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn entree_propre_passe() {
        assert_eq!(examiner(1024, 1024, 1, 1, true), Verdict::Passer);
        assert!(peut_passer(1024, 1024, 1, 1, true));
    }

    #[test]
    fn type_inconnu_jamais_remis_au_c() {
        // le cœur du rôle : Rust ne remet au C que ce qu'il sait borner
        assert!(matches!(examiner(1, 1, 1, 1, false), Verdict::Rejeter { .. }));
    }

    #[test]
    fn trop_gros_rejete_avant_traitement() {
        assert!(matches!(examiner(TAILLE_MAX + 1, 0, 1, 1, true), Verdict::Rejeter { .. }));
    }

    #[test]
    fn cumul_trop_gros_rejete_sans_debordement() {
        // cumul déjà au plafond
        assert!(matches!(examiner(1, CUMUL_MAX, 1, 1, true), Verdict::Rejeter { .. }));
        // cumul + taille déborderait u64 : refusé proprement, jamais de panique
        assert!(matches!(examiner(u64::MAX, u64::MAX, 1, 1, true), Verdict::Rejeter { .. }));
    }

    #[test]
    fn imbrication_et_nombre_bornes() {
        assert!(matches!(examiner(1, 1, PROFONDEUR_MAX + 1, 1, true), Verdict::Rejeter { .. }));
        assert!(matches!(examiner(1, 1, 1, ENTREES_MAX, true), Verdict::Rejeter { .. }));
    }

    #[test]
    fn aux_bornes_exactes_on_passe() {
        assert_eq!(examiner(TAILLE_MAX, TAILLE_MAX, PROFONDEUR_MAX, ENTREES_MAX - 1, true), Verdict::Passer);
    }
}
