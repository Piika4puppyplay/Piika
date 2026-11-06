//! explorateur.rs — AUTORITÉS de l'explorateur de fichiers + VIDAGE RAM.
//!
//! Demande Piika (06/11) : l'explorateur est réécrit en Rust, séparé du système
//! (comme le Media Center Rust : une app `.puppy` signée), avec thèmes et packs
//! d'icônes futurs. Ses **autorités restent bridées au maximum** pour couper
//! toute attaque, et la **RAM est vidée à la fermeture** pour que les clés des
//! volumes déchiffrés (disques, clés USB, cartes, téléphones) ne **fuitent pas**.
//!
//! Ce module est le CERVEAU (pur, testé) de ces deux garanties. Il ne touche pas
//! le matériel : il décide ce que l'app a le droit de faire, et quelles
//! obligations de nettoyage s'imposent. Le C applique (seccomp, `crypto_wipe`,
//! démontage).
//!
//! 1. AUTORITÉS MINIMALES. L'explorateur ne sait que : lire des fichiers, écrire
//!    sur des supports approuvés, et DEMANDER un montage (qui passe par le sas
//!    `montage_securise`). Tout le reste est INTERDIT : pas de réseau, pas
//!    d'exécution de code, pas de privilège, pas de `ptrace`, pas de montage
//!    direct. C'est la liste blanche la plus courte possible.
//!
//! 2. VIDAGE RAM À LA FERMETURE. À la fermeture de l'explorateur — ou dès qu'un
//!    volume déchiffré est démonté — la clé de ce volume et les tampons
//!    sensibles sont effacés (zéroïsation). Une clé déchiffrée ne survit jamais
//!    à la fenêtre qui l'a ouverte.
//!
//! Module PUR : aucune E/S, aucun privilège, jamais de panique. La sûreté
//! mémoire (anti buffer-overflow) vient du langage : tout est borné, pas d'`unsafe`.
#![allow(dead_code)]

/// Un pouvoir que l'app explorateur pourrait réclamer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Pouvoir {
    /// Lire des fichiers (sur un support déjà monté / approuvé).
    LireFichiers,
    /// Écrire/copier/déplacer sur un support APPROUVÉ (amovible, ou nos volumes).
    EcrireSupportApprouve,
    /// DEMANDER l'ouverture d'un volume chiffré (la décision passe par le sas
    /// `montage_securise` : chrono anti-brute-force + Honey-Dog). L'explorateur
    /// ne monte jamais lui-même.
    DemanderMontage,
    // --- tout ce qui suit est INTERDIT à l'explorateur ---
    /// Accès réseau.
    Reseau,
    /// Exécuter du code (lancer un binaire, un script).
    ExecuterCode,
    /// Élever ses privilèges.
    Privilege,
    /// Tracer un autre processus (`ptrace`).
    Ptrace,
    /// Monter / démonter directement un système de fichiers.
    MontageDirect,
}

/// Liste blanche : SEULS ces trois pouvoirs sont autorisés. Tout le reste est
/// refusé. C'est le bridage maximal demandé.
pub fn autorise(p: Pouvoir) -> bool {
    matches!(
        p,
        Pouvoir::LireFichiers | Pouvoir::EcrireSupportApprouve | Pouvoir::DemanderMontage
    )
}

/// Événement du cycle de vie d'un volume / de l'app.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Evenement {
    /// Un volume chiffré vient d'être ouvert (sa clé est en RAM).
    VolumeOuvert,
    /// Un volume déchiffré est démonté.
    VolumeDemonte,
    /// L'explorateur se ferme (volontairement, ou tué).
    ExplorateurFerme,
}

/// Faut-il effacer (zéroïser) la clé d'un volume déchiffré sur cet événement ?
/// VRAI au démontage et à la fermeture : la clé ne survit jamais à la fenêtre.
pub fn doit_effacer_cle(ev: Evenement) -> bool {
    matches!(ev, Evenement::VolumeDemonte | Evenement::ExplorateurFerme)
}

/// Obligations de nettoyage à la fermeture de l'explorateur. Toutes vraies :
/// c'est un contrat de non-fuite, pas une option.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Fermeture {
    /// Effacer en RAM les clés de TOUS les volumes déchiffrés encore ouverts.
    pub effacer_cles_volumes: bool,
    /// Effacer les tampons sensibles (aperçus de fichiers, chemins, presse-papier
    /// ayant transité par un volume chiffré).
    pub effacer_tampons_sensibles: bool,
    /// Démonter proprement les volumes que l'explorateur avait fait ouvrir.
    pub demonter_volumes_ouverts: bool,
}

/// Le contrat de fermeture : on efface TOUT. Rien ne fuit.
pub fn a_la_fermeture() -> Fermeture {
    Fermeture {
        effacer_cles_volumes: true,
        effacer_tampons_sensibles: true,
        demonter_volumes_ouverts: true,
    }
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seuls_les_trois_pouvoirs_minimaux_sont_autorises() {
        assert!(autorise(Pouvoir::LireFichiers));
        assert!(autorise(Pouvoir::EcrireSupportApprouve));
        assert!(autorise(Pouvoir::DemanderMontage));
    }

    #[test]
    fn tout_le_dangereux_est_interdit() {
        for p in [
            Pouvoir::Reseau,
            Pouvoir::ExecuterCode,
            Pouvoir::Privilege,
            Pouvoir::Ptrace,
            Pouvoir::MontageDirect,
        ] {
            assert!(!autorise(p), "{p:?} ne doit JAMAIS etre autorise a l'explorateur");
        }
    }

    #[test]
    fn explorateur_ne_monte_jamais_lui_meme() {
        // il peut DEMANDER un montage, jamais le faire directement
        assert!(autorise(Pouvoir::DemanderMontage));
        assert!(!autorise(Pouvoir::MontageDirect));
    }

    #[test]
    fn cle_effacee_au_demontage_et_a_la_fermeture() {
        assert!(doit_effacer_cle(Evenement::VolumeDemonte));
        assert!(doit_effacer_cle(Evenement::ExplorateurFerme));
    }

    #[test]
    fn cle_vit_tant_que_le_volume_est_ouvert() {
        // à l'ouverture, la clé est légitimement en RAM (volume en service)
        assert!(!doit_effacer_cle(Evenement::VolumeOuvert));
    }

    #[test]
    fn fermeture_efface_tout() {
        let f = a_la_fermeture();
        assert!(f.effacer_cles_volumes);
        assert!(f.effacer_tampons_sensibles);
        assert!(f.demonter_volumes_ouverts);
    }
}
