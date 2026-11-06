//! secours_iso.rs — cerveau de décision du RECOVERY EMBARQUÉ DANS L'ISO.
//!
//! But (Piika 06/11) : réparer une installation PuppyOS **sans jamais la
//! démarrer**. On boote l'ISO, on détecte l'installation présente (par
//! l'inspecteur lecture seule `inspection.c`, qui lit les tables et signatures
//! SANS monter), puis on répare les deux apps privilégiées à partir d'une copie
//! dorée signée embarquée dans l'ISO — exactement la même vérification épinglée
//! que la console `puprecovery` (voir `secours.rs`).
//!
//! RÈGLES DURES (reprises de la directive installation/multiboot) :
//!   - JAMAIS de format automatique : on ne détruit pas les données de
//!     l'utilisateur pour « réparer ». L'effacement total reste un choix
//!     explicite, séparé, jamais pris par cette décision.
//!   - Si l'installation est chiffrée (dm-crypt/LUKS), le **mot de passe de
//!     déchiffrement est OBLIGATOIRE** avant de toucher quoi que ce soit : le
//!     recovery ne stocke AUCUNE clé (comme la console), c'est la phrase de
//!     l'utilisateur qui ouvre le volume. Pas de phrase = pas de réparation.
//!   - On ne touche JAMAIS un disque qui n'est pas visiblement PuppyOS.
//!
//! Module PUR : aucune E/S, aucun privilège. Le C (ISO) fait l'inspection, la
//! demande de mot de passe, l'ouverture du volume et la pose ; la DÉCISION est
//! ici (testée).
#![allow(dead_code)]

/// Ce que l'inspection (lecture seule) a établi sur le disque cible.
#[derive(Debug, Clone, Copy)]
pub struct EtatInstall {
    /// Une partition est visiblement une installation PuppyOS.
    pub puppyos_detecte: bool,
    /// Le volume système est chiffré (LUKS/dm-crypt) : mot de passe requis.
    pub chiffre: bool,
    /// Le volume est ouvert/montable (après mot de passe le cas échéant) et
    /// lisible en écriture pour la réparation.
    pub montable: bool,
    /// Le dossier isolé des deux apps critiques est présent (sinon : à poser).
    pub apps_presentes: bool,
}

/// Décision du recovery ISO. Aucune n'efface jamais le disque.
#[derive(Debug, PartialEq, Eq)]
pub enum ActionIso {
    /// Pas de PuppyOS détecté : on ne touche à rien (protection de l'existant).
    RienDetecte,
    /// Installation chiffrée pas encore ouverte : exiger la phrase AVANT tout.
    MotDePasseRequis,
    /// Volume ouvert, apps présentes : réinstaller/vérifier les deux apps depuis
    /// la copie dorée signée, SANS démarrer l'OS (pose A/B, données préservées).
    ReparerApps,
    /// Volume ouvert mais apps absentes/irrécupérables : proposer une
    /// réinstallation qui PRÉSERVE les données (jamais de format).
    ProposerReinstallSansFormat,
}

/// Décide l'action du recovery ISO à partir de l'état constaté. Ordre : pas de
/// PuppyOS → rien ; chiffré non ouvert → mot de passe ; ouvert → réparer si les
/// apps sont là, sinon réinstaller sans format.
pub fn decider(e: &EtatInstall) -> ActionIso {
    if !e.puppyos_detecte {
        return ActionIso::RienDetecte;
    }
    if e.chiffre && !e.montable {
        // le mot de passe ouvre le volume : rien n'est touché avant.
        return ActionIso::MotDePasseRequis;
    }
    if !e.montable {
        // PuppyOS non chiffré mais illisible (stockage défectueux) : on ne peut
        // pas réparer en place, on propose une réinstallation sans format.
        return ActionIso::ProposerReinstallSansFormat;
    }
    if e.apps_presentes {
        ActionIso::ReparerApps
    } else {
        ActionIso::ProposerReinstallSansFormat
    }
}

/// Un format total détruit les données : il ne peut JAMAIS être une conséquence
/// automatique d'une réparation. Cette fonction dit explicitement qu'aucune
/// `ActionIso` ne l'autorise — un effacement reste un choix séparé et explicite.
pub fn autorise_format(_a: &ActionIso) -> bool {
    false
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    fn etat(puppyos: bool, chiffre: bool, montable: bool, apps: bool) -> EtatInstall {
        EtatInstall { puppyos_detecte: puppyos, chiffre, montable, apps_presentes: apps }
    }

    #[test]
    fn pas_de_puppyos_on_ne_touche_a_rien() {
        // disque Windows/autre : protection de l'existant
        assert_eq!(decider(&etat(false, false, true, true)), ActionIso::RienDetecte);
        assert_eq!(decider(&etat(false, true, false, false)), ActionIso::RienDetecte);
    }

    #[test]
    fn chiffre_non_ouvert_exige_le_mot_de_passe() {
        assert_eq!(decider(&etat(true, true, false, false)), ActionIso::MotDePasseRequis);
        assert_eq!(decider(&etat(true, true, false, true)), ActionIso::MotDePasseRequis);
    }

    #[test]
    fn ouvert_avec_apps_repare() {
        // chiffré mais déjà ouvert (mot de passe fourni), apps présentes
        assert_eq!(decider(&etat(true, true, true, true)), ActionIso::ReparerApps);
        // non chiffré, apps présentes
        assert_eq!(decider(&etat(true, false, true, true)), ActionIso::ReparerApps);
    }

    #[test]
    fn ouvert_sans_apps_reinstalle_sans_format() {
        assert_eq!(decider(&etat(true, true, true, false)), ActionIso::ProposerReinstallSansFormat);
        assert_eq!(decider(&etat(true, false, true, false)), ActionIso::ProposerReinstallSansFormat);
    }

    #[test]
    fn puppyos_non_chiffre_illisible_reinstalle_sans_format() {
        // stockage défectueux : pas réparable en place, jamais de format auto
        assert_eq!(decider(&etat(true, false, false, true)), ActionIso::ProposerReinstallSansFormat);
    }

    #[test]
    fn aucune_action_n_autorise_le_format() {
        for a in [
            ActionIso::RienDetecte,
            ActionIso::MotDePasseRequis,
            ActionIso::ReparerApps,
            ActionIso::ProposerReinstallSansFormat,
        ] {
            assert!(!autorise_format(&a));
        }
    }
}
