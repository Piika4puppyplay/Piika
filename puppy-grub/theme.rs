//! Thèmes `.pupgrub` et mise en page du menu graphique (GOP) de puppy-GRUB.
//!
//! Brique PURE : ni UEFI, ni allocation, ni `unsafe`. Testée sur le PC
//! (`rustc --test theme.rs`) puis utilisée telle quelle dans l'application UEFI.
//!
//! Format `.pupgrub` (texte, clé=valeur, une par ligne, `#` = commentaire) :
//!   titre=PuppyOS            (≤ 40 caractères ASCII imprimables)
//!   fond=#141a26             couleurs en #RRGGBB
//!   panneau=#1f2838
//!   texte=#eceff4
//!   texte_doux=#8b95a6
//!   selection=#4c8dff
//!   texte_selection=#ffffff
//!
//! Règle de sûreté (comme les thèmes système de PuppyOS) : un thème ne peut
//! JAMAIS casser le menu. Fichier trop gros, binaire (octet nul) ou illisible →
//! thème austère complet. Valeur invalide → cette valeur seule reste austère.
//! Un thème trop peu contrasté (texte illisible sur son fond) est refusé.

#![cfg_attr(not(test), allow(dead_code))]

pub const TAILLE_MAX: usize = 4096;
pub const TITRE_MAX: usize = 40;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Theme {
    pub titre: [u8; TITRE_MAX],
    pub ntitre: usize,
    pub fond: u32,
    pub panneau: u32,
    pub texte: u32,
    pub texte_doux: u32,
    pub selection: u32,
    pub texte_selection: u32,
}

impl Theme {
    /// Le thème austère : toujours disponible, indestructible.
    pub const fn austere() -> Theme {
        let mut titre = [0u8; TITRE_MAX];
        let t = b"PuppyOS";
        let mut i = 0;
        while i < t.len() {
            titre[i] = t[i];
            i += 1;
        }
        Theme {
            titre,
            ntitre: 7,
            fond: 0x141a26,
            panneau: 0x1f2838,
            texte: 0xeceff4,
            texte_doux: 0x8b95a6,
            selection: 0x4c8dff,
            texte_selection: 0xffffff,
        }
    }

    pub fn titre(&self) -> &[u8] {
        &self.titre[..self.ntitre]
    }
}

/// Lit `#RRGGBB` (ou `RRGGBB`). None si invalide.
pub fn couleur(v: &[u8]) -> Option<u32> {
    let v = if v.first() == Some(&b'#') { &v[1..] } else { v };
    if v.len() != 6 {
        return None;
    }
    let mut c = 0u32;
    for &b in v {
        let d = match b {
            b'0'..=b'9' => b - b'0',
            b'a'..=b'f' => b - b'a' + 10,
            b'A'..=b'F' => b - b'A' + 10,
            _ => return None,
        };
        c = (c << 4) | d as u32;
    }
    Some(c)
}

fn rogner(mut s: &[u8]) -> &[u8] {
    while let Some((&p, r)) = s.split_first() {
        if p == b' ' || p == b'\t' || p == b'\r' { s = r } else { break }
    }
    while let Some((&d, r)) = s.split_last() {
        if d == b' ' || d == b'\t' || d == b'\r' { s = r } else { break }
    }
    s
}

/// Luminance relative (0..=1000) façon WCAG, approximée en entiers.
fn luminance(c: u32) -> u32 {
    let (r, g, b) = ((c >> 16) & 255, (c >> 8) & 255, c & 255);
    (2126 * r + 7152 * g + 722 * b) / 2550 // 0..=1000 (linéarité approchée)
}

/// Contraste suffisant entre deux couleurs (écart de luminance ≥ 35 %).
pub fn lisible(texte: u32, fond: u32) -> bool {
    let (a, b) = (luminance(texte), luminance(fond));
    (if a > b { a - b } else { b - a }) >= 350
}

/// Lit un thème `.pupgrub`. Jamais d'échec : au pire, l'austère.
pub fn lire_pupgrub(octets: &[u8]) -> Theme {
    let mut t = Theme::austere();
    if octets.len() > TAILLE_MAX || octets.contains(&0) {
        return t;
    }
    for ligne in octets.split(|&b| b == b'\n') {
        let l = rogner(ligne);
        if l.is_empty() || l[0] == b'#' {
            continue;
        }
        let eg = match l.iter().position(|&b| b == b'=') {
            Some(i) => i,
            None => continue,
        };
        let (cle, val) = (rogner(&l[..eg]), rogner(&l[eg + 1..]));
        match cle {
            b"titre" => {
                if !val.is_empty() && val.len() <= TITRE_MAX && val.iter().all(|&b| (32..127).contains(&b)) {
                    t.titre = [0; TITRE_MAX];
                    t.titre[..val.len()].copy_from_slice(val);
                    t.ntitre = val.len();
                }
            }
            b"fond" => { if let Some(c) = couleur(val) { t.fond = c } }
            b"panneau" => { if let Some(c) = couleur(val) { t.panneau = c } }
            b"texte" => { if let Some(c) = couleur(val) { t.texte = c } }
            b"texte_doux" => { if let Some(c) = couleur(val) { t.texte_doux = c } }
            b"selection" => { if let Some(c) = couleur(val) { t.selection = c } }
            b"texte_selection" => { if let Some(c) = couleur(val) { t.texte_selection = c } }
            _ => {}
        }
    }
    // Garde-fou de lisibilité : un thème illisible retombe sur l'austère.
    if !lisible(t.texte, t.panneau) || !lisible(t.texte_selection, t.selection) {
        let mut a = Theme::austere();
        a.titre = t.titre;
        a.ntitre = t.ntitre;
        return a;
    }
    t
}

/// Rectangle en pixels écran.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rect {
    pub x: u32,
    pub y: u32,
    pub l: u32,
    pub h: u32,
}

/// Mise en page du menu pour un écran `l`×`h` et `n` entrées.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Disposition {
    /// Échelle des glyphes (police 5×10 agrandie par pixels entiers).
    pub echelle: u32,
    pub panneau: Rect,
    pub titre_y: u32,
    /// y de la première entrée, hauteur d'une ligne d'entrée.
    pub entree_y: u32,
    pub ligne_h: u32,
    pub aide_y: u32,
}

/// Calcule la mise en page. Toujours dans l'écran, quel que soit `n` (borné à 8).
pub fn disposition(l: u32, h: u32, n: u32) -> Disposition {
    let n = n.clamp(1, 8);
    let l = l.max(320);
    let h = h.max(200);
    // échelle : 1 en 640x480, 2 en 1024x768+, 3 en 1920x1080+, 4 en 4K.
    let echelle = (h / 340).clamp(1, 4);
    let car_h = 12 * echelle; // cellule 6×12
    let ligne_h = car_h + 10 * echelle;
    let contenu_h = 3 * car_h + 4 * echelle + n * ligne_h + 2 * car_h;
    let pl = (l * 6 / 10).max(300).min(l - 20);
    let ph = (contenu_h + 16 * echelle).min(h - 20);
    let px = (l - pl) / 2;
    let py = (h - ph) / 2;
    let titre_y = py + 6 * echelle;
    let entree_y = titre_y + 3 * car_h + 4 * echelle;
    let aide_y = (entree_y + n * ligne_h + car_h / 2).min(py + ph - car_h - 2);
    Disposition { echelle, panneau: Rect { x: px, y: py, l: pl, h: ph }, titre_y, entree_y, ligne_h, aide_y }
}

/// Convertit 0xRRGGBB vers le pixel GOP selon son format
/// (0 = RGB réservé 8 bits, 1 = BGR réservé 8 bits). Autres formats → None
/// (puppy-GRUB repasse alors en menu texte).
pub fn pixel_gop(c: u32, format: u32) -> Option<u32> {
    let (r, g, b) = ((c >> 16) & 255, (c >> 8) & 255, c & 255);
    match format {
        0 => Some(r | (g << 8) | (b << 16)),
        1 => Some((r << 16) | (g << 8) | b),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn austere_si_vide_binaire_ou_trop_gros() {
        assert_eq!(lire_pupgrub(b""), Theme::austere());
        assert_eq!(lire_pupgrub(b"fond=#000000\0"), Theme::austere());
        let gros = [b'#'; TAILLE_MAX + 1];
        assert_eq!(lire_pupgrub(&gros), Theme::austere());
    }

    #[test]
    fn theme_valide_applique() {
        let t = lire_pupgrub(b"# chiens\ntitre=Les chiens de Piika\nfond=#102030\npanneau=#000000\ntexte=#FFFFFF\nselection=#e04060\n");
        assert_eq!(t.titre(), b"Les chiens de Piika");
        assert_eq!(t.fond, 0x102030);
        assert_eq!(t.panneau, 0);
        assert_eq!(t.texte, 0xffffff);
        assert_eq!(t.selection, 0xe04060);
    }

    #[test]
    fn valeur_invalide_reste_austere() {
        let t = lire_pupgrub(b"fond=bleu\ntexte=#12345\nselection=#GG0000\ntitre=\xc3\xa9t\xc3\xa9\n");
        let a = Theme::austere();
        assert_eq!((t.fond, t.texte, t.selection), (a.fond, a.texte, a.selection));
        assert_eq!(t.titre(), b"PuppyOS"); // titre non ASCII refusé
    }

    #[test]
    fn theme_illisible_refuse() {
        let t = lire_pupgrub(b"titre=Gris\npanneau=#808080\ntexte=#828282\n");
        assert_eq!(t.texte, Theme::austere().texte);
        assert_eq!(t.panneau, Theme::austere().panneau);
        assert_eq!(t.titre(), b"Gris");
    }

    #[test]
    fn titre_trop_long_refuse() {
        let mut v = b"titre=".to_vec();
        v.extend_from_slice(&[b'A'; TITRE_MAX + 1]);
        assert_eq!(lire_pupgrub(&v).titre(), b"PuppyOS");
    }

    #[test]
    fn couleurs() {
        assert_eq!(couleur(b"#a0B1c2"), Some(0xa0b1c2));
        assert_eq!(couleur(b"a0b1c2"), Some(0xa0b1c2));
        assert_eq!(couleur(b"#a0b1c"), None);
        assert_eq!(pixel_gop(0x112233, 1), Some(0x112233)); // BGRX : octets B,G,R en mémoire
        assert_eq!(pixel_gop(0x112233, 0), Some(0x332211));
        assert_eq!(pixel_gop(0x112233, 2), None);
    }

    #[test]
    fn disposition_toujours_dans_l_ecran() {
        for &(l, h) in &[(640u32, 480u32), (800, 600), (1024, 768), (1280, 800), (1920, 1080), (3840, 2160), (100, 50)] {
            for n in 0..12 {
                let d = disposition(l, h, n);
                let (l2, h2) = (l.max(320), h.max(200));
                assert!(d.panneau.x + d.panneau.l <= l2 && d.panneau.y + d.panneau.h <= h2, "{l}x{h} n={n}");
                assert!(d.titre_y >= d.panneau.y && d.aide_y < d.panneau.y + d.panneau.h);
                assert!(d.echelle >= 1 && d.echelle <= 4);
            }
        }
        assert_eq!(disposition(1920, 1080, 2).echelle, 3);
        assert_eq!(disposition(800, 600, 2).echelle, 1);
    }

    #[test]
    fn fuzz_jamais_de_panique() {
        // générateur pseudo-aléatoire déterministe (xorshift) : 20 000 entrées
        let mut x: u64 = 0x9e3779b97f4a7c15;
        let mut buf = [0u8; 300];
        for _ in 0..20_000 {
            let n = (x % 300) as usize;
            for b in buf.iter_mut().take(n) {
                x ^= x << 13; x ^= x >> 7; x ^= x << 17;
                *b = match x % 6 { 0 => b'=', 1 => b'\n', 2 => b'#', 3 => b"0123456789abcdef"[(x >> 8) as usize % 16], _ => (x >> 16) as u8 };
            }
            let t = lire_pupgrub(&buf[..n]);
            assert!(t.ntitre <= TITRE_MAX);
            assert!(lisible(t.texte, t.panneau));
        }
    }
}
