//! aide.rs — CŒUR LOGIQUE du Centre d'aide « PuppyHelp » (app `.puppy` à part).
//!
//! Demande Piika (06/11) : un centre d'aide qui est une application système
//! indépendante, **facultative**, **non monolithique**, supprimable/désactivable/
//! réinstallable depuis le gestionnaire d'applications. Les applications système
//! ne doivent **jamais** dépendre de lui pour fonctionner : leurs raccourcis
//! vérifient sa disponibilité, transmettent un identifiant de page valide,
//! affichent un message de secours si besoin, et **ne bloquent jamais** l'appli
//! appelante.
//!
//! Ce module est le CERVEAU (pur, testé) de ces garanties de sûreté. L'UI (style
//! skeuomorphe rétro Win98/XP, 3 habillages, « Où suis-je ? », recherche,
//! adaptatif écran/tablette/téléphone) est à construire par-dessus ; ici on
//! garantit les comportements qui ne doivent JAMAIS casser.
//!
//! Règles dures de Piika :
//!   - une app supprimée ou désactivée par l'utilisateur n'est **jamais**
//!     réinstallée de force, même très ancienne : on respecte son choix ;
//!   - une page défectueuse ne fait **pas** tomber tout le centre d'aide ;
//!   - la fenêtre d'erreur « aide indisponible » fonctionne **indépendamment**
//!     du centre d'aide lui-même ;
//!   - un raccourci ne bloque **jamais** l'application qui l'appelle.
//!
//! Module PUR : aucune E/S, aucun privilège, jamais de panique.
#![allow(dead_code)]

/// Nom du paquet système du centre d'aide.
pub const PAQUET: &str = "PuppyHelp";

/// État opérationnel du centre d'aide, vu par un appelant.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EtatAide {
    /// Installé, actif, à jour, intègre : utilisable.
    Operationnel,
    /// Supprimé par l'utilisateur.
    Desinstalle,
    /// Désactivé par l'utilisateur.
    Desactive,
    /// Trop ancien (plusieurs versions de retard).
    Obsolete,
    /// Présent mais signature/intégrité en défaut.
    Corrompu,
    /// Présent mais ne répond pas.
    Indisponible,
}

impl EtatAide {
    /// Vrai seulement si le centre d'aide peut réellement ouvrir une page.
    pub fn utilisable(self) -> bool {
        matches!(self, EtatAide::Operationnel)
    }
}

/// Le centre d'aide est **facultatif** : aucune app système n'en dépend.
/// Invariant de conception (toujours vrai).
pub fn aide_est_facultative() -> bool {
    true
}

/// La fenêtre de secours « aide indisponible » fonctionne **indépendamment** du
/// centre d'aide (sinon elle ne pourrait jamais s'afficher). Toujours vrai.
pub fn fenetre_secours_independante() -> bool {
    true
}

/// Ce que fait un raccourci d'aide (depuis n'importe quelle app ou le bureau).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResolutionRaccourci {
    /// Ouvrir directement la bonne page (aide opérationnelle ET identifiant valide).
    OuvrirPage,
    /// Afficher la fenêtre de secours (aide non opérationnelle, ou page invalide).
    /// Ne bloque JAMAIS l'appli appelante.
    AfficherSecours { motif: &'static str },
}

/// Résout un raccourci d'aide. `etat` : état du centre d'aide. `page_id_valide` :
/// l'appelant transmet-il un identifiant de page valide ? Ne bloque jamais
/// l'appli appelante : elle reçoit toujours une réponse immédiate.
pub fn resoudre_raccourci(etat: EtatAide, page_id_valide: bool) -> ResolutionRaccourci {
    if !etat.utilisable() {
        return ResolutionRaccourci::AfficherSecours {
            motif: "centre d'aide non operationnel : fenetre de secours (l'appli continue normalement)",
        };
    }
    if !page_id_valide {
        return ResolutionRaccourci::AfficherSecours {
            motif: "identifiant de page invalide : fenetre de secours",
        };
    }
    ResolutionRaccourci::OuvrirPage
}

/// État d'installation d'une app système (pour la politique de mise à jour).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EtatInstallation {
    /// Jamais installée (système neuf).
    JamaisInstalle,
    /// Installée et active.
    InstalleActif,
    /// Supprimée par l'utilisateur (choix explicite).
    SupprimeParUtilisateur,
    /// Désactivée par l'utilisateur (choix explicite).
    DesactiveParUtilisateur,
}

/// Décision de mise à jour / préinstallation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PolitiqueMaj {
    /// Préinstaller (système neuf, jamais installée).
    Preinstaller,
    /// Forcer la mise à jour (installée, active, mais obsolète) — comme les autres apps.
    ForcerMaj,
    /// Rien à faire (installée, active, à jour).
    RienAFaire,
    /// Respecter le choix de l'utilisateur : NE PAS réinstaller/réactiver de force,
    /// même si l'app est ancienne. (supprimée ou désactivée par l'utilisateur)
    RespecterChoix,
}

/// Politique de mise à jour du centre d'aide (et de toute app facultative).
/// Règle d'or : un choix utilisateur (suppression/désactivation) n'est **jamais**
/// écrasé par une préinstallation/MAJ forcée, quelle que soit l'ancienneté.
pub fn politique_maj(etat: EtatInstallation, obsolete: bool) -> PolitiqueMaj {
    match etat {
        EtatInstallation::SupprimeParUtilisateur | EtatInstallation::DesactiveParUtilisateur => {
            PolitiqueMaj::RespecterChoix
        }
        EtatInstallation::JamaisInstalle => PolitiqueMaj::Preinstaller,
        EtatInstallation::InstalleActif => {
            if obsolete {
                PolitiqueMaj::ForcerMaj
            } else {
                PolitiqueMaj::RienAFaire
            }
        }
    }
}

/// Chargement d'UNE page du centre d'aide (non monolithique). `existe` : la page
/// demandée existe-t-elle ? `intacte` : son module est-il intègre ?
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChargementPage {
    /// Page chargée.
    Ok,
    /// Erreur **isolée** à cette page : le reste du centre d'aide reste debout.
    ErreurPageIsolee { motif: &'static str },
}

/// Charge une page. Une page absente ou abîmée produit une erreur **isolée** :
/// elle ne fait jamais tomber tout le centre d'aide (exigence non monolithique).
pub fn charger_page(existe: bool, intacte: bool) -> ChargementPage {
    if !existe {
        return ChargementPage::ErreurPageIsolee { motif: "page d'aide introuvable" };
    }
    if !intacte {
        return ChargementPage::ErreurPageIsolee { motif: "page d'aide abimee : isolee, le reste de l'aide fonctionne" };
    }
    ChargementPage::Ok
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aide_operationnelle_et_page_valide_ouvre() {
        assert_eq!(resoudre_raccourci(EtatAide::Operationnel, true), ResolutionRaccourci::OuvrirPage);
    }

    #[test]
    fn aide_hs_affiche_secours_sans_jamais_bloquer() {
        for e in [
            EtatAide::Desinstalle, EtatAide::Desactive, EtatAide::Obsolete,
            EtatAide::Corrompu, EtatAide::Indisponible,
        ] {
            // quel que soit l'état HS, et même avec une page valide, on tombe sur
            // le secours — jamais de blocage de l'appli appelante
            assert!(matches!(resoudre_raccourci(e, true), ResolutionRaccourci::AfficherSecours { .. }));
            assert!(!e.utilisable());
        }
    }

    #[test]
    fn page_invalide_meme_aide_ok_affiche_secours() {
        assert!(matches!(
            resoudre_raccourci(EtatAide::Operationnel, false),
            ResolutionRaccourci::AfficherSecours { .. }
        ));
    }

    #[test]
    fn choix_utilisateur_jamais_ecrase() {
        // supprimée ou désactivée → on respecte, même obsolète
        for e in [EtatInstallation::SupprimeParUtilisateur, EtatInstallation::DesactiveParUtilisateur] {
            assert_eq!(politique_maj(e, false), PolitiqueMaj::RespecterChoix);
            assert_eq!(politique_maj(e, true), PolitiqueMaj::RespecterChoix); // même très ancienne
        }
    }

    #[test]
    fn preinstall_et_maj_forcee_comme_les_autres() {
        assert_eq!(politique_maj(EtatInstallation::JamaisInstalle, false), PolitiqueMaj::Preinstaller);
        assert_eq!(politique_maj(EtatInstallation::InstalleActif, true), PolitiqueMaj::ForcerMaj);
        assert_eq!(politique_maj(EtatInstallation::InstalleActif, false), PolitiqueMaj::RienAFaire);
    }

    #[test]
    fn une_page_cassee_n_abat_pas_tout() {
        assert_eq!(charger_page(true, true), ChargementPage::Ok);
        assert!(matches!(charger_page(false, true), ChargementPage::ErreurPageIsolee { .. }));
        assert!(matches!(charger_page(true, false), ChargementPage::ErreurPageIsolee { .. }));
    }

    #[test]
    fn invariants_de_surete() {
        assert!(aide_est_facultative());
        assert!(fenetre_secours_independante());
    }
}
