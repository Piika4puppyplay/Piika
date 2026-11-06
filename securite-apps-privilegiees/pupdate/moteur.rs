//! moteur.rs — PuppyUpdate : le « cerveau » de la mise à jour. À partir d'un
//! catalogue DÉJÀ VÉRIFIÉ (signature Ed25519 OK, cf. verif.rs) et de l'état réel
//! des applications (cf. apps.rs / registre.rs), il calcule un *bilan* de mise à
//! jour : ce qui doit être forcé (apps système), ce qui est proposé (apps
//! utilisateur), et ce qui est incompatible. Il ne télécharge rien et ne touche
//! pas au disque — le téléchargement HTTPS réutilise l'existant (http.c/maj.c) et
//! l'installation réelle passera par un exécuteur privilégié (puppy-autorite),
//! phase ultérieure. Ici : décision pure, testable, sans risque.
#![allow(dead_code)]

use crate::apps::{decider, EtatApp, Operation, Plan};
use crate::registre::{comparer_version, Catalogue, Categorie};
use core::cmp::Ordering;

/// Une mise à jour d'une application, prête à exécuter (plan A/B inclus).
#[derive(Clone, Debug)]
pub struct MajApp {
    pub id: String,
    pub de: Option<String>, // version installée (None = première installation)
    pub vers: String,       // version proposée par le catalogue
    pub categorie: Categorie,
    pub forcee: bool,       // true pour les apps système (imposée par une MAJ OS)
    pub plan: Plan,         // le plan d'actions A/B (apps::decider)
}

/// Bilan complet d'un cycle de mise à jour.
#[derive(Clone, Debug, Default)]
pub struct Bilan {
    pub forcees: Vec<MajApp>,       // apps système à mettre à jour (obligatoire)
    pub proposees: Vec<MajApp>,     // apps utilisateur avec une MAJ disponible
    pub incompatibles: Vec<String>, // ids exigeant un OS plus récent
}

impl Bilan {
    pub fn total(&self) -> usize {
        self.forcees.len() + self.proposees.len()
    }
}

/// Calcule le bilan : pour chaque application connue, confronte son état au
/// catalogue vérifié. Les apps système en retard sont *forcées* ; les apps
/// utilisateur en retard sont *proposées* ; une entrée exigeant un OS plus
/// récent que `os_version` est signalée incompatible (jamais installée).
pub fn planifier_maj(catalogue: &Catalogue, etats: &[EtatApp], os_version: &str) -> Bilan {
    let mut bilan = Bilan::default();

    for etat in etats {
        let entree = match catalogue.entrees.iter().find(|e| e.id == etat.id) {
            None => continue, // installée mais hors catalogue : on n'y touche pas
            Some(e) => e,
        };

        // incompatibilité OS
        if !entree.min_os.is_empty()
            && comparer_version(os_version, &entree.min_os) == Ordering::Less
        {
            bilan.incompatibles.push(etat.id.clone());
            continue;
        }

        // y a-t-il quelque chose de plus récent ?
        let plus_recent = match &etat.installee {
            None => true, // absente : une install est « disponible »
            Some(inst) => comparer_version(&entree.version, inst) == Ordering::Greater,
        };
        if !plus_recent {
            continue;
        }

        let op = Operation::Installer {
            version: entree.version.clone(),
            securite: entree.obligatoire,
        };
        // decider ne peut échouer ici (version strictement plus récente), mais on
        // reste prudent : en cas de refus inattendu, on saute cette app.
        let plan = match decider(etat, &op) {
            Ok(p) => p,
            Err(_) => continue,
        };

        let maj = MajApp {
            id: etat.id.clone(),
            de: etat.installee.clone(),
            vers: entree.version.clone(),
            categorie: etat.categorie,
            forcee: etat.categorie == Categorie::Systeme,
            plan,
        };

        if maj.forcee {
            bilan.forcees.push(maj);
        } else {
            bilan.proposees.push(maj);
        }
    }

    // ordre stable et lisible : par id
    bilan.forcees.sort_by(|a, b| a.id.cmp(&b.id));
    bilan.proposees.sort_by(|a, b| a.id.cmp(&b.id));
    bilan
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;
    use crate::registre::parser_catalogue;

    fn catalogue() -> Catalogue {
        parser_catalogue(
            "\
catalogue=1
sequence=7
composant=dock
  categorie=systeme
  version=0.20
  fichier=Dock-0.20.puppy
  sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  obligatoire=oui
  min_os=0.19
composant=sidebar
  categorie=systeme
  version=0.19
  fichier=Sidebar-0.19.puppy
  sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  obligatoire=oui
composant=mediacenter
  categorie=utilisateur
  version=0.3
  fichier=MediaRust-0.3.puppy
  sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
composant=futur
  categorie=utilisateur
  version=1.0
  fichier=Futur-1.0.puppy
  sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  min_os=0.99
",
        )
        .unwrap()
    }

    fn app(id: &str, cat: Categorie, inst: Option<&str>) -> EtatApp {
        EtatApp {
            id: id.into(),
            categorie: cat,
            installee: inst.map(|s| s.into()),
            precedente: None,
            iso: inst.map(|s| s.into()),
            plancher_securite: None,
        }
    }

    #[test]
    fn systeme_force_utilisateur_propose() {
        let etats = vec![
            app("dock", Categorie::Systeme, Some("0.19")),       // 0.19 -> 0.20 forcé
            app("sidebar", Categorie::Systeme, Some("0.19")),    // à jour
            app("mediacenter", Categorie::Utilisateur, Some("0.2")), // 0.2 -> 0.3 proposé
        ];
        let b = planifier_maj(&catalogue(), &etats, "0.20");
        assert_eq!(b.forcees.len(), 1);
        assert_eq!(b.forcees[0].id, "dock");
        assert_eq!(b.forcees[0].vers, "0.20");
        assert!(b.forcees[0].forcee);
        assert_eq!(b.proposees.len(), 1);
        assert_eq!(b.proposees[0].id, "mediacenter");
        assert!(!b.proposees[0].forcee);
        assert!(b.incompatibles.is_empty());
    }

    #[test]
    fn incompatible_signale_pas_installe() {
        let etats = vec![app("futur", Categorie::Utilisateur, Some("0.5"))];
        let b = planifier_maj(&catalogue(), &etats, "0.20"); // futur exige 0.99
        assert_eq!(b.total(), 0);
        assert_eq!(b.incompatibles, vec!["futur".to_string()]);
    }

    #[test]
    fn hors_catalogue_ignore() {
        let etats = vec![app("bloc-notes", Categorie::Utilisateur, Some("1.0"))];
        let b = planifier_maj(&catalogue(), &etats, "0.20");
        assert_eq!(b.total(), 0);
        assert!(b.incompatibles.is_empty());
    }

    #[test]
    fn app_absente_est_une_maj_disponible() {
        // mediacenter pas installé -> proposé (première installation)
        let etats = vec![app("mediacenter", Categorie::Utilisateur, None)];
        let b = planifier_maj(&catalogue(), &etats, "0.20");
        assert_eq!(b.proposees.len(), 1);
        assert_eq!(b.proposees[0].de, None);
        assert_eq!(b.proposees[0].vers, "0.3");
    }

    #[test]
    fn maj_forcee_releve_plancher() {
        let etats = vec![app("dock", Categorie::Systeme, Some("0.19"))];
        let b = planifier_maj(&catalogue(), &etats, "0.20");
        // le plan d'install d'une MAJ système obligatoire doit relever le plancher
        assert_eq!(b.forcees[0].plan.etat_resultant.plancher_securite.as_deref(), Some("0.20"));
    }
}
