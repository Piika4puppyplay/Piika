//! chien_dormant.rs — LE 4e CHIEN : dernier rempart dormant, scellé.
//!
//! Demande Piika (06/11) : un chien chiffré qui dort, et qui ne s'éveille que si
//! un problème très grave menace l'OS et qu'il faut du renfort pour se protéger.
//! Il se déchiffre quand c'est nécessaire, défend au maximum, puis se re-scelle
//! une fois le danger éliminé. Mystérieux, non affiché dans l'OS. S'il fait ramer
//! à fond pendant son intervention, tant pis : c'est le prix du dernier rempart.
//!
//! GARDE-FOU (doctrine PuppyOS, confirmé) : « protéger à tout prix » veut dire
//! **tout verrouiller**, JAMAIS détruire les données ni enfermer le propriétaire
//! dehors. Et il n'a **aucun pouvoir** que la sécurité normale n'a pas : c'est
//! une posture d'urgence pré-armée, pas une arme secrète (sinon ce serait lui la
//! plus grosse cible). Il ne s'éveille que sur un signal **grave ET signé**.
//!
//! Module PUR : aucune E/S, aucun privilège, jamais de panique. Le C fait le
//! déchiffrement (coffre TPM existant), applique la posture et re-scelle.
#![allow(dead_code)]

/// Niveau de menace constaté par la sécurité normale.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Menace {
    /// Rien d'anormal.
    Aucune,
    /// Incident ordinaire (un fichier douteux, une app qui plante) : la sécurité
    /// normale suffit, le dernier rempart NE s'éveille pas.
    Ordinaire,
    /// Menace grave et avérée pour l'intégrité de l'OS : renfort nécessaire.
    Grave,
}

/// Décision d'éveil du chien dormant.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reveil {
    /// Il reste scellé (pas assez grave, ou signal non prouvé).
    ResteScelle { motif: &'static str },
    /// Il s'éveille et prend la posture de défense.
    Eveil { posture: Posture },
}

/// Posture de défense du dernier rempart. **Tout est défensif, rien n'est
/// destructif.** Les deux derniers champs sont des garanties invariables.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Posture {
    /// Couper le réseau (isoler la machine).
    pub couper_reseau: bool,
    /// Forcer tout le stockage en lecture seule (geler les dégâts).
    pub forcer_lecture_seule: bool,
    /// Quarantaine agressive de tout ce qui est suspect (bloqué, pas effacé).
    pub quarantaine_agressive: bool,
    /// Re-sceller immédiatement les 2 apps privilégiées (coffre).
    pub sceller_apps_privilegiees: bool,
    /// Exiger la ré-authentification du propriétaire avant tout retour normal.
    pub exiger_reauth_proprietaire: bool,
    /// GARANTIE : ne détruit JAMAIS les données du propriétaire. Toujours vrai.
    pub jamais_detruire_donnees: bool,
    /// GARANTIE : n'enferme JAMAIS le propriétaire dehors. Toujours vrai.
    pub jamais_enfermer_proprietaire: bool,
}

/// La seule posture possible : tout verrouiller, rien détruire.
pub fn posture_defense() -> Posture {
    Posture {
        couper_reseau: true,
        forcer_lecture_seule: true,
        quarantaine_agressive: true,
        sceller_apps_privilegiees: true,
        exiger_reauth_proprietaire: true,
        jamais_detruire_donnees: true,
        jamais_enfermer_proprietaire: true,
    }
}

/// Décide si le chien dormant s'éveille.
/// `menace` : niveau constaté. `signal_signe_valide` : le signal d'alerte est-il
/// authentifié (signé) ? Il ne s'éveille QUE sur `Grave` **et** un signal signé
/// valide — jamais sur un simple soupçon, jamais sur un signal non prouvé (ce qui
/// bloque un faux réveil provoqué par un attaquant).
pub fn decider_reveil(menace: Menace, signal_signe_valide: bool) -> Reveil {
    match menace {
        Menace::Aucune | Menace::Ordinaire => Reveil::ResteScelle {
            motif: "pas assez grave : la securite normale suffit, le dernier rempart dort",
        },
        Menace::Grave => {
            if signal_signe_valide {
                Reveil::Eveil { posture: posture_defense() }
            } else {
                Reveil::ResteScelle {
                    motif: "signal d'alerte non authentifie : on ne reveille pas sur un signal non prouve",
                }
            }
        }
    }
}

/// Obligations une fois le danger éliminé : on re-scelle, on efface les secrets
/// d'éveil de la RAM. Le chien se rendort.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FinIntervention {
    /// Re-chiffrer / re-sceller le chien dans le coffre.
    pub re_sceller: bool,
    /// Zéroïser en RAM les secrets utilisés pendant l'éveil.
    pub zeroiser_secrets_eveil: bool,
    /// Lever le réseau coupé et la lecture seule seulement après ré-auth du
    /// propriétaire (il n'est jamais enfermé dehors).
    pub retablir_apres_reauth: bool,
}

/// Le contrat de fin d'intervention.
pub fn apres_danger_elimine() -> FinIntervention {
    FinIntervention {
        re_sceller: true,
        zeroiser_secrets_eveil: true,
        retablir_apres_reauth: true,
    }
}

/// Vrai si cet éveil mène bien à une posture qui ne détruit rien et n'enferme
/// personne (invariant de sûreté du dernier rempart).
pub fn eveil_sans_degats(menace: Menace, signal_signe_valide: bool) -> bool {
    match decider_reveil(menace, signal_signe_valide) {
        Reveil::ResteScelle { .. } => true,
        Reveil::Eveil { posture } => {
            posture.jamais_detruire_donnees && posture.jamais_enfermer_proprietaire
        }
    }
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dort_tant_que_pas_grave() {
        for m in [Menace::Aucune, Menace::Ordinaire] {
            assert!(matches!(decider_reveil(m, true), Reveil::ResteScelle { .. }));
        }
    }

    #[test]
    fn grave_et_signe_eveille() {
        assert!(matches!(
            decider_reveil(Menace::Grave, true),
            Reveil::Eveil { .. }
        ));
    }

    #[test]
    fn grave_mais_signal_non_prouve_ne_reveille_pas() {
        // un attaquant ne doit pas pouvoir déclencher un faux réveil
        assert!(matches!(
            decider_reveil(Menace::Grave, false),
            Reveil::ResteScelle { .. }
        ));
    }

    #[test]
    fn la_posture_ne_detruit_jamais_et_n_enferme_jamais() {
        let p = posture_defense();
        assert!(p.jamais_detruire_donnees);
        assert!(p.jamais_enfermer_proprietaire);
        // et elle verrouille bien tout le reste
        assert!(p.couper_reseau);
        assert!(p.forcer_lecture_seule);
        assert!(p.quarantaine_agressive);
        assert!(p.sceller_apps_privilegiees);
        assert!(p.exiger_reauth_proprietaire);
    }

    #[test]
    fn invariant_eveil_sans_degats_dans_tous_les_cas() {
        for m in [Menace::Aucune, Menace::Ordinaire, Menace::Grave] {
            for s in [true, false] {
                assert!(eveil_sans_degats(m, s), "degats possibles pour {m:?}/{s}");
            }
        }
    }

    #[test]
    fn fin_intervention_rescelle_et_efface() {
        let f = apres_danger_elimine();
        assert!(f.re_sceller);
        assert!(f.zeroiser_secrets_eveil);
        assert!(f.retablir_apres_reauth);
    }
}
