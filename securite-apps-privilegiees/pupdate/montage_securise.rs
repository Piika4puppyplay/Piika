//! montage_securise.rs — MONTAGE SÉCURISÉ des lecteurs chiffrés.
//!
//! Socle commun (Piika 06/11) au déchiffrement multi-OS, à l'explorateur et au
//! mode live. Deux idées, pures et testées ici ; le C fait l'ouverture dm-crypt,
//! l'analyse Honey-Dog et le montage réel.
//!
//! 1. ANTI-BRUTE-FORCE SANS DESTRUCTION. On ne supprime JAMAIS les données après
//!    X essais ratés (ce ne sont pas forcément nos disques : effacer serait une
//!    catastrophe). À la place, un **chrono évolutif** : chaque échec allonge le
//!    délai avant le prochain essai (croissance exponentielle plafonnée), ce qui
//!    rend le dictionnaire et le brute-force impraticables sans rien détruire.
//!
//! 2. JAMAIS DE MONTAGE À L'AVEUGLE. Un bon mot de passe n'entraîne PAS un
//!    montage immédiat : il ouvre un **sas Honey-Dog** qui impose une analyse.
//!      - refus de l'analyse  → lecteur NON monté, motif donné, et on dit la
//!        vérité : le code ÉTAIT bon (on ne ment pas) ;
//!      - analyse qui trouve du dangereux → NON monté (motif) ;
//!      - analyse propre + acceptée → on monte.
//!
//! Module PUR : aucune E/S, aucun privilège, aucune horloge (le délai est
//! calculé, c'est l'appelant qui attend). Jamais de panique.
#![allow(dead_code)]

/// Délai (en secondes) imposé AVANT de pouvoir retenter, après `echecs` essais
/// ratés consécutifs. Croissance exponentielle plafonnée : 0, 1, 2, 4, 8, …
/// jusqu'à `PLAFOND_S`. Aucun essai n'est jamais refusé définitivement, aucune
/// donnée n'est jamais effacée : seul le temps d'attente grandit.
pub const PLAFOND_S: u32 = 3600; // une heure : brute-force hors de portée

pub fn delai_apres_echecs(echecs: u32) -> u32 {
    if echecs == 0 {
        return 0;
    }
    // 2^(echecs-1), plafonné, sans débordement.
    let dec = echecs - 1;
    if dec >= 32 {
        return PLAFOND_S;
    }
    match 1u32.checked_shl(dec) {
        Some(v) => v.min(PLAFOND_S),
        None => PLAFOND_S,
    }
}

/// Décision après la saisie d'un mot de passe sur un lecteur.
#[derive(Debug, PartialEq, Eq)]
pub enum Decision {
    /// Mauvais code : on NE monte pas, on NE supprime rien. `essais` = nombre
    /// d'échecs consécutifs désormais, `delai_s` = attente avant le prochain essai.
    CodeIncorrect { essais: u32, delai_s: u32 },
    /// Bon code : le lecteur n'est PAS encore monté. Le sas Honey-Dog doit
    /// d'abord analyser (jamais de montage à l'aveugle).
    AttendreAnalyse,
}

/// Après une saisie. `code_bon` : le mot de passe ouvre-t-il le volume ?
/// `echecs_avant` : nombre d'échecs consécutifs déjà subis sur ce lecteur.
pub fn apres_saisie(code_bon: bool, echecs_avant: u32) -> Decision {
    if code_bon {
        Decision::AttendreAnalyse
    } else {
        let essais = echecs_avant.saturating_add(1);
        Decision::CodeIncorrect { essais, delai_s: delai_apres_echecs(essais) }
    }
}

/// Verdict du sas Honey-Dog (après qu'un bon code a ouvert le sas).
#[derive(Debug, PartialEq, Eq)]
pub enum Montage {
    /// L'utilisateur a refusé l'analyse : lecteur NON monté. `code_etait_bon`
    /// est vrai (on le dit sans mentir), `motif` explique pourquoi.
    NonMonteRefusAnalyse { code_etait_bon: bool, motif: &'static str },
    /// L'analyse a trouvé du contenu dangereux : lecteur NON monté.
    NonMonteDanger { motif: &'static str },
    /// Analyse acceptée et propre : on monte.
    Monter,
}

/// Décision du sas. `analyse_acceptee` : l'utilisateur a-t-il lancé l'analyse ?
/// `propre` : l'analyse Honey-Dog n'a-t-elle rien trouvé de dangereux ?
/// (Le code est forcément bon à ce stade : le sas ne s'ouvre qu'après un bon code.)
pub fn apres_analyse(analyse_acceptee: bool, propre: bool) -> Montage {
    if !analyse_acceptee {
        return Montage::NonMonteRefusAnalyse {
            code_etait_bon: true,
            motif: "analyse de securite refusee : le lecteur n'est pas monte (le code etait pourtant bon)",
        };
    }
    if !propre {
        return Montage::NonMonteDanger {
            motif: "analyse Honey-Dog : contenu dangereux detecte, lecteur non monte",
        };
    }
    Montage::Monter
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn delai_croit_et_plafonne() {
        assert_eq!(delai_apres_echecs(0), 0);
        assert_eq!(delai_apres_echecs(1), 1);
        assert_eq!(delai_apres_echecs(2), 2);
        assert_eq!(delai_apres_echecs(3), 4);
        assert_eq!(delai_apres_echecs(4), 8);
        // strictement croissant jusqu'au plafond, jamais de débordement
        let mut prec = 0;
        for e in 0..64 {
            let d = delai_apres_echecs(e);
            assert!(d >= prec);
            assert!(d <= PLAFOND_S);
            prec = d;
        }
        assert_eq!(delai_apres_echecs(1000), PLAFOND_S);
    }

    #[test]
    fn mauvais_code_ne_detruit_rien_et_attend() {
        // un mauvais code n'entraîne QUE de l'attente, jamais de suppression
        let d = apres_saisie(false, 0);
        assert_eq!(d, Decision::CodeIncorrect { essais: 1, delai_s: 1 });
        let d = apres_saisie(false, 4);
        assert_eq!(d, Decision::CodeIncorrect { essais: 5, delai_s: 16 });
    }

    #[test]
    fn bon_code_ouvre_le_sas_mais_ne_monte_pas() {
        assert_eq!(apres_saisie(true, 0), Decision::AttendreAnalyse);
        assert_eq!(apres_saisie(true, 99), Decision::AttendreAnalyse);
    }

    #[test]
    fn refus_analyse_ne_monte_pas_mais_dit_la_verite() {
        match apres_analyse(false, true) {
            Montage::NonMonteRefusAnalyse { code_etait_bon, .. } => assert!(code_etait_bon),
            autre => panic!("attendu NonMonteRefusAnalyse, obtenu {autre:?}"),
        }
    }

    #[test]
    fn danger_ne_monte_pas() {
        assert!(matches!(apres_analyse(true, false), Montage::NonMonteDanger { .. }));
    }

    #[test]
    fn propre_et_accepte_monte() {
        assert_eq!(apres_analyse(true, true), Montage::Monter);
    }

    #[test]
    fn jamais_de_montage_sans_analyse() {
        // quel que soit le nombre d'essais, un bon code n'aboutit jamais
        // directement à Monter : il faut passer par le sas.
        for e in 0..10 {
            assert_eq!(apres_saisie(true, e), Decision::AttendreAnalyse);
        }
    }
}
