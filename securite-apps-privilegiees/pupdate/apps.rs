//! apps.rs — PuppyPackageManager + PuppyRollback : la POLITIQUE d'installation,
//! de mise à jour, de rollback et de désinstallation des applications `.puppy`,
//! en Rust. Ce module ne touche PAS au disque : il décide ce qui est autorisé et
//! produit un *plan* d'actions ; un exécuteur privilégié (puppy-autorite, phase
//! ultérieure) réalisera les renommages atomiques. On sépare exprès la décision
//! (testable, sans risque) de l'exécution (privilégiée, destructive).
//!
//! Schéma A/B des applications (comble le manque M4 du rapport d'audit : à ce
//! jour un `.puppy` est remplacé en place, sans retour possible) :
//!   /system/programmes/<id>/            version installée
//!   /system/programmes/<id>.precedent/  version précédente (1 cran de rollback)
//!   /system/programmes-iso/<id>/        version d'origine livrée avec l'ISO
//!
//! Politique de la directive :
//!   - app SYSTÈME : désinstallation complète interdite ; MAJ obligatoire ; une
//!     MAJ marquée sécurité ne peut pas être annulée *définitivement* → on ne
//!     laisse jamais l'app durablement sous un « plancher de sécurité ».
//!   - app UTILISATEUR : install / désinstall / rollback libres.
//!   - anti-downgrade : installer une version plus ancienne que l'installée est
//!     refusé (réinstaller la même version est autorisé).
#![allow(dead_code)]

use core::cmp::Ordering;

// réutilise la comparaison de versions du registre
use crate::registre::{comparer_version, Categorie};

/// État d'une application tel que connu du gestionnaire.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EtatApp {
    pub id: String,
    pub categorie: Categorie,
    pub installee: Option<String>,   // version en place, None si absente
    pub precedente: Option<String>,  // version de rollback, None si aucune
    pub iso: Option<String>,         // version d'origine ISO, None si pas livrée avec l'ISO
    /// Plancher de sécurité : version en-dessous de laquelle on refuse de
    /// rester (fixé quand une MAJ `securite` est installée). None = pas de plancher.
    pub plancher_securite: Option<String>,
}

/// Opération demandée.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Operation {
    /// Installer / mettre à jour vers cette version (le `.puppy` est déjà
    /// vérifié en amont : signature Ed25519 OK). `securite` = MAJ de sécurité.
    Installer { version: String, securite: bool },
    /// Revenir à la version précédente (1 cran).
    Rollback,
    /// Revenir à la version d'origine de l'ISO.
    RetourIso,
    /// Désinstaller l'application.
    Desinstaller,
}

/// Raisons de refus (toutes affichables en clair dans l'UI).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refus {
    SystemeDesinstallInterdit, // une app système ne se désinstalle pas entièrement
    Downgrade,                 // installer une version plus ancienne que l'installée
    DowngradeSecurite,         // reviendrait sous le plancher de sécurité
    RienAFaire,                // déjà dans l'état voulu / rien à annuler
    PasDePrecedent,            // rollback sans version précédente
    PasDIso,                   // retour ISO sans version ISO connue
    AbsentePourOperation,      // rollback/retour/désinstall d'une app non installée
}

impl Refus {
    pub fn libelle(self) -> &'static str {
        match self {
            Refus::SystemeDesinstallInterdit => "Application système : désinstallation complète impossible",
            Refus::Downgrade => "Version plus ancienne que celle installée : refusée",
            Refus::DowngradeSecurite => "Reviendrait sous une mise à jour de sécurité : refusé",
            Refus::RienAFaire => "Rien à faire",
            Refus::PasDePrecedent => "Aucune version précédente pour revenir en arrière",
            Refus::PasDIso => "Aucune version d'origine (ISO) disponible",
            Refus::AbsentePourOperation => "Application non installée",
        }
    }
}

/// Action élémentaire du plan (réalisée plus tard par l'exécuteur privilégié).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Action {
    /// Poser cette version (extraire le `.puppy` vérifié vers `<id>.part` puis
    /// bascule atomique) ; l'installée actuelle devient la précédente.
    Poser { version: String },
    /// Échanger `<id>` et `<id>.precedent` (rollback atomique).
    BasculerPrecedente,
    /// Copier la version ISO par-dessus (depuis `programmes-iso/<id>`).
    PoserIso { version: String },
    /// Supprimer l'arbre de l'application (désinstallation).
    Supprimer,
}

/// Plan résultant d'une décision autorisée.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Plan {
    pub actions: Vec<Action>,
    pub etat_resultant: EtatApp,
}

/// Le plancher est-il respecté par `version` ? (>= plancher, ou pas de plancher)
fn au_dessus_plancher(version: &str, plancher: &Option<String>) -> bool {
    match plancher {
        None => true,
        Some(p) => comparer_version(version, p) != Ordering::Less,
    }
}

/// DÉCISION centrale : que faire de `op` sur `etat` ? Ne touche rien.
pub fn decider(etat: &EtatApp, op: &Operation) -> Result<Plan, Refus> {
    match op {
        // ---------------------------------------------------- installer / MAJ
        Operation::Installer { version, securite } => {
            if let Some(inst) = &etat.installee {
                match comparer_version(version, inst) {
                    Ordering::Less => return Err(Refus::Downgrade),
                    Ordering::Equal => return Err(Refus::RienAFaire), // déjà cette version
                    Ordering::Greater => {}
                }
            }
            let plancher = if *securite {
                // une MAJ de sécurité relève le plancher à sa propre version
                Some(version.clone())
            } else {
                etat.plancher_securite.clone()
            };
            let resultant = EtatApp {
                id: etat.id.clone(),
                categorie: etat.categorie,
                installee: Some(version.clone()),
                precedente: etat.installee.clone(), // l'ancienne devient précédente
                iso: etat.iso.clone(),
                plancher_securite: plancher,
            };
            Ok(Plan { actions: vec![Action::Poser { version: version.clone() }], etat_resultant: resultant })
        }

        // --------------------------------------------------------- rollback
        Operation::Rollback => {
            if etat.installee.is_none() {
                return Err(Refus::AbsentePourOperation);
            }
            let prec = match &etat.precedente {
                None => return Err(Refus::PasDePrecedent),
                Some(p) => p.clone(),
            };
            // app système : refuser si la précédente est sous le plancher de sécurité
            if etat.categorie == Categorie::Systeme && !au_dessus_plancher(&prec, &etat.plancher_securite) {
                return Err(Refus::DowngradeSecurite);
            }
            let resultant = EtatApp {
                id: etat.id.clone(),
                categorie: etat.categorie,
                installee: Some(prec),
                precedente: etat.installee.clone(), // échange
                iso: etat.iso.clone(),
                plancher_securite: etat.plancher_securite.clone(),
            };
            Ok(Plan { actions: vec![Action::BasculerPrecedente], etat_resultant: resultant })
        }

        // -------------------------------------------------------- retour ISO
        Operation::RetourIso => {
            if etat.installee.is_none() {
                return Err(Refus::AbsentePourOperation);
            }
            let iso = match &etat.iso {
                None => return Err(Refus::PasDIso),
                Some(v) => v.clone(),
            };
            if let Some(inst) = &etat.installee {
                if comparer_version(&iso, inst) == Ordering::Equal {
                    return Err(Refus::RienAFaire); // déjà sur la version ISO
                }
            }
            if etat.categorie == Categorie::Systeme && !au_dessus_plancher(&iso, &etat.plancher_securite) {
                return Err(Refus::DowngradeSecurite);
            }
            let resultant = EtatApp {
                id: etat.id.clone(),
                categorie: etat.categorie,
                installee: Some(iso.clone()),
                precedente: etat.installee.clone(),
                iso: etat.iso.clone(),
                plancher_securite: etat.plancher_securite.clone(),
            };
            Ok(Plan { actions: vec![Action::PoserIso { version: iso }], etat_resultant: resultant })
        }

        // ------------------------------------------------------ désinstaller
        Operation::Desinstaller => {
            if etat.installee.is_none() {
                return Err(Refus::AbsentePourOperation);
            }
            if etat.categorie == Categorie::Systeme {
                return Err(Refus::SystemeDesinstallInterdit);
            }
            let resultant = EtatApp {
                id: etat.id.clone(),
                categorie: etat.categorie,
                installee: None,
                precedente: None,
                iso: etat.iso.clone(),
                plancher_securite: etat.plancher_securite.clone(),
            };
            Ok(Plan { actions: vec![Action::Supprimer], etat_resultant: resultant })
        }
    }
}

/// MAJ OS : une app système doit être forcée à sa dernière version stable.
/// Renvoie le plan d'installation forcée, ou None si déjà à jour.
/// (La version cible vient du catalogue vérifié ; `securite=true` car imposée.)
pub fn forcer_app_systeme(etat: &EtatApp, cible: &str) -> Option<Plan> {
    if etat.categorie != Categorie::Systeme {
        return None;
    }
    match &etat.installee {
        Some(inst) if comparer_version(cible, inst) != Ordering::Greater => None,
        _ => decider(etat, &Operation::Installer { version: cible.to_string(), securite: true }).ok(),
    }
}

// ===========================================================================
//                                AUTOTESTS
//   rustc --edition 2021 --test system/pupdate/apps.rs ... (via la crate)
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    fn util(installee: Option<&str>, prec: Option<&str>, iso: Option<&str>) -> EtatApp {
        EtatApp {
            id: "bloc-notes".into(),
            categorie: Categorie::Utilisateur,
            installee: installee.map(|s| s.into()),
            precedente: prec.map(|s| s.into()),
            iso: iso.map(|s| s.into()),
            plancher_securite: None,
        }
    }
    fn sys(installee: Option<&str>, prec: Option<&str>, iso: Option<&str>, plancher: Option<&str>) -> EtatApp {
        EtatApp {
            id: "dock".into(),
            categorie: Categorie::Systeme,
            installee: installee.map(|s| s.into()),
            precedente: prec.map(|s| s.into()),
            iso: iso.map(|s| s.into()),
            plancher_securite: plancher.map(|s| s.into()),
        }
    }

    #[test]
    fn install_maj_pousse_precedente() {
        let e = util(Some("1.0"), None, Some("1.0"));
        let p = decider(&e, &Operation::Installer { version: "1.1".into(), securite: false }).unwrap();
        assert_eq!(p.actions, vec![Action::Poser { version: "1.1".into() }]);
        assert_eq!(p.etat_resultant.installee.as_deref(), Some("1.1"));
        assert_eq!(p.etat_resultant.precedente.as_deref(), Some("1.0"));
    }

    #[test]
    fn install_meme_version_rien_a_faire() {
        let e = util(Some("1.0"), None, None);
        assert_eq!(decider(&e, &Operation::Installer { version: "1.0".into(), securite: false }), Err(Refus::RienAFaire));
    }

    #[test]
    fn install_downgrade_refuse() {
        let e = util(Some("1.1"), None, None);
        assert_eq!(decider(&e, &Operation::Installer { version: "1.0".into(), securite: false }), Err(Refus::Downgrade));
    }

    #[test]
    fn install_securite_releve_plancher() {
        let e = sys(Some("0.18"), None, Some("0.17"), None);
        let p = decider(&e, &Operation::Installer { version: "0.19".into(), securite: true }).unwrap();
        assert_eq!(p.etat_resultant.plancher_securite.as_deref(), Some("0.19"));
    }

    #[test]
    fn rollback_utilisateur_libre() {
        let e = util(Some("1.1"), Some("1.0"), None);
        let p = decider(&e, &Operation::Rollback).unwrap();
        assert_eq!(p.actions, vec![Action::BasculerPrecedente]);
        assert_eq!(p.etat_resultant.installee.as_deref(), Some("1.0"));
        assert_eq!(p.etat_resultant.precedente.as_deref(), Some("1.1"));
    }

    #[test]
    fn rollback_sans_precedente_refuse() {
        let e = util(Some("1.1"), None, None);
        assert_eq!(decider(&e, &Operation::Rollback), Err(Refus::PasDePrecedent));
    }

    #[test]
    fn rollback_systeme_sous_plancher_refuse() {
        // dock en 0.19 (plancher 0.19), précédente 0.18 -> rollback interdit
        let e = sys(Some("0.19"), Some("0.18"), Some("0.17"), Some("0.19"));
        assert_eq!(decider(&e, &Operation::Rollback), Err(Refus::DowngradeSecurite));
    }

    #[test]
    fn rollback_systeme_au_dessus_plancher_ok() {
        // dock en 0.20 (plancher 0.19), précédente 0.19 -> rollback autorisé
        let e = sys(Some("0.20"), Some("0.19"), Some("0.17"), Some("0.19"));
        let p = decider(&e, &Operation::Rollback).unwrap();
        assert_eq!(p.etat_resultant.installee.as_deref(), Some("0.19"));
    }

    #[test]
    fn retour_iso_ok() {
        let e = util(Some("1.2"), Some("1.1"), Some("1.0"));
        let p = decider(&e, &Operation::RetourIso).unwrap();
        assert_eq!(p.actions, vec![Action::PoserIso { version: "1.0".into() }]);
        assert_eq!(p.etat_resultant.installee.as_deref(), Some("1.0"));
    }

    #[test]
    fn retour_iso_sans_iso_refuse() {
        let e = util(Some("1.2"), None, None);
        assert_eq!(decider(&e, &Operation::RetourIso), Err(Refus::PasDIso));
    }

    #[test]
    fn retour_iso_systeme_sous_plancher_refuse() {
        let e = sys(Some("0.20"), None, Some("0.17"), Some("0.19"));
        assert_eq!(decider(&e, &Operation::RetourIso), Err(Refus::DowngradeSecurite));
    }

    #[test]
    fn desinstall_utilisateur_ok() {
        let e = util(Some("1.0"), Some("0.9"), Some("0.9"));
        let p = decider(&e, &Operation::Desinstaller).unwrap();
        assert_eq!(p.actions, vec![Action::Supprimer]);
        assert_eq!(p.etat_resultant.installee, None);
    }

    #[test]
    fn desinstall_systeme_interdit() {
        let e = sys(Some("0.19"), None, Some("0.17"), None);
        assert_eq!(decider(&e, &Operation::Desinstaller), Err(Refus::SystemeDesinstallInterdit));
    }

    #[test]
    fn operation_sur_absente_refuse() {
        let e = util(None, None, Some("1.0"));
        assert_eq!(decider(&e, &Operation::Rollback), Err(Refus::AbsentePourOperation));
        assert_eq!(decider(&e, &Operation::RetourIso), Err(Refus::AbsentePourOperation));
        assert_eq!(decider(&e, &Operation::Desinstaller), Err(Refus::AbsentePourOperation));
        // installer une absente est permis (première installation)
        assert!(decider(&e, &Operation::Installer { version: "1.0".into(), securite: false }).is_ok());
    }

    #[test]
    fn forcer_app_systeme_quand_en_retard() {
        let e = sys(Some("0.18"), None, Some("0.17"), None);
        let p = forcer_app_systeme(&e, "0.19").expect("doit forcer");
        assert_eq!(p.etat_resultant.installee.as_deref(), Some("0.19"));
        assert_eq!(p.etat_resultant.plancher_securite.as_deref(), Some("0.19"));
    }

    #[test]
    fn forcer_app_systeme_deja_a_jour_none() {
        let e = sys(Some("0.19"), None, Some("0.17"), None);
        assert!(forcer_app_systeme(&e, "0.19").is_none());
    }

    #[test]
    fn forcer_ignore_utilisateur() {
        let e = util(Some("1.0"), None, None);
        assert!(forcer_app_systeme(&e, "2.0").is_none());
    }
}
