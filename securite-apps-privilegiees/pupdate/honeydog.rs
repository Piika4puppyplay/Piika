//! honeydog.rs — VERDICT des chiens de garde (Honey-Dog & co), cœur commun.
//!
//! Demande Piika (06/11) : les chiens sont le cœur de la sécurité. Quand un chien
//! (flair/antivirus, bac, etc.) trouve un fichier à problème, il ne supprime
//! JAMAIS tout seul. Deux idées :
//!
//! 1. QUARANTAINE PLUTÔT QUE SUPPRESSION. Par défaut, un fichier dangereux est
//!    mis en **quarantaine** : bloqué sous PuppyOS, mais **pas effacé** du
//!    lecteur. Supprimer, c'est éradiquer (irrécupérable) — donc jamais
//!    automatique : **seulement sur accord explicite du propriétaire**. « Il ne
//!    te supprime rien sans ton avis. »
//!
//! 2. ON N'ABÎME PAS L'OS VOISIN. Sur un volume en **lecture seule** (disque d'un
//!    autre OS), on ne peut rien écrire : « quarantaine » veut dire *ne pas
//!    exposer* le fichier sous PuppyOS et **noter son chemin**, sans rien toucher
//!    sur ce disque. La suppression y est de toute façon impossible.
//!
//! 3. L'HUMEUR EST PUREMENT COSMÉTIQUE. Énervé, fatigué, calme ou ignoré, le
//!    chien **protège exactement pareil**. L'humeur ne change JAMAIS une décision
//!    de sécurité : c'est garanti ici par le type (le verdict ne prend même pas
//!    l'humeur en paramètre) et par `protege_toujours`.
//!
//! Module PUR : aucune E/S, aucun privilège, jamais de panique. Le C fournit le
//! chemin exact et l'explication ; le chien applique (bloquer, effacer sur accord).
#![allow(dead_code)]

/// Gravité d'un fichier après analyse d'un chien.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Gravite {
    /// Rien trouvé.
    Propre,
    /// Douteux (heuristique, à surveiller).
    Suspect,
    /// Dangereux avéré (signature, bombe, exécutable interdit…).
    Dangereux,
}

/// Action décidée sur un fichier. JAMAIS de suppression sans accord explicite.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ActionFichier {
    /// Fichier propre : on laisse passer.
    Autoriser,
    /// Bloqué sous PuppyOS, **pas effacé** du lecteur (réversible). `sur_disque`
    /// est faux quand le volume est en lecture seule : on ne fait que *ne pas
    /// exposer* le fichier et noter son chemin, sans rien écrire sur ce disque.
    Quarantaine { motif: &'static str, sur_disque: bool },
    /// Éradication irrécupérable. N'est atteignable **qu'avec** l'accord explicite
    /// du propriétaire ET un volume inscriptible.
    Supprimer { motif: &'static str },
}

/// Décide l'action sur un fichier.
/// `gravite` : verdict du chien. `volume_inscriptible` : peut-on écrire sur ce
/// volume (faux = OS voisin en lecture seule). `accord_suppression` : le
/// propriétaire a-t-il **explicitement** demandé la suppression de CE fichier ?
///
/// Règles : propre → autoriser ; sinon quarantaine par défaut ; suppression
/// seulement si accord explicite ET volume inscriptible (sinon quarantaine).
pub fn verdict_fichier(
    gravite: Gravite,
    volume_inscriptible: bool,
    accord_suppression: bool,
) -> ActionFichier {
    match gravite {
        Gravite::Propre => ActionFichier::Autoriser,
        Gravite::Suspect | Gravite::Dangereux => {
            if accord_suppression && volume_inscriptible {
                ActionFichier::Supprimer {
                    motif: "suppression demandee explicitement par le proprietaire",
                }
            } else if accord_suppression && !volume_inscriptible {
                // accord donné mais disque voisin en lecture seule : on ne peut
                // pas effacer là-bas → quarantaine (on bloque sans rien écrire)
                ActionFichier::Quarantaine {
                    motif: "volume en lecture seule : fichier bloque sous PuppyOS, pas efface (impossible sur ce disque)",
                    sur_disque: false,
                }
            } else {
                // défaut : quarantaine, jamais de suppression sans accord
                ActionFichier::Quarantaine {
                    motif: "fichier a probleme mis en quarantaine (bloque, non efface) : ton accord est requis pour supprimer",
                    sur_disque: volume_inscriptible,
                }
            }
        }
    }
}

/// Humeur du chien — **purement cosmétique** (affichage, widget sidebar, BD).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Humeur {
    Calme,
    Content,
    Fatigue,
    Excite,
    Enerve,
    Ignore,
}

/// Le chien protège-t-il toujours aussi bien, quelle que soit son humeur ?
/// Réponse invariable : **oui**. L'humeur ne touche jamais la protection.
pub fn protege_toujours(_humeur: Humeur) -> bool {
    true
}

/// Jamais de suppression automatique : sans accord explicite, l'action n'est
/// jamais `Supprimer`, quelle que soit la gravité ou le volume.
pub fn jamais_supprimer_sans_accord(gravite: Gravite, volume_inscriptible: bool) -> bool {
    !matches!(verdict_fichier(gravite, volume_inscriptible, false), ActionFichier::Supprimer { .. })
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn propre_passe() {
        assert_eq!(verdict_fichier(Gravite::Propre, true, false), ActionFichier::Autoriser);
        assert_eq!(verdict_fichier(Gravite::Propre, true, true), ActionFichier::Autoriser);
    }

    #[test]
    fn danger_sans_accord_va_en_quarantaine_jamais_supprime() {
        for g in [Gravite::Suspect, Gravite::Dangereux] {
            match verdict_fichier(g, true, false) {
                ActionFichier::Quarantaine { sur_disque, .. } => assert!(sur_disque),
                autre => panic!("sans accord, attendu quarantaine, obtenu {autre:?}"),
            }
        }
    }

    #[test]
    fn suppression_seulement_avec_accord_explicite_et_volume_inscriptible() {
        // accord + inscriptible → suppression
        assert!(matches!(
            verdict_fichier(Gravite::Dangereux, true, true),
            ActionFichier::Supprimer { .. }
        ));
        // accord mais lecture seule → quarantaine (pas de suppression possible)
        match verdict_fichier(Gravite::Dangereux, false, true) {
            ActionFichier::Quarantaine { sur_disque, .. } => assert!(!sur_disque),
            autre => panic!("lecture seule : attendu quarantaine, obtenu {autre:?}"),
        }
    }

    #[test]
    fn jamais_de_suppression_automatique() {
        // l'invariant central de Piika : « il ne te supprime rien sans ton avis »
        for g in [Gravite::Propre, Gravite::Suspect, Gravite::Dangereux] {
            for inscriptible in [true, false] {
                assert!(jamais_supprimer_sans_accord(g, inscriptible));
            }
        }
    }

    #[test]
    fn os_voisin_lecture_seule_bloque_sans_rien_ecrire() {
        match verdict_fichier(Gravite::Dangereux, false, false) {
            ActionFichier::Quarantaine { sur_disque, .. } => assert!(!sur_disque),
            autre => panic!("attendu quarantaine hors disque, obtenu {autre:?}"),
        }
    }

    #[test]
    fn humeur_ne_change_jamais_la_protection() {
        for h in [
            Humeur::Calme, Humeur::Content, Humeur::Fatigue,
            Humeur::Excite, Humeur::Enerve, Humeur::Ignore,
        ] {
            assert!(protege_toujours(h));
        }
        // garanti aussi par le type : verdict_fichier n'a aucun paramètre d'humeur.
    }
}
