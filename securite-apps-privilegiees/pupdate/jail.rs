//! jail.rs — la « prison de chemin » et les garde-fous de l'autorité confinée
//! `puppy-autorite-paquets` (le NOUVEAU privilège demandé par Piika le 06/11).
//!
//! Idée : séparer le pouvoir d'installer un `.puppy` du gros gardien
//! `puppy-autorite` (qui gère aussi comptes, USB, extinction, mises à jour OS…).
//! Cette autorité-ci ne sait faire qu'UNE chose — poser / retirer / revenir en
//! arrière une application — et ne peut toucher QUE trois racines. Elle ne peut
//! PAS approcher le noyau (`/system/versions`), l'ESP/boot, les clés
//! (`/system/etc`), les documents (`/gamelles`), le journal… Même compromise,
//! elle est muselée par ces règles.
//!
//! Ce module est PUR : il décide ce qui est permis, il ne touche jamais le
//! disque. Les chemins qu'il calcule, et la vérification `chemin_autorise`, sont
//! la dernière ligne de défense (en plus de O_NOFOLLOW / realpath côté exécuteur
//! privilégié). On sépare exprès la décision (testable, sans risque) de
//! l'exécution (privilégiée).
#![allow(dead_code)]

use crate::registre::Categorie;

// --------------------------------------------------------------------------
//  Les SEULES racines où un .puppy peut poser des fichiers.
// --------------------------------------------------------------------------
pub const RACINE_PROGRAMMES: &str = "/system/programmes";
pub const RACINE_PROGRAMMES_ISO: &str = "/system/programmes-iso";
pub const RACINE_THEMES: &str = "/system/themes";
pub const RACINE_ICONES: &str = "/system/icones";

/// L'ensemble des racines autorisées (programmes + leur miroir ISO + thèmes + icônes).
pub const RACINES_AUTORISEES: &[&str] =
    &[RACINE_PROGRAMMES, RACINE_PROGRAMMES_ISO, RACINE_THEMES, RACINE_ICONES];

/// Préfixes explicitement interdits — jamais, même par erreur. L'autorité
/// confinée refuse tout chemin qui tombe là-dessous, AVANT même de regarder les
/// racines autorisées. C'est la muselière : le noyau, l'ESP/boot, les clés, les
/// documents de l'utilisateur, le journal de sécurité, les pseudo-systèmes.
pub const PREFIXES_INTERDITS: &[&str] = &[
    "/system/versions", // le noyau et l'OS lui-même (jamais via un .puppy)
    "/system/etc",      // configuration système + clés publiques embarquées
    "/system/journal",  // journal de sécurité (chaîné)
    "/boot",
    "/efi",
    "/EFI",
    "/gamelles", // les documents de l'utilisateur
    "/run",
    "/proc",
    "/sys",
    "/dev",
];

const MAX_ID: usize = 31;

/// Le genre d'un paquet, qui fixe sa racine. (Pas la catégorie système/user,
/// qui est une POLITIQUE venant du catalogue de confiance — voir `classer`.)
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Genre {
    Programme,
    Theme,
    Icones,
}

impl Genre {
    /// Depuis le champ `type=` de puppy.info.
    pub fn depuis_type(t: &str) -> Option<Genre> {
        match t {
            "programme" => Some(Genre::Programme),
            "theme" => Some(Genre::Theme),
            "icones" => Some(Genre::Icones),
            _ => None,
        }
    }
    pub fn racine(self) -> &'static str {
        match self {
            Genre::Programme => RACINE_PROGRAMMES,
            Genre::Theme => RACINE_THEMES,
            Genre::Icones => RACINE_ICONES,
        }
    }
    /// Seuls les programmes ont un miroir ISO (retour à la version d'origine).
    pub fn racine_iso(self) -> Option<&'static str> {
        match self {
            Genre::Programme => Some(RACINE_PROGRAMMES_ISO),
            _ => None,
        }
    }
    /// L'extension de fichier attendue, pour recouper avec le contenu.
    pub fn extension(self) -> &'static str {
        match self {
            Genre::Programme => ".puppy",
            Genre::Theme => ".puptheme",
            Genre::Icones => ".puppycone",
        }
    }
}

/// Identifiant d'application : [a-z0-9-], 1..=31 (comme `programme_id_valide` en C
/// et `id_valide` du registre). Pas de point, pas de barre, pas de « .. ».
pub fn id_valide(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= MAX_ID
        && id
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
}

/// Raisons de refus de la prison de chemin (toutes affichables en clair).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RefusJail {
    IdInvalide,
    ExtensionIncoherente, // le contenu (type=) ne colle pas au nom de fichier
}

impl RefusJail {
    pub fn libelle(self) -> &'static str {
        match self {
            RefusJail::IdInvalide => "Identifiant d'application invalide",
            RefusJail::ExtensionIncoherente => "Le contenu ne correspond pas à l'extension du fichier",
        }
    }
}

/// Les quatre chemins confinés d'une application, tous STRICTEMENT sous la
/// racine du genre. L'autorité ne reçoit jamais un chemin brut de l'UI : elle
/// recalcule ceux-ci elle-même à partir de (genre, id) validés.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CheminsConfines {
    pub cible: String,      // <racine>/<id>            version installée
    pub precedent: String,  // <racine>/<id>.precedent  version de rollback
    pub stagiaire: String,  // <racine>/<id>.part       zone d'extraction (install atomique)
    pub iso: Option<String>, // <racine_iso>/<id>       version d'origine ISO (programmes)
}

/// Calcule les chemins confinés d'une application. Seule porte d'entrée : on
/// part de (genre, id) — jamais d'un chemin fourni par l'appelant.
pub fn chemins(genre: Genre, id: &str) -> Result<CheminsConfines, RefusJail> {
    if !id_valide(id) {
        return Err(RefusJail::IdInvalide);
    }
    let r = genre.racine();
    Ok(CheminsConfines {
        cible: format!("{r}/{id}"),
        precedent: format!("{r}/{id}.precedent"),
        stagiaire: format!("{r}/{id}.part"),
        iso: genre.racine_iso().map(|ri| format!("{ri}/{id}")),
    })
}

/// Le nom de fichier `.puppy`/.puptheme/.puppycone doit coller au `type=` lu
/// dans puppy.info (anti-déguisement : un thème renommé en .puppy est refusé).
pub fn extension_coherente(genre: Genre, nom_fichier: &str) -> Result<(), RefusJail> {
    if nom_fichier.ends_with(genre.extension()) {
        Ok(())
    } else {
        Err(RefusJail::ExtensionIncoherente)
    }
}

/// `p` est-il sous `prefixe` (égal, ou suivi d'un « / ») ? Évite que
/// « /system/programmes » laisse passer « /system/programmes-evil ».
fn sous(p: &str, prefixe: &str) -> bool {
    p == prefixe || (p.len() > prefixe.len() && p.as_bytes()[prefixe.len()] == b'/' && p.starts_with(prefixe))
}

/// LA vérification de la muselière : ce chemin absolu est-il permis à l'autorité
/// confinée ? Vrai seulement s'il est strictement sous une racine autorisée, pas
/// sous un préfixe interdit, absolu, sans « .. », sans « // », sans octet
/// dangereux (NUL, barre inversée, retour à la ligne). Défense « au cordeau » :
/// tout chemin contenant « .. » est refusé d'office.
pub fn chemin_autorise(p: &str) -> bool {
    if !p.starts_with('/') || p.len() > 4096 {
        return false;
    }
    if p.contains("..") || p.contains("//") {
        return false;
    }
    if p.as_bytes().iter().any(|&b| b == 0 || b == b'\\' || b == b'\n' || b == b'\r') {
        return false;
    }
    for f in PREFIXES_INTERDITS {
        if sous(p, f) {
            return false;
        }
    }
    RACINES_AUTORISEES.iter().any(|r| sous(p, r))
}

// --------------------------------------------------------------------------
//  Classement d'affichage : système / application / jeu / autre.
//  SÉCURITÉ : « système » ne peut JAMAIS être auto-déclaré par le paquet — il
//  vient de la catégorie de CONFIANCE (le catalogue signé / le registre). Le
//  champ `categorie=` de puppy.info n'est qu'un indice cosmétique pour
//  distinguer application / jeu / autre.
// --------------------------------------------------------------------------
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum GenreAffiche {
    Systeme,
    Application,
    Jeu,
    Autre,
}

impl GenreAffiche {
    pub fn libelle(self) -> &'static str {
        match self {
            GenreAffiche::Systeme => "système",
            GenreAffiche::Application => "application",
            GenreAffiche::Jeu => "jeu",
            GenreAffiche::Autre => "autre",
        }
    }
    /// Ordre d'affichage dans la liste : système d'abord (le plus sensible),
    /// puis applications, puis jeux, puis le reste.
    pub fn rang(self) -> u8 {
        match self {
            GenreAffiche::Systeme => 0,
            GenreAffiche::Application => 1,
            GenreAffiche::Jeu => 2,
            GenreAffiche::Autre => 3,
        }
    }
}

/// `confiance` = la catégorie venue du catalogue signé / registre (seule source
/// de vérité pour « système »). `indice` = le champ `categorie=` de puppy.info
/// (cosmétique). Une app de confiance=Systeme s'affiche TOUJOURS « système »,
/// quoi que prétende le paquet ; une app utilisateur ne peut jamais se faire
/// passer pour système.
pub fn classer(confiance: Categorie, indice: Option<&str>) -> GenreAffiche {
    if confiance == Categorie::Systeme {
        return GenreAffiche::Systeme;
    }
    match indice.map(|s| s.trim().to_ascii_lowercase()).as_deref() {
        Some("jeu") | Some("jeux") | Some("game") => GenreAffiche::Jeu,
        Some("systeme") | Some("système") | Some("system") => GenreAffiche::Application, // tentative ignorée
        Some("autre") | Some("autres") | Some("other") => GenreAffiche::Autre,
        _ => GenreAffiche::Application,
    }
}

// --------------------------------------------------------------------------
//  Anti-bombe / anti-débordement : bornes de décompression d'un .puppy.
//  Un .puppy est un conteneur SHA-256 d'entrées (chemin relatif + taille). Avant
//  d'extraire, on refuse tout ce qui pourrait saturer le disque ou déborder un
//  tampon : trop gros, trop de fichiers, trop profond, chemin trop long, chemin
//  illégal (absolu, « .. », barre inversée), ou ratio de décompression délirant.
// --------------------------------------------------------------------------
#[derive(Clone, Copy, Debug)]
pub struct Limites {
    pub taille_totale_max: u64, // somme décompressée
    pub fichiers_max: u32,
    pub taille_fichier_max: u64,
    pub profondeur_max: usize, // nombre de segments de chemin
    pub chemin_octets_max: usize,
    pub ratio_max: u64, // décompressé / compressé
}

impl Limites {
    /// Bornes par défaut, larges pour une vraie application mais mortelles pour
    /// une bombe. (512 Mio au total, 256 Mio par fichier, 20000 entrées.)
    pub const DEFAUT: Limites = Limites {
        taille_totale_max: 512 * 1024 * 1024,
        fichiers_max: 20_000,
        taille_fichier_max: 256 * 1024 * 1024,
        profondeur_max: 32,
        chemin_octets_max: 255,
        ratio_max: 200,
    };
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RefusBombe {
    CheminIllegal, // absolu, « .. », barre inversée, vide, octet de contrôle
    CheminTropLong,
    TropProfond,
    FichierTropGros,
    TropDeFichiers,
    TropGros,       // somme décompressée au-dessus du plafond
    RatioExcessif,  // décompressé / compressé délirant (bombe)
}

impl RefusBombe {
    pub fn libelle(self) -> &'static str {
        match self {
            RefusBombe::CheminIllegal => "Chemin de fichier interdit dans le paquet",
            RefusBombe::CheminTropLong => "Chemin de fichier trop long",
            RefusBombe::TropProfond => "Arborescence trop profonde",
            RefusBombe::FichierTropGros => "Fichier trop gros",
            RefusBombe::TropDeFichiers => "Trop de fichiers",
            RefusBombe::TropGros => "Paquet décompressé trop gros",
            RefusBombe::RatioExcessif => "Taux de décompression anormal (bombe suspectée)",
        }
    }
}

/// Un chemin d'entrée de paquet est-il sain ? Relatif, pas de « .. », pas de
/// barre inversée, pas d'octet de contrôle, pas vide, longueur et profondeur
/// bornées.
pub fn verifier_chemin_entree(chemin: &str, lim: &Limites) -> Result<(), RefusBombe> {
    if chemin.is_empty() || chemin.len() > lim.chemin_octets_max {
        return Err(if chemin.is_empty() { RefusBombe::CheminIllegal } else { RefusBombe::CheminTropLong });
    }
    if chemin.starts_with('/') || chemin.contains("..") || chemin.contains('\\') {
        return Err(RefusBombe::CheminIllegal);
    }
    if chemin.as_bytes().iter().any(|&b| b < 0x20) {
        return Err(RefusBombe::CheminIllegal);
    }
    let profondeur = chemin.split('/').filter(|s| !s.is_empty()).count();
    if profondeur == 0 || profondeur > lim.profondeur_max {
        return Err(RefusBombe::TropProfond);
    }
    Ok(())
}

/// Vérifie une entrée (chemin + taille décompressée) contre les bornes.
pub fn verifier_entree(chemin: &str, taille: u64, lim: &Limites) -> Result<(), RefusBombe> {
    verifier_chemin_entree(chemin, lim)?;
    if taille > lim.taille_fichier_max {
        return Err(RefusBombe::FichierTropGros);
    }
    Ok(())
}

/// Vérifie les cumuls : nombre d'entrées, taille totale décompressée, et ratio
/// de décompression par rapport à la taille compressée du fichier .puppy.
pub fn verifier_cumul(nb_entrees: u32, total_decompresse: u64, taille_compressee: u64, lim: &Limites) -> Result<(), RefusBombe> {
    if nb_entrees > lim.fichiers_max {
        return Err(RefusBombe::TropDeFichiers);
    }
    if total_decompresse > lim.taille_totale_max {
        return Err(RefusBombe::TropGros);
    }
    // ratio : on ne déclenche que passé un petit plancher (un gros paquet
    // légitime a un ratio faible ; une bombe a un ratio énorme sur peu d'octets).
    if taille_compressee > 0 && total_decompresse / taille_compressee.max(1) > lim.ratio_max && total_decompresse > 16 * 1024 {
        return Err(RefusBombe::RatioExcessif);
    }
    Ok(())
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chemins_confines_sous_la_racine() {
        let c = chemins(Genre::Programme, "bloc-notes").unwrap();
        assert_eq!(c.cible, "/system/programmes/bloc-notes");
        assert_eq!(c.precedent, "/system/programmes/bloc-notes.precedent");
        assert_eq!(c.stagiaire, "/system/programmes/bloc-notes.part");
        assert_eq!(c.iso.as_deref(), Some("/system/programmes-iso/bloc-notes"));
        // tous les chemins calculés passent la muselière
        assert!(chemin_autorise(&c.cible));
        assert!(chemin_autorise(&c.precedent));
        assert!(chemin_autorise(&c.stagiaire));
        assert!(chemin_autorise(c.iso.as_deref().unwrap()));
    }

    #[test]
    fn theme_et_icones_sans_iso() {
        assert_eq!(chemins(Genre::Theme, "aero").unwrap().cible, "/system/themes/aero");
        assert_eq!(chemins(Genre::Theme, "aero").unwrap().iso, None);
        assert_eq!(chemins(Genre::Icones, "rondes").unwrap().cible, "/system/icones/rondes");
    }

    #[test]
    fn id_invalide_refuse() {
        for mauvais in ["", "../x", "A", "a/b", "a.b", "trop-long-trop-long-trop-long-xyz", "a b"] {
            assert!(chemins(Genre::Programme, mauvais).is_err(), "devrait refuser « {mauvais} »");
        }
        assert!(chemins(Genre::Programme, "a").is_ok());
        assert!(chemins(Genre::Programme, "bloc-notes-2").is_ok());
    }

    #[test]
    fn muselière_refuse_hors_racines() {
        // le noyau, l'ESP, les clés, les documents : jamais
        assert!(!chemin_autorise("/system/versions/0.19.4/bin/puppy-bureau"));
        assert!(!chemin_autorise("/system/etc/cles-paquets.conf"));
        assert!(!chemin_autorise("/boot/BOOTX64.EFI"));
        assert!(!chemin_autorise("/EFI/PUPPYOS/puppyos.efi"));
        assert!(!chemin_autorise("/gamelles/gam1/secret.txt"));
        assert!(!chemin_autorise("/system/journal/securite.txt"));
        // hors de tout : non
        assert!(!chemin_autorise("/etc/passwd"));
        assert!(!chemin_autorise("relatif/pas/absolu"));
    }

    #[test]
    fn muselière_refuse_les_pieges() {
        assert!(!chemin_autorise("/system/programmes/../versions/x")); // .. refusé
        assert!(!chemin_autorise("/system/programmes//x"));            // // refusé
        assert!(!chemin_autorise("/system/programmes-evil/x"));        // frontière de préfixe
        assert!(!chemin_autorise("/system/programmes/x\nmauvais"));    // retour à la ligne
        assert!(!chemin_autorise("/system/programmes/x\\y"));          // barre inversée
    }

    #[test]
    fn muselière_accepte_les_racines() {
        assert!(chemin_autorise("/system/programmes/bloc-notes"));
        assert!(chemin_autorise("/system/programmes/bloc-notes/bin/app"));
        assert!(chemin_autorise("/system/programmes-iso/bloc-notes"));
        assert!(chemin_autorise("/system/themes/aero/theme.conf"));
        assert!(chemin_autorise("/system/icones/rondes"));
    }

    #[test]
    fn extension_doit_coller_au_type() {
        assert!(extension_coherente(Genre::Programme, "Bonjour.puppy").is_ok());
        assert!(extension_coherente(Genre::Theme, "Aero.puptheme").is_ok());
        assert!(extension_coherente(Genre::Programme, "Theme-deguise.puptheme").is_err());
    }

    #[test]
    fn classement_systeme_vient_de_la_confiance() {
        // de confiance = système -> toujours « système », même si le paquet dit « jeu »
        assert_eq!(classer(Categorie::Systeme, Some("jeu")), GenreAffiche::Systeme);
        // un paquet utilisateur ne peut PAS se faire passer pour système
        assert_eq!(classer(Categorie::Utilisateur, Some("systeme")), GenreAffiche::Application);
        assert_eq!(classer(Categorie::Utilisateur, Some("système")), GenreAffiche::Application);
        // indices cosmétiques normaux
        assert_eq!(classer(Categorie::Utilisateur, Some("jeu")), GenreAffiche::Jeu);
        assert_eq!(classer(Categorie::Utilisateur, Some("autre")), GenreAffiche::Autre);
        assert_eq!(classer(Categorie::Utilisateur, None), GenreAffiche::Application);
    }

    #[test]
    fn anti_bombe_chemins() {
        let l = Limites::DEFAUT;
        assert!(verifier_entree("bin/app", 1000, &l).is_ok());
        assert_eq!(verifier_entree("/abs", 1, &l), Err(RefusBombe::CheminIllegal));
        assert_eq!(verifier_entree("a/../b", 1, &l), Err(RefusBombe::CheminIllegal));
        assert_eq!(verifier_entree("a\\b", 1, &l), Err(RefusBombe::CheminIllegal));
        assert_eq!(verifier_entree("", 1, &l), Err(RefusBombe::CheminIllegal));
    }

    #[test]
    fn anti_bombe_tailles() {
        let l = Limites::DEFAUT;
        assert_eq!(verifier_entree("gros", l.taille_fichier_max + 1, &l), Err(RefusBombe::FichierTropGros));
        assert_eq!(verifier_cumul(l.fichiers_max + 1, 1, 1, &l), Err(RefusBombe::TropDeFichiers));
        assert_eq!(verifier_cumul(1, l.taille_totale_max + 1, 1_000_000, &l), Err(RefusBombe::TropGros));
        // bombe : 100 Mio décompressés depuis 1 Kio compressé
        assert_eq!(verifier_cumul(1, 100 * 1024 * 1024, 1024, &l), Err(RefusBombe::RatioExcessif));
        // normal : 10 Mio depuis 4 Mio compressés
        assert!(verifier_cumul(500, 10 * 1024 * 1024, 4 * 1024 * 1024, &l).is_ok());
    }

    #[test]
    fn anti_bombe_profondeur() {
        let mut l = Limites::DEFAUT;
        l.profondeur_max = 3;
        assert!(verifier_chemin_entree("a/b/c", &l).is_ok());
        assert_eq!(verifier_chemin_entree("a/b/c/d", &l), Err(RefusBombe::TropProfond));
    }
}
