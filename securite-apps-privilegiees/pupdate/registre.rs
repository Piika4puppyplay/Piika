//! registre.rs — PuppyRegistry : l'inventaire local des composants de PuppyOS
//! et le calcul de leur état, écrit en Rust (priorité Rust de Piika : ce module
//! parse du texte de catalogue qui vient d'un dépôt, donc d'une source NON
//! fiable — c'est le cas d'usage Rust par excellence).
//!
//! Ce module est en LECTURE SEULE : il décrit ce qui est installé et ce que le
//! catalogue propose. Il n'installe rien, ne télécharge rien, n'écrit rien sur
//! le disque. La vérification de la signature du catalogue (Ed25519, via
//! Monocypher en C) se fait AVANT d'appeler `parser_catalogue` sur un contenu ;
//! tant que la signature n'est pas vérifiée, une entrée de catalogue reste une
//! donnée suspecte et sert au plus à afficher « mise à jour disponible », jamais
//! à installer.
//!
//! Sûreté du parseur (exigence « catalogue mal formé → rejet propre ») :
//!   - bornes dures sur le nombre de lignes, d'entrées et la longueur des champs ;
//!   - identifiants limités à [a-z0-9-] (comme programme_id_valide en C) ;
//!   - aucun chemin : les noms de fichier ne peuvent contenir ni '/' ni "..".
#![allow(dead_code)]

use core::cmp::Ordering;

// ------------------------------- bornes dures -------------------------------
const MAX_LIGNES: usize = 20_000;
const MAX_ENTREES: usize = 2_000;
const MAX_CHAMP: usize = 256;
const MAX_ID: usize = 31;

// ------------------------------- catégorie ----------------------------------
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Categorie {
    Systeme,     // essentiel : désinstallation complète interdite, MAJ obligatoire
    Utilisateur, // installable/désinstallable, rollback libre
}

impl Categorie {
    fn depuis(s: &str) -> Categorie {
        match s {
            "systeme" | "système" | "system" => Categorie::Systeme,
            _ => Categorie::Utilisateur,
        }
    }
}

// --------------------------------- états ------------------------------------
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Etat {
    Installe,           // présent, pas d'info de catalogue pour comparer
    Absent,             // au catalogue mais pas installé
    AJour,              // installé, version = dernière proposée
    MajDisponible,      // installé, une version plus récente existe
    Obligatoire,        // MAJ disponible ET marquée obligatoire (sécurité)
    Incompatible,       // exige un OS plus récent que celui installé
    SignatureInvalide,  // le dépôt a fourni une entrée dont la signature est fausse
    DepotIndisponible,  // installé mais le catalogue n'a pas pu être lu (réseau)
    NonVerifiable,      // ni catalogue vérifié ni réseau : état inconnu
}

impl Etat {
    /// Libellé lisible en clair (UI). Pas de codename interne.
    pub fn libelle(self) -> &'static str {
        match self {
            Etat::Installe => "Installé",
            Etat::Absent => "Absent",
            Etat::AJour => "À jour",
            Etat::MajDisponible => "Mise à jour disponible",
            Etat::Obligatoire => "Mise à jour obligatoire",
            Etat::Incompatible => "Incompatible",
            Etat::SignatureInvalide => "Signature invalide",
            Etat::DepotIndisponible => "Dépôt indisponible",
            Etat::NonVerifiable => "Non vérifiable",
        }
    }
}

// --------------------- composant tel qu'installé (disque) -------------------
#[derive(Clone, Debug)]
pub struct Installe {
    pub id: String,
    pub nom: String,
    pub version: String,
    pub categorie: Categorie,
}

// ------------------- entrée de catalogue (source NON fiable) ----------------
#[derive(Clone, Debug)]
pub struct EntreeCatalogue {
    pub id: String,
    pub version: String,
    pub fichier: String,
    pub sha256: String,
    pub categorie: Categorie,
    pub obligatoire: bool,
    pub min_os: String, // "" si non précisé
}

// --------------------------- catalogue complet ------------------------------
#[derive(Clone, Debug)]
pub struct Catalogue {
    pub sequence: u64, // numéro monotone, anti-gel / anti-omission
    pub genere: String,
    pub entrees: Vec<EntreeCatalogue>,
}

impl Catalogue {
    fn trouver(&self, id: &str) -> Option<&EntreeCatalogue> {
        self.entrees.iter().find(|e| e.id == id)
    }
}

// --------------------------- fiche de registre ------------------------------
#[derive(Clone, Debug)]
pub struct Fiche {
    pub id: String,
    pub nom: String,
    pub categorie: Categorie,
    pub version_installee: Option<String>,
    pub version_disponible: Option<String>,
    pub obligatoire: bool,
    pub etat: Etat,
}

// ===========================================================================
//  Comparaison de versions « 0.19.3 » (numérique, champ par champ).
//  Un champ non numérique vaut 0. Les champs manquants valent 0 (0.19 == 0.19.0).
// ===========================================================================
pub fn comparer_version(a: &str, b: &str) -> Ordering {
    let mut ia = a.split('.');
    let mut ib = b.split('.');
    loop {
        match (ia.next(), ib.next()) {
            (None, None) => return Ordering::Equal,
            (ca, cb) => {
                let na: u64 = ca.unwrap_or("0").trim().parse().unwrap_or(0);
                let nb: u64 = cb.unwrap_or("0").trim().parse().unwrap_or(0);
                match na.cmp(&nb) {
                    Ordering::Equal => continue,
                    autre => return autre,
                }
            }
        }
    }
}

// ===========================================================================
//  Validation d'un identifiant de composant : [a-z0-9-], 1..=31, comme en C.
// ===========================================================================
fn id_valide(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= MAX_ID
        && id
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
}

/// Un nom de fichier de paquet : pas de chemin, pas de "..", longueur bornée,
/// se termine par .puppy / .puptheme / .puppycone.
fn fichier_valide(f: &str) -> bool {
    !f.is_empty()
        && f.len() <= MAX_CHAMP
        && !f.contains('/')
        && !f.contains('\\')
        && !f.contains("..")
        && !f.bytes().any(|c| c < 0x20)
        && (f.ends_with(".puppy") || f.ends_with(".puptheme") || f.ends_with(".puppycone"))
}

/// Un SHA-256 hexadécimal : exactement 64 caractères hex (ou vide = toléré mais
/// l'entrée ne sera pas installable tant qu'on n'a pas d'empreinte).
fn sha_valide(s: &str) -> bool {
    s.is_empty() || (s.len() == 64 && s.bytes().all(|c| c.is_ascii_hexdigit()))
}

// ===========================================================================
//  LE PARSEUR : texte de catalogue (NON fiable) -> Catalogue, ou erreur propre.
//  Format (voir RAPPORT-AUDIT-PUPPYUPDATE-REGISTRY.md §4) :
//    catalogue=1
//    sequence=42
//    genere=2026-11-06T00:00:00Z
//    composant=<id>
//      categorie=systeme|utilisateur
//      version=…
//      fichier=….puppy
//      sha256=…
//      obligatoire=oui|non
//      min_os=…
//  Les lignes vides et celles commençant par '#' sont ignorées. Les clés
//  inconnues sont ignorées (tolérance avant). Toute valeur hors-norme fait
//  rejeter l'ENTRÉE concernée sans faire tomber tout le catalogue, sauf une
//  en-tête absente/incohérente qui, elle, rejette tout.
// ===========================================================================
pub fn parser_catalogue(texte: &str) -> Result<Catalogue, &'static str> {
    if texte.len() > MAX_LIGNES * MAX_CHAMP {
        return Err("catalogue trop volumineux");
    }

    let mut version_format = 0u32;
    let mut sequence: Option<u64> = None;
    let mut genere = String::new();
    let mut entrees: Vec<EntreeCatalogue> = Vec::new();

    // entrée en cours de construction
    let mut cur_id: Option<String> = None;
    let mut cur = brouillon();

    let mut nb_lignes = 0usize;

    // ferme l'entrée en cours si elle est complète et valide
    let pousser = |entrees: &mut Vec<EntreeCatalogue>,
                   id: &mut Option<String>,
                   b: &mut Brouillon| {
        if let Some(idv) = id.take() {
            if id_valide(&idv)
                && !b.version.is_empty()
                && b.version.len() <= MAX_CHAMP
                && fichier_valide(&b.fichier)
                && sha_valide(&b.sha256)
                && entrees.len() < MAX_ENTREES
                && !entrees.iter().any(|e| e.id == idv)
            {
                entrees.push(EntreeCatalogue {
                    id: idv,
                    version: core::mem::take(&mut b.version),
                    fichier: core::mem::take(&mut b.fichier),
                    sha256: core::mem::take(&mut b.sha256),
                    categorie: Categorie::depuis(&b.categorie),
                    obligatoire: matches!(b.obligatoire.as_str(), "oui" | "yes" | "1" | "true"),
                    min_os: core::mem::take(&mut b.min_os),
                });
            }
        }
        *b = brouillon();
    };

    for ligne in texte.lines() {
        nb_lignes += 1;
        if nb_lignes > MAX_LIGNES {
            return Err("catalogue : trop de lignes");
        }
        let l = ligne.trim();
        if l.is_empty() || l.starts_with('#') {
            continue;
        }
        let (cle, val) = match l.split_once('=') {
            Some((c, v)) => (c.trim(), v.trim()),
            None => continue, // ligne sans '=' : ignorée (tolérance)
        };
        if val.len() > MAX_CHAMP {
            continue; // valeur aberrante : ignorée
        }
        match cle {
            "catalogue" => version_format = val.parse().unwrap_or(0),
            "sequence" => sequence = Some(val.parse().unwrap_or(0)),
            "genere" => genere = val.chars().take(64).collect(),
            "composant" | "component" => {
                // nouvelle entrée : pousser l'ancienne
                pousser(&mut entrees, &mut cur_id, &mut cur);
                cur_id = Some(val.to_string());
            }
            "categorie" | "category" => cur.categorie = val.to_string(),
            "version" => cur.version = val.to_string(),
            "fichier" | "file" => cur.fichier = val.to_string(),
            "sha256" => cur.sha256 = val.to_ascii_lowercase(),
            "obligatoire" | "mandatory" => cur.obligatoire = val.to_ascii_lowercase(),
            "min_os" => cur.min_os = val.to_string(),
            _ => {} // clé inconnue ignorée
        }
    }
    // pousser la dernière entrée
    pousser(&mut entrees, &mut cur_id, &mut cur);

    if version_format == 0 {
        return Err("catalogue : en-tête 'catalogue=' absente ou invalide");
    }
    let sequence = match sequence {
        Some(s) => s,
        None => return Err("catalogue : 'sequence' absente"),
    };
    Ok(Catalogue {
        sequence,
        genere,
        entrees,
    })
}

struct Brouillon {
    categorie: String,
    version: String,
    fichier: String,
    sha256: String,
    obligatoire: String,
    min_os: String,
}
fn brouillon() -> Brouillon {
    Brouillon {
        categorie: String::new(),
        version: String::new(),
        fichier: String::new(),
        sha256: String::new(),
        obligatoire: String::new(),
        min_os: String::new(),
    }
}

// ===========================================================================
//  CONSTRUCTION DU REGISTRE : fusion (installés × catalogue) -> fiches + états.
//
//  `catalogue_verifie` : Some(cat) si la signature Ed25519 a été vérifiée en
//  amont ; None si le catalogue n'a pas pu être lu/vérifié (réseau coupé,
//  signature fausse) — on le distingue par `motif_absence`.
// ===========================================================================
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum MotifAbsence {
    AucunReseau,       // -> DepotIndisponible pour les composants installés
    SignatureFausse,   // -> SignatureInvalide
    Inconnu,           // -> NonVerifiable
}

pub fn construire(
    installes: &[Installe],
    catalogue_verifie: Option<&Catalogue>,
    os_version: &str,
    motif_absence: MotifAbsence,
) -> Vec<Fiche> {
    let mut fiches: Vec<Fiche> = Vec::new();

    // 1. toutes les entrées installées
    for inst in installes {
        let (dispo, obligatoire, etat) = match catalogue_verifie {
            None => {
                let e = match motif_absence {
                    MotifAbsence::AucunReseau => Etat::DepotIndisponible,
                    MotifAbsence::SignatureFausse => Etat::SignatureInvalide,
                    MotifAbsence::Inconnu => Etat::NonVerifiable,
                };
                (None, false, e)
            }
            Some(cat) => match cat.trouver(&inst.id) {
                None => (None, false, Etat::Installe), // installé, pas au catalogue
                Some(e) => {
                    let cmp = comparer_version(&e.version, &inst.version);
                    let incompatible = !e.min_os.is_empty()
                        && comparer_version(os_version, &e.min_os) == Ordering::Less;
                    let etat = if incompatible {
                        Etat::Incompatible
                    } else if cmp == Ordering::Greater {
                        if e.obligatoire {
                            Etat::Obligatoire
                        } else {
                            Etat::MajDisponible
                        }
                    } else {
                        Etat::AJour
                    };
                    (Some(e.version.clone()), e.obligatoire, etat)
                }
            },
        };
        fiches.push(Fiche {
            id: inst.id.clone(),
            nom: inst.nom.clone(),
            categorie: inst.categorie,
            version_installee: Some(inst.version.clone()),
            version_disponible: dispo,
            obligatoire,
            etat,
        });
    }

    // 2. entrées du catalogue non installées -> Absent
    if let Some(cat) = catalogue_verifie {
        for e in &cat.entrees {
            if installes.iter().any(|i| i.id == e.id) {
                continue;
            }
            let incompatible = !e.min_os.is_empty()
                && comparer_version(os_version, &e.min_os) == Ordering::Less;
            fiches.push(Fiche {
                id: e.id.clone(),
                nom: e.id.clone(),
                categorie: e.categorie,
                version_installee: None,
                version_disponible: Some(e.version.clone()),
                obligatoire: e.obligatoire,
                etat: if incompatible { Etat::Incompatible } else { Etat::Absent },
            });
        }
    }

    fiches.sort_by(|a, b| {
        // système d'abord, puis par nom
        match (a.categorie, b.categorie) {
            (Categorie::Systeme, Categorie::Utilisateur) => Ordering::Less,
            (Categorie::Utilisateur, Categorie::Systeme) => Ordering::Greater,
            _ => a.nom.to_lowercase().cmp(&b.nom.to_lowercase()),
        }
    });
    fiches
}

/// Vérifie qu'un catalogue nouvellement reçu n'est pas un retour en arrière
/// (anti-gel/omission) : sa séquence doit être >= la dernière séquence vue.
pub fn sequence_acceptable(nouvelle: u64, derniere_vue: u64) -> bool {
    nouvelle >= derniere_vue
}

// ===========================================================================
//                                AUTOTESTS
//   rustc --test registre.rs -o /tmp/reg_test && /tmp/reg_test
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    fn cat_exemple() -> &'static str {
        "\
# catalogue applications-puppy/puppyos
catalogue=1
sequence=42
genere=2026-11-06T00:00:00Z
composant=mediacenter
  categorie=utilisateur
  version=0.2
  fichier=MediaRust-0.2.puppy
  sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  obligatoire=non
composant=dock
  categorie=systeme
  version=0.19
  fichier=Dock-0.19.puppy
  sha256=fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210
  obligatoire=oui
  min_os=0.19
"
    }

    #[test]
    fn versions() {
        assert_eq!(comparer_version("0.19.3", "0.19.2"), Ordering::Greater);
        assert_eq!(comparer_version("0.19", "0.19.0"), Ordering::Equal);
        assert_eq!(comparer_version("0.2", "0.19"), Ordering::Less);
        assert_eq!(comparer_version("1.0", "0.99.99"), Ordering::Greater);
        assert_eq!(comparer_version("abc", "0"), Ordering::Equal); // non num -> 0
    }

    #[test]
    fn parse_ok() {
        let c = parser_catalogue(cat_exemple()).expect("doit parser");
        assert_eq!(c.sequence, 42);
        assert_eq!(c.entrees.len(), 2);
        let mc = c.trouver("mediacenter").unwrap();
        assert_eq!(mc.version, "0.2");
        assert_eq!(mc.categorie, Categorie::Utilisateur);
        assert!(!mc.obligatoire);
        let dock = c.trouver("dock").unwrap();
        assert_eq!(dock.categorie, Categorie::Systeme);
        assert!(dock.obligatoire);
        assert_eq!(dock.min_os, "0.19");
    }

    #[test]
    fn entete_absente_rejette_tout() {
        let t = "sequence=1\ncomposant=x\nversion=1\nfichier=x.puppy\n";
        assert!(parser_catalogue(t).is_err());
    }

    #[test]
    fn sequence_absente_rejette() {
        let t = "catalogue=1\ncomposant=x\nversion=1\nfichier=x.puppy\n";
        assert!(parser_catalogue(t).is_err());
    }

    #[test]
    fn entree_mal_formee_ignoree_pas_tout() {
        // une entrée avec chemin dans le fichier (attaque) + une bonne entrée
        let t = "\
catalogue=1
sequence=5
composant=mechant
  version=1.0
  fichier=../../etc/passwd
composant=bon
  version=1.0
  fichier=Bon-1.0.puppy
  sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
";
        let c = parser_catalogue(t).unwrap();
        assert_eq!(c.entrees.len(), 1, "l'entrée avec chemin doit être rejetée");
        assert_eq!(c.entrees[0].id, "bon");
    }

    #[test]
    fn id_invalide_rejete() {
        let t = "\
catalogue=1
sequence=5
composant=MAJUSCULE
  version=1.0
  fichier=X.puppy
  sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
";
        let c = parser_catalogue(t).unwrap();
        assert_eq!(c.entrees.len(), 0);
    }

    #[test]
    fn sha_invalide_rejete() {
        let t = "\
catalogue=1
sequence=5
composant=x
  version=1.0
  fichier=X.puppy
  sha256=pastouthex
";
        let c = parser_catalogue(t).unwrap();
        assert_eq!(c.entrees.len(), 0);
    }

    #[test]
    fn doublon_ignore() {
        let t = "\
catalogue=1
sequence=5
composant=x
  version=1.0
  fichier=X-1.puppy
  sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
composant=x
  version=2.0
  fichier=X-2.puppy
  sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
";
        let c = parser_catalogue(t).unwrap();
        assert_eq!(c.entrees.len(), 1);
        assert_eq!(c.entrees[0].version, "1.0"); // la première gagne
    }

    #[test]
    fn fuzz_leger_ne_panique_pas() {
        // Entrées pathologiques : on vérifie seulement l'absence de panique.
        let beaucoup = "composant=x\n".repeat(100_000); // beaucoup d'entrées
        let gros = "a".repeat(1_000_000);
        let cas = [
            "",
            "=",
            "=\n=\n=",
            "catalogue=",
            "catalogue=1\nsequence=",
            "catalogue=1\nsequence=1\ncomposant=\nversion=\nfichier=",
            "catalogue=1\nsequence=99999999999999999999999999\n", // overflow -> 0
            "\0\0\0",
            beaucoup.as_str(),
            gros.as_str(),
        ];
        for t in cas {
            let _ = parser_catalogue(t); // ne doit pas paniquer
        }
    }

    fn installes_exemple() -> Vec<Installe> {
        vec![
            Installe { id: "mediacenter".into(), nom: "Media Center".into(), version: "0.1".into(), categorie: Categorie::Utilisateur },
            Installe { id: "dock".into(), nom: "Dock".into(), version: "0.19".into(), categorie: Categorie::Systeme },
            Installe { id: "orphelin".into(), nom: "Orphelin".into(), version: "1.0".into(), categorie: Categorie::Utilisateur },
        ]
    }

    #[test]
    fn registre_etats() {
        let c = parser_catalogue(cat_exemple()).unwrap();
        let f = construire(&installes_exemple(), Some(&c), "0.19", MotifAbsence::Inconnu);
        let get = |id: &str| f.iter().find(|x| x.id == id).unwrap().clone();

        // mediacenter 0.1 installé, 0.2 dispo, non obligatoire -> MAJ dispo
        assert_eq!(get("mediacenter").etat, Etat::MajDisponible);
        // dock 0.19 == 0.19 -> à jour
        assert_eq!(get("dock").etat, Etat::AJour);
        // orphelin installé mais hors catalogue -> Installé
        assert_eq!(get("orphelin").etat, Etat::Installe);
    }

    #[test]
    fn registre_obligatoire_et_absent() {
        // dock installé en 0.18 -> MAJ obligatoire ; mediacenter absent
        let inst = vec![
            Installe { id: "dock".into(), nom: "Dock".into(), version: "0.18".into(), categorie: Categorie::Systeme },
        ];
        let c = parser_catalogue(cat_exemple()).unwrap();
        let f = construire(&inst, Some(&c), "0.19", MotifAbsence::Inconnu);
        let get = |id: &str| f.iter().find(|x| x.id == id).unwrap().clone();
        assert_eq!(get("dock").etat, Etat::Obligatoire);
        assert_eq!(get("mediacenter").etat, Etat::Absent);
    }

    #[test]
    fn registre_incompatible() {
        // dock exige min_os 0.19, OS en 0.18 -> incompatible
        let inst = vec![
            Installe { id: "dock".into(), nom: "Dock".into(), version: "0.18".into(), categorie: Categorie::Systeme },
        ];
        let c = parser_catalogue(cat_exemple()).unwrap();
        let f = construire(&inst, Some(&c), "0.18", MotifAbsence::Inconnu);
        let get = |id: &str| f.iter().find(|x| x.id == id).unwrap().clone();
        assert_eq!(get("dock").etat, Etat::Incompatible);
    }

    #[test]
    fn registre_depot_indisponible() {
        let f = construire(&installes_exemple(), None, "0.19", MotifAbsence::AucunReseau);
        for fiche in &f {
            assert_eq!(fiche.etat, Etat::DepotIndisponible);
        }
    }

    #[test]
    fn registre_signature_fausse() {
        let f = construire(&installes_exemple(), None, "0.19", MotifAbsence::SignatureFausse);
        assert!(f.iter().all(|x| x.etat == Etat::SignatureInvalide));
    }

    #[test]
    fn anti_gel_sequence() {
        assert!(sequence_acceptable(42, 42));
        assert!(sequence_acceptable(43, 42));
        assert!(!sequence_acceptable(41, 42)); // retour en arrière refusé
    }

    #[test]
    fn tri_systeme_avant_utilisateur() {
        let c = parser_catalogue(cat_exemple()).unwrap();
        let f = construire(&installes_exemple(), Some(&c), "0.19", MotifAbsence::Inconnu);
        // la première fiche doit être de catégorie Système
        assert_eq!(f[0].categorie, Categorie::Systeme);
    }
}
