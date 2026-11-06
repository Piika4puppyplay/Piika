//! installateur.rs — la logique de l'installateur de paquets `.puppy` (UI de
//! l'installation par lot), demandée par Piika le 06/11. PURE : aucune E/S,
//! aucun privilège, aucun disque. L'UI dessine la liste ; l'autorité confinée
//! `puppy-autorite-paquets` (voir jail.rs) exécute le plan.
//!
//! Ce que ça couvre :
//!   - une LISTE de lot VIVANTE : on peut ajouter des .puppy au fil de l'eau,
//!     en retirer, et décocher ceux qu'on ne veut plus installer (les cases) ;
//!   - le CLASSEMENT d'affichage système / application / jeu / autre ;
//!   - la PORTE D'ADMISSION (sécurité) : un paquet SYSTÈME non signé ou falsifié
//!     est REFUSÉ, même pour une simple application ; un paquet falsifié est
//!     toujours refusé ; le verdict Forensic (Honey-Dog + analyse de
//!     l'installateur) peut refuser n'importe quel paquet ;
//!   - le PLAN DE LOT : pour chaque entrée cochée et admise, le plan d'actions
//!     (via apps::decider), dans l'ordre système d'abord, avec les refus listés
//!     en clair ;
//!   - la COMPENSATION : si une pose échoue en cours de lot, de quoi la défaire
//!     (les autres, déjà posées atomiquement, tiennent).
#![allow(dead_code)]

use crate::apps::{decider, Action, EtatApp, Operation, Plan};
use crate::jail::{classer, Genre, GenreAffiche};
use crate::registre::Categorie;

/// État de la signature Ed25519 d'un paquet (vérifiée en amont par verif.rs /
/// signature-paquet.c).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Signature {
    Valide,  // signé, signature correcte
    Absente, // pas de signature du tout
    Fausse,  // signature présente mais invalide (paquet modifié après signature)
}

/// Verdict de l'analyse de sécurité : Honey-Dog + l'analyse Forensic propre à
/// l'installateur (en plus). L'un ou l'autre peut refuser.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Forensic {
    Accepte,
    Refuse,
}

/// Décision d'admission d'un paquet dans le lef (avant même la politique A/B).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Admission {
    Admis,
    AdmisAvertissement, // app utilisateur non signée : autorisé mais prévenu
    Refuse,
}

/// La porte d'admission (règle de Piika). Ordre : un refus Forensic prime ;
/// sinon la signature décide, avec le durcissement « système ».
pub fn admission(signature: Signature, forensic: Forensic, affiche: GenreAffiche) -> Admission {
    if forensic == Forensic::Refuse {
        return Admission::Refuse;
    }
    match signature {
        Signature::Fausse => Admission::Refuse, // falsifié : jamais, pour personne
        Signature::Absente => {
            if affiche == GenreAffiche::Systeme {
                Admission::Refuse // paquet système non signé : REFUSÉ
            } else {
                Admission::AdmisAvertissement // app utilisateur non signée : avertir
            }
        }
        Signature::Valide => Admission::Admis,
    }
}

/// Une entrée de la liste d'installation.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entree {
    pub id: String,
    pub nom: String,
    pub version: String,
    pub genre: Genre,          // programme / thème / icônes (→ racine)
    pub confiance: Categorie,  // catégorie de CONFIANCE (catalogue) → système ou non
    pub affiche: GenreAffiche, // système / application / jeu / autre (calculé)
    pub signature: Signature,
    pub forensic: Forensic,
    pub securite: bool, // MAJ de sécurité (relève le plancher)
    pub coche: bool,    // cochée = à installer ; décochée = retirée du lot
}

impl Entree {
    /// Construit une entrée en calculant son classement d'affichage de façon
    /// sûre (« système » ne vient que de la confiance, pas du paquet).
    pub fn nouvelle(
        id: &str,
        nom: &str,
        version: &str,
        genre: Genre,
        confiance: Categorie,
        indice_categorie: Option<&str>,
        signature: Signature,
        forensic: Forensic,
        securite: bool,
    ) -> Entree {
        Entree {
            id: id.into(),
            nom: nom.into(),
            version: version.into(),
            genre,
            confiance,
            affiche: classer(confiance, indice_categorie),
            signature,
            forensic,
            securite,
            coche: true, // par défaut, un .puppy ouvert est coché
        }
    }

    pub fn admission(&self) -> Admission {
        admission(self.signature, self.forensic, self.affiche)
    }

    /// Motif de refus en clair (None si admise).
    pub fn motif_refus(&self) -> Option<&'static str> {
        match self.admission() {
            Admission::Refuse => Some(match (self.forensic, self.signature) {
                (Forensic::Refuse, _) => "Refusé par l'analyse de sécurité (Honey-Dog / Forensic)",
                (_, Signature::Fausse) => "Signature fausse : paquet modifié après sa signature",
                _ => "Paquet système non signé : refusé",
            }),
            _ => None,
        }
    }
}

/// La liste de lot, vivante.
#[derive(Clone, Debug, Default)]
pub struct Liste {
    pub entrees: Vec<Entree>,
}

impl Liste {
    pub fn nouvelle() -> Liste {
        Liste { entrees: Vec::new() }
    }

    /// Ajoute un .puppy au fil de l'eau. Si le même id est déjà là, la nouvelle
    /// entrée remplace l'ancienne (on garde l'état coché/décoché précédent).
    pub fn ajouter(&mut self, e: Entree) {
        if let Some(pos) = self.entrees.iter().position(|x| x.id == e.id) {
            let coche = self.entrees[pos].coche;
            self.entrees[pos] = Entree { coche, ..e };
        } else {
            self.entrees.push(e);
        }
    }

    /// Retire complètement une entrée de la liste.
    pub fn retirer(&mut self, id: &str) {
        self.entrees.retain(|x| x.id != id);
    }

    /// Coche ou décoche une entrée (la case). Décocher = la retirer du lot sans
    /// l'ôter de la liste.
    pub fn cocher(&mut self, id: &str, valeur: bool) {
        if let Some(e) = self.entrees.iter_mut().find(|x| x.id == id) {
            e.coche = valeur;
        }
    }

    pub fn nb(&self) -> usize {
        self.entrees.len()
    }
    pub fn nb_cochees(&self) -> usize {
        self.entrees.iter().filter(|e| e.coche).count()
    }

    /// La liste dans l'ordre d'affichage : système d'abord, puis application,
    /// jeu, autre ; à rang égal, par nom. (L'ordre n'est pas figé : il se
    /// recalcule à chaque ajout/retrait — « ça bouge au fil de l'eau ».)
    pub fn ordonnee(&self) -> Vec<&Entree> {
        let mut v: Vec<&Entree> = self.entrees.iter().collect();
        v.sort_by(|a, b| a.affiche.rang().cmp(&b.affiche.rang()).then_with(|| a.nom.cmp(&b.nom)));
        v
    }
}

/// Un élément du plan de lot : l'id et son plan d'actions A/B (via apps.rs).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ElementPlan {
    pub id: String,
    pub plan: Plan,
}

/// Le plan d'un lot : ce qui sera posé (dans l'ordre), et ce qui est refusé
/// (avec le motif en clair, pour l'affichage).
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct PlanLot {
    pub a_poser: Vec<ElementPlan>,
    pub refuses: Vec<(String, String)>, // (id, motif)
}

/// Construit le plan d'un lot. `etat_de` donne l'état A/B courant d'une app par
/// son id (l'appelant le fournit depuis le registre). Les entrées décochées sont
/// ignorées (l'utilisateur les a retirées) ; les entrées non admises ou refusées
/// par la politique A/B sont listées dans `refuses`.
pub fn planifier<F>(liste: &Liste, etat_de: F) -> PlanLot
where
    F: Fn(&str) -> EtatApp,
{
    let mut lot = PlanLot::default();
    for e in liste.ordonnee() {
        if !e.coche {
            continue;
        }
        if e.admission() == Admission::Refuse {
            lot.refuses.push((e.id.clone(), e.motif_refus().unwrap_or("Refusé").into()));
            continue;
        }
        let etat = etat_de(&e.id);
        match decider(&etat, &Operation::Installer { version: e.version.clone(), securite: e.securite }) {
            Ok(plan) => lot.a_poser.push(ElementPlan { id: e.id.clone(), plan }),
            Err(refus) => lot.refuses.push((e.id.clone(), refus.libelle().into())),
        }
    }
    lot
}

/// Compensation si la pose d'un élément échoue en cours de lot : comment défaire
/// CET élément (les autres, déjà posés atomiquement, restent valides et n'ont pas
/// à être touchés). Première installation (pas de précédente) → tout supprimer ;
/// sinon → rebasculer sur la précédente.
pub fn compensation(element: &ElementPlan) -> Vec<Action> {
    if element.plan.etat_resultant.precedente.is_some() {
        vec![Action::BasculerPrecedente]
    } else {
        vec![Action::Supprimer]
    }
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    fn etat_absent(id: &str, cat: Categorie) -> EtatApp {
        EtatApp { id: id.into(), categorie: cat, installee: None, precedente: None, iso: None, plancher_securite: None }
    }

    fn ent(id: &str, cat: Categorie, sig: Signature, forensic: Forensic) -> Entree {
        Entree::nouvelle(id, id, "1.0", Genre::Programme, cat, None, sig, forensic, false)
    }

    #[test]
    fn admission_paquet_systeme_non_signe_refuse() {
        assert_eq!(admission(Signature::Absente, Forensic::Accepte, GenreAffiche::Systeme), Admission::Refuse);
        // une app utilisateur non signée passe, avec avertissement
        assert_eq!(
            admission(Signature::Absente, Forensic::Accepte, GenreAffiche::Application),
            Admission::AdmisAvertissement
        );
    }

    #[test]
    fn admission_falsifie_toujours_refuse() {
        for a in [GenreAffiche::Systeme, GenreAffiche::Application, GenreAffiche::Jeu, GenreAffiche::Autre] {
            assert_eq!(admission(Signature::Fausse, Forensic::Accepte, a), Admission::Refuse);
        }
    }

    #[test]
    fn admission_forensic_prime() {
        // même signé et valide, un refus Forensic l'emporte
        assert_eq!(admission(Signature::Valide, Forensic::Refuse, GenreAffiche::Application), Admission::Refuse);
    }

    #[test]
    fn admission_signe_valide_ok() {
        assert_eq!(admission(Signature::Valide, Forensic::Accepte, GenreAffiche::Systeme), Admission::Admis);
    }

    #[test]
    fn liste_vivante_ajout_retrait_dedup() {
        let mut l = Liste::nouvelle();
        l.ajouter(ent("bloc-notes", Categorie::Utilisateur, Signature::Valide, Forensic::Accepte));
        l.ajouter(ent("jeu-serpent", Categorie::Utilisateur, Signature::Valide, Forensic::Accepte));
        assert_eq!(l.nb(), 2);
        // ré-ajouter le même id remplace, ne double pas
        l.ajouter(ent("bloc-notes", Categorie::Utilisateur, Signature::Valide, Forensic::Accepte));
        assert_eq!(l.nb(), 2);
        l.retirer("jeu-serpent");
        assert_eq!(l.nb(), 1);
    }

    #[test]
    fn decocher_garde_dans_la_liste_mais_hors_lot() {
        let mut l = Liste::nouvelle();
        l.ajouter(ent("bloc-notes", Categorie::Utilisateur, Signature::Valide, Forensic::Accepte));
        l.cocher("bloc-notes", false);
        assert_eq!(l.nb(), 1);
        assert_eq!(l.nb_cochees(), 0);
        let lot = planifier(&l, |id| etat_absent(id, Categorie::Utilisateur));
        assert!(lot.a_poser.is_empty() && lot.refuses.is_empty()); // ni posé ni refusé : juste ignoré
    }

    #[test]
    fn ordre_systeme_dabord() {
        let mut l = Liste::nouvelle();
        l.ajouter(Entree::nouvelle("zoulou", "Zoulou", "1.0", Genre::Programme, Categorie::Utilisateur, Some("jeu"), Signature::Valide, Forensic::Accepte, false));
        l.ajouter(Entree::nouvelle("alpha", "Alpha", "1.0", Genre::Programme, Categorie::Utilisateur, None, Signature::Valide, Forensic::Accepte, false));
        l.ajouter(Entree::nouvelle("dock", "Dock", "1.0", Genre::Programme, Categorie::Systeme, None, Signature::Valide, Forensic::Accepte, false));
        let ordre: Vec<&str> = l.ordonnee().iter().map(|e| e.id.as_str()).collect();
        assert_eq!(ordre, vec!["dock", "alpha", "zoulou"]); // système, puis appli (alpha), puis jeu (zoulou)
    }

    #[test]
    fn plan_de_lot_pose_les_admises_refuse_les_autres() {
        let mut l = Liste::nouvelle();
        l.ajouter(ent("bloc-notes", Categorie::Utilisateur, Signature::Valide, Forensic::Accepte)); // ok
        l.ajouter(ent("dock", Categorie::Systeme, Signature::Absente, Forensic::Accepte)); // système non signé -> refus
        l.ajouter(ent("triche", Categorie::Utilisateur, Signature::Fausse, Forensic::Accepte)); // falsifié -> refus
        let lot = planifier(&l, |id| etat_absent(id, if id == "dock" { Categorie::Systeme } else { Categorie::Utilisateur }));
        assert_eq!(lot.a_poser.len(), 1);
        assert_eq!(lot.a_poser[0].id, "bloc-notes");
        assert_eq!(lot.a_poser[0].plan.actions, vec![Action::Poser { version: "1.0".into() }]);
        let ids_refuses: Vec<&str> = lot.refuses.iter().map(|(i, _)| i.as_str()).collect();
        assert!(ids_refuses.contains(&"dock") && ids_refuses.contains(&"triche"));
    }

    #[test]
    fn compensation_premiere_install_supprime() {
        let mut l = Liste::nouvelle();
        l.ajouter(ent("bloc-notes", Categorie::Utilisateur, Signature::Valide, Forensic::Accepte));
        let lot = planifier(&l, |id| etat_absent(id, Categorie::Utilisateur));
        // première install (pas de précédente) -> compensation = supprimer
        assert_eq!(compensation(&lot.a_poser[0]), vec![Action::Supprimer]);
    }

    #[test]
    fn compensation_mise_a_jour_rebascule() {
        let mut l = Liste::nouvelle();
        l.ajouter(Entree::nouvelle("bloc-notes", "Bloc", "2.0", Genre::Programme, Categorie::Utilisateur, None, Signature::Valide, Forensic::Accepte, false));
        let lot = planifier(&l, |id| EtatApp {
            id: id.into(),
            categorie: Categorie::Utilisateur,
            installee: Some("1.0".into()),
            precedente: None,
            iso: None,
            plancher_securite: None,
        });
        // mise à jour (une précédente existe désormais) -> compensation = rebasculer
        assert_eq!(compensation(&lot.a_poser[0]), vec![Action::BasculerPrecedente]);
    }
}
