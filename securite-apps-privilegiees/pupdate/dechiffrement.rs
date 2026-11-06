//! dechiffrement.rs — DÉCHIFFREMENT MULTI-OS : que sait-on ouvrir, et comment ?
//!
//! Demande Piika (06/11) : pouvoir ouvrir des volumes Windows / Mac / Linux +
//! clés USB / cartes / téléphone, UNIQUEMENT avec le bon mot de passe, et SANS
//! jamais brute-forcer, casser ni forcer quoi que ce soit.
//!
//! Ce module est le CERVEAU (pur, testé) de cette décision. À partir du système
//! de fichiers détecté en LECTURE SEULE par `inspection.c` (aucun montage), il
//! répond à une seule question : « que peut-on honnêtement offrir sur ce
//! volume ? ». Trois principes :
//!
//! 1. HONNÊTETÉ SUR NOS LIMITES. On n'ouvre que ce qu'on sait réellement ouvrir.
//!    LUKS / nos volumes → par mot de passe. FAT/exFAT → en clair.
//!    NTFS / ext / APFS en clair → LECTURE SEULE (ce sont des OS concurrents :
//!    on ne les abîme jamais). BitLocker / FileVault → il FAUT la clé de
//!    récupération Microsoft / Apple : aucun contournement possible, et c'est
//!    tant mieux.
//!
//! 2. ZÉRO BRUTE-FORCE. Un volume protégé par mot de passe passe par le sas
//!    `montage_securise` (chrono évolutif + Honey-Dog). Un volume verrouillé par
//!    un fournisseur (BitLocker/FileVault) n'est JAMAIS attaqué : on réclame la
//!    clé de récupération, point.
//!
//! 3. ON N'ABÎME PAS L'OS VOISIN. Par défaut, un système de fichiers étranger
//!    (Windows, Linux, macOS) est offert en LECTURE SEULE. Seuls les formats
//!    d'échange amovibles (FAT/exFAT) et nos propres volumes déchiffrés sont en
//!    lecture/écriture.
//!
//! Module PUR : aucune E/S, aucun privilège, jamais de panique. Le C fait
//! l'ouverture dm-crypt réelle et le montage ; ce module ne fait que décider.
#![allow(dead_code)]

/// Système de fichiers reconnu (miroir des chaînes de `inspection.c`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Format {
    /// Notre volume PuppyOS chiffré (LUKS + marqueur maison).
    PuppyChiffre,
    /// LUKS générique (volume Linux chiffré, ou clé/carte chiffrée LUKS).
    Luks,
    /// FAT12/16/32 — format d'échange amovible.
    Fat,
    /// exFAT — format d'échange amovible (grosses clés/cartes).
    Exfat,
    /// NTFS en clair (Windows).
    Ntfs,
    /// NTFS verrouillé BitLocker (signature `-FVE-FS-`).
    NtfsBitlocker,
    /// APFS en clair (macOS récent).
    Apfs,
    /// APFS / CoreStorage chiffré FileVault.
    ApfsChiffre,
    /// ext2/3/4 (Linux).
    Ext,
    /// Espace d'échange (swap) — rien à monter.
    Swap,
    /// Image ISO 9660 (lecture seule par nature).
    Iso9660,
    /// Chiffré mais signature inconnue : on ne sait pas l'ouvrir.
    ChiffreInconnu,
    /// Non reconnu.
    Inconnu,
}

/// Classe la chaîne `fs[]` remplie par `inspection.c` en `Format`.
/// `bitlocker` et `filevault` viennent d'une sous-détection C (signature) : un
/// NTFS marqué BitLocker, un APFS marqué FileVault. Tolérant à la casse et aux
/// variantes ("FAT32", "ext2/3", "chiffré/inconnu"…).
pub fn classer(fs: &str, puppyos: bool, bitlocker: bool, filevault: bool) -> Format {
    let f = fs.trim();
    // comparaison insensible à la casse sans allocation
    let eq = |cible: &str| f.eq_ignore_ascii_case(cible);
    let commence = |p: &str| f.len() >= p.len() && f[..p.len()].eq_ignore_ascii_case(p);

    if eq("LUKS") {
        return if puppyos { Format::PuppyChiffre } else { Format::Luks };
    }
    if commence("FAT") {
        return Format::Fat;
    }
    if eq("exFAT") {
        return Format::Exfat;
    }
    if eq("NTFS") {
        return if bitlocker { Format::NtfsBitlocker } else { Format::Ntfs };
    }
    if eq("APFS") {
        return if filevault { Format::ApfsChiffre } else { Format::Apfs };
    }
    if commence("ext") {
        return Format::Ext;
    }
    if eq("swap") {
        return Format::Swap;
    }
    if eq("ISO9660") {
        return Format::Iso9660;
    }
    // "chiffré/inconnu" (avec ou sans accent, toutes graphies) → chiffré inconnu
    if commence("chiffr") {
        return Format::ChiffreInconnu;
    }
    Format::Inconnu
}

/// Ce qu'on peut honnêtement offrir sur un volume.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ouverture {
    /// Protégé par mot de passe qu'on sait ouvrir (LUKS / PuppyOS). Doit passer
    /// par le sas `montage_securise` (chrono anti-brute-force + Honey-Dog).
    /// `ecriture` : lecture/écriture après ouverture (vrai pour nos volumes).
    MotDePasse { ecriture: bool },
    /// En clair, montable directement. `ecriture` : vrai seulement pour les
    /// formats d'échange amovibles (FAT/exFAT) ; faux pour un OS voisin qu'on
    /// ne veut jamais abîmer (NTFS/ext/APFS → lecture seule).
    SansChiffrement { ecriture: bool },
    /// Verrouillé par un fournisseur : il FAUT sa clé de récupération, aucun
    /// brute-force n'est tenté ni possible. `fournisseur` = "Microsoft"/"Apple".
    /// `lecture_seule` : même avec la clé, on ne monte qu'en lecture (prudence).
    CleRecuperation { fournisseur: &'static str, lecture_seule: bool },
    /// Rien à monter (swap).
    NonMontable { motif: &'static str },
    /// On ne sait pas l'ouvrir (chiffré d'un format inconnu, format non reconnu).
    NonSupporte { motif: &'static str },
}

/// Décide l'ouverture possible d'un format. Aucun effet de bord.
pub fn ouverture(f: Format) -> Ouverture {
    match f {
        // nos volumes / LUKS : mot de passe → sas de montage, puis lecture/écriture
        Format::PuppyChiffre | Format::Luks => Ouverture::MotDePasse { ecriture: true },

        // formats d'échange amovibles : en clair, lecture/écriture
        Format::Fat | Format::Exfat => Ouverture::SansChiffrement { ecriture: true },

        // OS voisins en clair : montables mais LECTURE SEULE (on ne les abîme pas)
        Format::Ntfs | Format::Ext | Format::Apfs => Ouverture::SansChiffrement { ecriture: false },

        // verrouillés fournisseur : clé de récupération obligatoire, jamais de force
        Format::NtfsBitlocker => Ouverture::CleRecuperation {
            fournisseur: "Microsoft",
            lecture_seule: true,
        },
        Format::ApfsChiffre => Ouverture::CleRecuperation {
            fournisseur: "Apple",
            lecture_seule: true,
        },

        // image ISO : lecture seule par nature
        Format::Iso9660 => Ouverture::SansChiffrement { ecriture: false },

        Format::Swap => Ouverture::NonMontable { motif: "espace d'echange (swap) : rien a monter" },

        Format::ChiffreInconnu => Ouverture::NonSupporte {
            motif: "volume chiffre d'un format inconnu : PuppyOS ne sait pas l'ouvrir",
        },
        Format::Inconnu => Ouverture::NonSupporte {
            motif: "systeme de fichiers non reconnu",
        },
    }
}

/// Vrai si l'ouverture passe par une invite de mot de passe qu'on sait traiter,
/// donc par le sas `montage_securise` (anti-brute-force + Honey-Dog).
/// FAUX pour BitLocker/FileVault (clé fournisseur, pas de mot de passe à nous)
/// et pour tout ce qui est en clair ou non supporté.
pub fn par_mot_de_passe(f: Format) -> bool {
    matches!(ouverture(f), Ouverture::MotDePasse { .. })
}

/// Vrai si on sait, au bout du compte, monter ce volume (éventuellement après
/// un mot de passe ou une clé de récupération). Faux pour swap et non supporté.
pub fn montable_un_jour(f: Format) -> bool {
    matches!(
        ouverture(f),
        Ouverture::MotDePasse { .. }
            | Ouverture::SansChiffrement { .. }
            | Ouverture::CleRecuperation { .. }
    )
}

// ===========================================================================
//                                AUTOTESTS
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classe_les_chaines_inspection() {
        assert_eq!(classer("LUKS", true, false, false), Format::PuppyChiffre);
        assert_eq!(classer("LUKS", false, false, false), Format::Luks);
        assert_eq!(classer("FAT32", false, false, false), Format::Fat);
        assert_eq!(classer("FAT16", false, false, false), Format::Fat);
        assert_eq!(classer("exFAT", false, false, false), Format::Exfat);
        assert_eq!(classer("NTFS", false, false, false), Format::Ntfs);
        assert_eq!(classer("NTFS", false, true, false), Format::NtfsBitlocker);
        assert_eq!(classer("APFS", false, false, false), Format::Apfs);
        assert_eq!(classer("APFS", false, false, true), Format::ApfsChiffre);
        assert_eq!(classer("ext4", false, false, false), Format::Ext);
        assert_eq!(classer("ext2/3", false, false, false), Format::Ext);
        assert_eq!(classer("swap", false, false, false), Format::Swap);
        assert_eq!(classer("ISO9660", false, false, false), Format::Iso9660);
        assert_eq!(classer("chiffré/inconnu", false, false, false), Format::ChiffreInconnu);
        assert_eq!(classer("", false, false, false), Format::Inconnu);
        assert_eq!(classer("btrfs", false, false, false), Format::Inconnu);
    }

    #[test]
    fn casse_et_espaces_tolerees() {
        assert_eq!(classer(" luks ", false, false, false), Format::Luks);
        assert_eq!(classer("ntfs", false, true, false), Format::NtfsBitlocker);
        assert_eq!(classer("ExFat", false, false, false), Format::Exfat);
    }

    #[test]
    fn nos_volumes_passent_par_le_sas_en_ecriture() {
        for f in [Format::PuppyChiffre, Format::Luks] {
            assert_eq!(ouverture(f), Ouverture::MotDePasse { ecriture: true });
            assert!(par_mot_de_passe(f));
        }
    }

    #[test]
    fn echange_amovible_en_clair_et_ecriture() {
        for f in [Format::Fat, Format::Exfat] {
            assert_eq!(ouverture(f), Ouverture::SansChiffrement { ecriture: true });
            assert!(!par_mot_de_passe(f));
        }
    }

    #[test]
    fn os_voisins_en_clair_jamais_abimes_lecture_seule() {
        for f in [Format::Ntfs, Format::Ext, Format::Apfs] {
            assert_eq!(ouverture(f), Ouverture::SansChiffrement { ecriture: false });
        }
        // une image ISO est aussi lecture seule
        assert_eq!(ouverture(Format::Iso9660), Ouverture::SansChiffrement { ecriture: false });
    }

    #[test]
    fn bitlocker_et_filevault_exigent_la_cle_jamais_de_force() {
        match ouverture(Format::NtfsBitlocker) {
            Ouverture::CleRecuperation { fournisseur, lecture_seule } => {
                assert_eq!(fournisseur, "Microsoft");
                assert!(lecture_seule);
            }
            autre => panic!("BitLocker devrait exiger la cle Microsoft, obtenu {autre:?}"),
        }
        match ouverture(Format::ApfsChiffre) {
            Ouverture::CleRecuperation { fournisseur, lecture_seule } => {
                assert_eq!(fournisseur, "Apple");
                assert!(lecture_seule);
            }
            autre => panic!("FileVault devrait exiger la cle Apple, obtenu {autre:?}"),
        }
        // crucial : un volume verrouillé fournisseur ne passe JAMAIS par le sas
        // mot de passe (donc aucune tentative, aucun brute-force)
        assert!(!par_mot_de_passe(Format::NtfsBitlocker));
        assert!(!par_mot_de_passe(Format::ApfsChiffre));
    }

    #[test]
    fn chiffre_inconnu_est_refuse_honnetement() {
        assert!(matches!(ouverture(Format::ChiffreInconnu), Ouverture::NonSupporte { .. }));
        assert!(matches!(ouverture(Format::Inconnu), Ouverture::NonSupporte { .. }));
        assert!(!montable_un_jour(Format::ChiffreInconnu));
        assert!(!montable_un_jour(Format::Inconnu));
        assert!(!par_mot_de_passe(Format::ChiffreInconnu));
    }

    #[test]
    fn swap_non_montable() {
        assert!(matches!(ouverture(Format::Swap), Ouverture::NonMontable { .. }));
        assert!(!montable_un_jour(Format::Swap));
    }

    #[test]
    fn montable_un_jour_couvre_les_bons_cas() {
        for f in [
            Format::PuppyChiffre, Format::Luks, Format::Fat, Format::Exfat,
            Format::Ntfs, Format::Ext, Format::Apfs, Format::Iso9660,
            Format::NtfsBitlocker, Format::ApfsChiffre,
        ] {
            assert!(montable_un_jour(f), "{f:?} devrait etre montable un jour");
        }
    }
}
