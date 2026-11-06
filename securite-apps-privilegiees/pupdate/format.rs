//! format.rs — parseurs Rust SÛRS des entrées non fiables de la mise à jour :
//! l'en-tête `.pupupdate` (0.3) et le conteneur `.pup` (PUPPYPKG). C'est là que
//! des octets hostiles entrent dans l'OS, donc c'est là que la sécurité mémoire
//! compte le plus — on remplace la lecture C (débordements possibles) par du
//! Rust borné. Ces parseurs sont PURS : ils prennent une tranche d'octets et
//! rendent une structure validée ou une erreur, sans jamais lire hors bornes et
//! sans jamais paniquer (voir les tests « fuzz » en bas).
//!
//! Formats (petit-boutiste) :
//!   .pupupdate : "PUPUPD01"(8) version[32] nonce[16] taille:u64 empreinte[32]
//!                = en-tête de 96 octets, puis le .pup chiffré, puis signature[64].
//!   .pup       : "PUPPYPKG"(8) format:u32=1 version[32] nb_entrees:u32
//!                puis par entrée : type:u8 lon_chemin:u16 chemin mode:u32 taille:u64 données[taille]
//!                puis "PUPFIN!!"(8) empreinte SHA-256[32].
#![allow(dead_code)]

use crate::jail::{verifier_chemin_entree, verifier_cumul, verifier_entree, Limites, RefusBombe};

const MAGIE_MAJ: &[u8; 8] = b"PUPUPD01";
const MAGIE_PUP: &[u8; 8] = b"PUPPYPKG";
const FIN_PUP: &[u8; 8] = b"PUPFIN!!";
const ENTETE_MAJ: usize = 96; // 8+32+16+8+32
const SIG_MAJ: usize = 64;

/// Plafond de taille d'un paquet chiffré annoncé dans l'en-tête .pupupdate :
/// au-delà, on refuse sans rien lire (anti-bombe de première ligne).
const TAILLE_MAJ_MAX: u64 = 1024 * 1024 * 1024; // 1 Gio

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ErreurFormat {
    TropCourt,
    MauvaiseMagie,
    MauvaisFormat,
    VersionInvalide,
    TailleAberrante,
    Troncature,       // une longueur déclarée sort de la tranche
    DebordementEntier,
    FinManquante,     // trailer "PUPFIN!!" absent / mal placé
    Bombe(RefusBombe),
}

impl ErreurFormat {
    pub fn libelle(self) -> &'static str {
        match self {
            ErreurFormat::TropCourt => "Fichier trop court",
            ErreurFormat::MauvaiseMagie => "Signature de format incorrecte",
            ErreurFormat::MauvaisFormat => "Version de format non prise en charge",
            ErreurFormat::VersionInvalide => "Champ version invalide",
            ErreurFormat::TailleAberrante => "Taille annoncée aberrante",
            ErreurFormat::Troncature => "Fichier tronqué / longueur hors limites",
            ErreurFormat::DebordementEntier => "Débordement d'entier dans les tailles",
            ErreurFormat::FinManquante => "Marque de fin absente",
            ErreurFormat::Bombe(_) => "Paquet rejeté par l'anti-bombe",
        }
    }
}

// --------------------------- lectures bornées --------------------------
fn u32_le(b: &[u8], o: usize) -> Option<u32> {
    b.get(o..o + 4).map(|s| u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
}
fn u16_le(b: &[u8], o: usize) -> Option<u16> {
    b.get(o..o + 2).map(|s| u16::from_le_bytes([s[0], s[1]]))
}
fn u64_le(b: &[u8], o: usize) -> Option<u64> {
    b.get(o..o + 8).map(|s| u64::from_le_bytes([s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]]))
}

/// Lit un champ version de 32 octets : texte ASCII imprimable, terminé par NUL,
/// non vide. Rejette tout octet de contrôle (anti-injection de journal).
fn lire_version(champ: &[u8]) -> Result<String, ErreurFormat> {
    if champ.len() != 32 {
        return Err(ErreurFormat::VersionInvalide);
    }
    let fin = champ.iter().position(|&c| c == 0).unwrap_or(32);
    if fin == 0 || fin > 31 {
        return Err(ErreurFormat::VersionInvalide); // vide, ou pas de NUL dans les 32
    }
    let v = &champ[..fin];
    if v.iter().any(|&c| !(0x20..0x7f).contains(&c)) {
        return Err(ErreurFormat::VersionInvalide);
    }
    Ok(String::from_utf8_lossy(v).into_owned())
}

// --------------------------- .pupupdate --------------------------------
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EnteteMaj {
    pub version: String,
    pub nonce: [u8; 16],
    pub taille_chiffree: u64,
    pub empreinte: [u8; 32],
}

/// Parse et valide l'en-tête d'un `.pupupdate` (les 96 premiers octets + la
/// cohérence de la taille totale avec la signature). NE vérifie PAS la signature
/// Ed25519 — ça reste à la couche crypto (verif.rs / Monocypher). Ici : bornes,
/// magie, version, taille non aberrante.
pub fn parser_entete_maj(fichier: &[u8]) -> Result<EnteteMaj, ErreurFormat> {
    if fichier.len() < ENTETE_MAJ + SIG_MAJ {
        return Err(ErreurFormat::TropCourt);
    }
    if &fichier[..8] != MAGIE_MAJ {
        return Err(ErreurFormat::MauvaiseMagie);
    }
    let version = lire_version(&fichier[8..40])?;
    let mut nonce = [0u8; 16];
    nonce.copy_from_slice(&fichier[40..56]);
    let taille_chiffree = u64_le(fichier, 56).ok_or(ErreurFormat::Troncature)?;
    let mut empreinte = [0u8; 32];
    empreinte.copy_from_slice(&fichier[64..96]);

    if taille_chiffree == 0 || taille_chiffree > TAILLE_MAJ_MAX {
        return Err(ErreurFormat::TailleAberrante);
    }
    // la taille annoncée doit coller EXACTEMENT au fichier : en-tête + paquet + signature
    let attendu = (ENTETE_MAJ as u64)
        .checked_add(taille_chiffree)
        .and_then(|x| x.checked_add(SIG_MAJ as u64))
        .ok_or(ErreurFormat::DebordementEntier)?;
    if attendu != fichier.len() as u64 {
        return Err(ErreurFormat::Troncature);
    }
    Ok(EnteteMaj { version, nonce, taille_chiffree, empreinte })
}

/// Les octets exacts que la signature Ed25519 couvre (l'en-tête de 96 octets).
pub fn entete_maj_a_signer(fichier: &[u8]) -> Option<&[u8]> {
    fichier.get(..ENTETE_MAJ)
}

// ------------------------------ .pup -----------------------------------
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EntreePup {
    pub type_entree: u8, // 1 fichier, 2 dossier, 3 lien
    pub chemin: String,
    pub mode: u32,
    pub taille: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Pup {
    pub version: String,
    pub entrees: Vec<EntreePup>,
    pub total_donnees: u64,
}

/// Parse et valide la STRUCTURE d'un conteneur `.pup` (sans copier les données) :
/// magie, format, version, chaque en-tête d'entrée (type/chemin/mode/taille),
/// chemins sûrs (pas de « .. », relatifs, bornés), anti-bombe par entrée et au
/// cumul, et la marque de fin "PUPFIN!!" + empreinte. Jamais de lecture hors
/// bornes, jamais de débordement d'entier (checked_add partout).
pub fn parser_pup(fichier: &[u8], lim: &Limites) -> Result<Pup, ErreurFormat> {
    // en-tête fixe (48) + fin (8) + empreinte (32)
    const TETE: usize = 8 + 4 + 32 + 4;
    const QUEUE: usize = 8 + 32;
    if fichier.len() < TETE + QUEUE {
        return Err(ErreurFormat::TropCourt);
    }
    if &fichier[..8] != MAGIE_PUP {
        return Err(ErreurFormat::MauvaiseMagie);
    }
    if u32_le(fichier, 8) != Some(1) {
        return Err(ErreurFormat::MauvaisFormat);
    }
    let version = lire_version(&fichier[12..44])?;
    let nb = u32_le(fichier, 44).ok_or(ErreurFormat::Troncature)?;
    if nb > lim.fichiers_max {
        return Err(ErreurFormat::Bombe(RefusBombe::TropDeFichiers));
    }

    // la zone des données s'arrête avant la queue (fin + empreinte)
    let limite_donnees = fichier.len() - QUEUE;
    let mut o = TETE;
    let mut entrees = Vec::new();
    let mut total: u64 = 0;
    for _ in 0..nb {
        let type_entree = *fichier.get(o).ok_or(ErreurFormat::Troncature)?;
        o += 1;
        let lon = u16_le(fichier, o).ok_or(ErreurFormat::Troncature)? as usize;
        o += 2;
        if lon == 0 || lon > lim.chemin_octets_max {
            return Err(ErreurFormat::Bombe(RefusBombe::CheminTropLong));
        }
        let brut = fichier.get(o..o + lon).ok_or(ErreurFormat::Troncature)?;
        o += lon;
        let chemin = core::str::from_utf8(brut).map_err(|_| ErreurFormat::Bombe(RefusBombe::CheminIllegal))?;
        verifier_chemin_entree(chemin, lim).map_err(ErreurFormat::Bombe)?;
        let mode = u32_le(fichier, o).ok_or(ErreurFormat::Troncature)?;
        o += 4;
        let taille = u64_le(fichier, o).ok_or(ErreurFormat::Troncature)?;
        o += 8;
        verifier_entree(chemin, taille, lim).map_err(ErreurFormat::Bombe)?;
        // les données suivent, en ligne : on saute `taille` octets, bornes + débordement vérifiés
        o = o.checked_add(taille as usize).ok_or(ErreurFormat::DebordementEntier)?;
        if o as u64 > limite_donnees as u64 {
            return Err(ErreurFormat::Troncature);
        }
        total = total.checked_add(taille).ok_or(ErreurFormat::DebordementEntier)?;
        entrees.push(EntreePup { type_entree, chemin: chemin.to_string(), mode, taille });
    }
    // après les entrées : exactement la marque de fin puis l'empreinte
    if o != limite_donnees {
        return Err(ErreurFormat::Troncature);
    }
    if &fichier[o..o + 8] != FIN_PUP {
        return Err(ErreurFormat::FinManquante);
    }
    verifier_cumul(nb, total, fichier.len() as u64, lim).map_err(ErreurFormat::Bombe)?;
    Ok(Pup { version, entrees, total_donnees: total })
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    // --- fabrique de .pupupdate / .pup valides pour les tests ---
    fn entete_maj(version: &str, taille: u64) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(MAGIE_MAJ);
        let mut ver = [0u8; 32];
        ver[..version.len()].copy_from_slice(version.as_bytes());
        v.extend_from_slice(&ver);
        v.extend_from_slice(&[7u8; 16]); // nonce
        v.extend_from_slice(&taille.to_le_bytes());
        v.extend_from_slice(&[9u8; 32]); // empreinte
        v
    }
    fn maj_complet(version: &str, taille: u64) -> Vec<u8> {
        let mut v = entete_maj(version, taille);
        v.extend(core::iter::repeat(0u8).take(taille as usize)); // paquet chiffré factice
        v.extend_from_slice(&[0u8; 64]); // signature
        v
    }

    fn pousser_entree(v: &mut Vec<u8>, t: u8, chemin: &str, taille: u64) {
        v.push(t);
        v.extend_from_slice(&(chemin.len() as u16).to_le_bytes());
        v.extend_from_slice(chemin.as_bytes());
        v.extend_from_slice(&0o644u32.to_le_bytes());
        v.extend_from_slice(&taille.to_le_bytes());
        v.extend(core::iter::repeat(0u8).take(taille as usize));
    }
    fn pup(entrees: &[(u8, &str, u64)]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(MAGIE_PUP);
        v.extend_from_slice(&1u32.to_le_bytes());
        let mut ver = [0u8; 32];
        ver[..3].copy_from_slice(b"1.0");
        v.extend_from_slice(&ver);
        v.extend_from_slice(&(entrees.len() as u32).to_le_bytes());
        for &(t, c, taille) in entrees {
            pousser_entree(&mut v, t, c, taille);
        }
        v.extend_from_slice(FIN_PUP);
        v.extend_from_slice(&[0u8; 32]); // empreinte
        v
    }

    // ------------------------------ .pupupdate -------------------------
    #[test]
    fn maj_valide() {
        let f = maj_complet("0.20.1", 100);
        let e = parser_entete_maj(&f).unwrap();
        assert_eq!(e.version, "0.20.1");
        assert_eq!(e.taille_chiffree, 100);
    }

    #[test]
    fn maj_trop_courte_mauvaise_magie_taille() {
        assert_eq!(parser_entete_maj(&[0u8; 10]), Err(ErreurFormat::TropCourt));
        let mut f = maj_complet("0.20.1", 100);
        f[0] = b'X';
        assert_eq!(parser_entete_maj(&f), Err(ErreurFormat::MauvaiseMagie));
        // taille annoncée qui ne colle pas au fichier
        let mut f = maj_complet("0.20.1", 100);
        let faux = 200u64.to_le_bytes();
        f[56..64].copy_from_slice(&faux);
        assert_eq!(parser_entete_maj(&f), Err(ErreurFormat::Troncature));
        // taille aberrante
        let f = entete_maj_brut_taille(TAILLE_MAJ_MAX + 1);
        assert_eq!(parser_entete_maj(&f), Err(ErreurFormat::TailleAberrante));
    }
    fn entete_maj_brut_taille(t: u64) -> Vec<u8> {
        let mut v = entete_maj("0.1", t);
        v.extend_from_slice(&[0u8; 64]);
        v
    }

    #[test]
    fn maj_version_pourrie_rejetee() {
        let mut f = maj_complet("0.20.1", 10);
        f[8..40].copy_from_slice(&[0xff; 32]); // pas de NUL, octets non imprimables
        assert_eq!(parser_entete_maj(&f), Err(ErreurFormat::VersionInvalide));
    }

    // -------------------------------- .pup -----------------------------
    #[test]
    fn pup_valide() {
        let f = pup(&[(1, "bin/app", 10), (2, "data", 0)]);
        let p = parser_pup(&f, &Limites::DEFAUT).unwrap();
        assert_eq!(p.version, "1.0");
        assert_eq!(p.entrees.len(), 2);
        assert_eq!(p.total_donnees, 10);
        assert_eq!(p.entrees[0].chemin, "bin/app");
    }

    #[test]
    fn pup_chemin_echappatoire_rejete() {
        let f = pup(&[(1, "../escape", 4)]);
        assert_eq!(parser_pup(&f, &Limites::DEFAUT), Err(ErreurFormat::Bombe(RefusBombe::CheminIllegal)));
    }

    #[test]
    fn pup_mauvaise_magie_et_format() {
        let mut f = pup(&[(1, "a", 1)]);
        f[0] = b'Z';
        assert_eq!(parser_pup(&f, &Limites::DEFAUT), Err(ErreurFormat::MauvaiseMagie));
        let mut f = pup(&[(1, "a", 1)]);
        f[8..12].copy_from_slice(&2u32.to_le_bytes()); // format = 2
        assert_eq!(parser_pup(&f, &Limites::DEFAUT), Err(ErreurFormat::MauvaisFormat));
    }

    #[test]
    fn pup_tronque_ne_panique_pas() {
        let f = pup(&[(1, "bin/app", 50)]);
        // on coupe au milieu des données : doit renvoyer une erreur, jamais paniquer
        for n in 0..f.len() {
            let _ = parser_pup(&f[..n], &Limites::DEFAUT); // ne doit pas paniquer
        }
        // taille d'entrée qui dépasse le fichier
        let mut f = pup(&[(1, "bin/app", 10)]);
        // trafique la taille déclarée de la 1re entrée pour qu'elle déborde
        let pos_taille = (8 + 4 + 32 + 4) + 1 + 2 + "bin/app".len() + 4;
        f[pos_taille..pos_taille + 8].copy_from_slice(&u64::MAX.to_le_bytes());
        let r = parser_pup(&f, &Limites::DEFAUT);
        assert!(matches!(r, Err(ErreurFormat::DebordementEntier) | Err(ErreurFormat::Bombe(_)) | Err(ErreurFormat::Troncature)));
    }

    #[test]
    fn pup_trop_de_fichiers_rejete() {
        let mut lim = Limites::DEFAUT;
        lim.fichiers_max = 2;
        let f = pup(&[(1, "a", 1), (1, "b", 1), (1, "c", 1)]);
        assert_eq!(parser_pup(&f, &lim), Err(ErreurFormat::Bombe(RefusBombe::TropDeFichiers)));
    }

    #[test]
    fn pup_fin_manquante() {
        let mut f = pup(&[(1, "a", 1)]);
        let n = f.len();
        f[n - 40..n - 32].copy_from_slice(b"XXXXXXXX"); // casse "PUPFIN!!"
        assert_eq!(parser_pup(&f, &Limites::DEFAUT), Err(ErreurFormat::FinManquante));
    }

    #[test]
    fn fuzz_leger_ne_panique_pas() {
        // quelques milliers d'octets pseudo-aléatoires : aucune panique admise
        let mut x = 0x1234_5678u32;
        let mut al = || { x ^= x << 13; x ^= x >> 17; x ^= x << 5; x };
        for taille in [0usize, 1, 47, 48, 96, 200, 1000] {
            for _ in 0..200 {
                let v: Vec<u8> = (0..taille).map(|_| (al() & 0xff) as u8).collect();
                let _ = parser_entete_maj(&v);
                let _ = parser_pup(&v, &Limites::DEFAUT);
            }
        }
    }
}
