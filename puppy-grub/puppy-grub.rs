//! puppy-grub.rs — puppy-GRUB : le gestionnaire d'amorçage UEFI maison de
//! PuppyOS, en Rust. Ce n'est PAS GNU GRUB (aucun code GNU) : c'est une
//! application UEFI autonome (type systemd-boot/rEFInd) qui s'exécute AVANT le
//! noyau, affiche un menu (dualboot), et « chaîne » l'image EFI choisie — le
//! noyau signé de PuppyOS, ou le chargeur d'un autre OS (Windows, Linux).
//!
//! Décision d'architecture : voir mémoire puppyos-puppy-grub et
//! /mnt/project-files/PuppyOS/RAPPORT-AUDIT-PUPPY-GRUB.md. Placé sur l'ESP en
//! /EFI/BOOT/BOOTX64.EFI ; le noyau PuppyOS déménage en /EFI/PUPPYOS/…efi.
//!
//! CE QUI MARCHE (testable en VM) :
//!   - lit le menu depuis l'ESP : \EFI\PUPPYOS\grub.conf (entrées titre+fichier) ;
//!   - menu texte navigable (↑/↓, Entrée, Échap) avec compte à rebours ;
//!   - CHAÎNE l'entrée choisie via LoadImage(depuis un tampon)+StartImage.
//! À VENIR : menu graphique (GOP) thématisable, auto-détection des autres OS,
//!   intégration PuppyUpdate (A/B ESP, .pupgrub), signature Secure Boot.
//!
//! Compilation (cible UEFI intégrée à rustc ; `rustup target add
//! x86_64-unknown-uefi` une fois, pas une dépendance crates.io) :
//!   rustc --target x86_64-unknown-uefi -C panic=abort -O \
//!         boot/puppy-grub/puppy-grub.rs -o BOOTX64.EFI
#![no_std]
#![no_main]
#![allow(dead_code)]

mod police;
mod theme;
mod uefi;

use core::ffi::c_void;
use core::panic::PanicInfo;
use core::ptr;
use uefi::*;

const VERSION: &str = "0.3-gop";
const THEME: &str = "\\EFI\\PUPPYOS\\theme.pupgrub";
const CONFIG: &str = "\\EFI\\PUPPYOS\\grub.conf";
const MAX_ENTREES: usize = 8;

/// Une entrée du menu : un titre lisible et un chemin EFI à chaîner.
#[derive(Clone, Copy)]
struct Entree {
    titre: [u8; 64],
    ntitre: usize,
    chemin: [u16; 160], // chemin EFI en UTF-16, terminé par 0
}
impl Entree {
    const fn vide() -> Self {
        Entree { titre: [0; 64], ntitre: 0, chemin: [0; 160] }
    }
    fn titre_str(&self) -> &str {
        core::str::from_utf8(&self.titre[..self.ntitre]).unwrap_or("?")
    }
}

struct Menu {
    entrees: [Entree; MAX_ENTREES],
    n: usize,
    defaut: usize,
    delai: i32, // secondes ; <0 = pas de compte à rebours
}

#[no_mangle]
pub extern "efiapi" fn efi_main(image: Handle, table: *mut SystemTable) -> Status {
    let out = unsafe { (*table).con_out };
    let inp = unsafe { (*table).con_in };
    let bs = unsafe { (*table).boot_services };

    unsafe { ((*out).clear_screen)(out) };

    // Volume de boot (l'ESP depuis lequel le micrologiciel nous a chargés).
    let racine = match ouvrir_volume_boot(bs, image) {
        Some(r) => r,
        None => {
            ecrire(out, "\n  puppy-GRUB : volume de boot introuvable.\n");
            unsafe { ((*bs).stall)(4_000_000) };
            return ERREUR;
        }
    };

    // Menu depuis \EFI\PUPPYOS\grub.conf (ou entrée par défaut si absent).
    let mut menu = Menu {
        entrees: [Entree::vide(); MAX_ENTREES],
        n: 0,
        defaut: 0,
        delai: 5,
    };
    if !lire_config(bs, racine, &mut menu) || menu.n == 0 {
        // repli : une seule entrée vers le noyau PuppyOS standard
        ajouter_entree(&mut menu, "PuppyOS", "\\EFI\\PUPPYOS\\puppyos.efi");
        menu.delai = -1;
    }

    // 0.3 : menu graphique (GOP) habillé par un thème .pupgrub facultatif ;
    // sans GOP utilisable, on garde le menu texte d'origine.
    let ecran = ouvrir_gop(bs);
    let th = lire_theme(bs, racine);

    // Boucle du menu.
    let mut sel = if menu.defaut < menu.n { menu.defaut } else { 0 };
    let mut restant = menu.delai;
    loop {
        match &ecran {
            Some(e) => dessiner_gop(e, &th, &menu, sel, restant),
            None => dessiner(out, &menu, sel, restant),
        }
        match lire_touche_1s(inp, bs) {
            Some(k) => {
                restant = -1; // toute touche annule le compte à rebours
                match k.scan_code {
                    SCAN_UP => sel = if sel == 0 { menu.n - 1 } else { sel - 1 },
                    SCAN_DOWN => sel = (sel + 1) % menu.n,
                    SCAN_ESC => {
                        ecrire(out, "\n  Retour au micrologiciel.\n");
                        return SUCCESS;
                    }
                    _ => {
                        if k.unicode_char == CHAR_CR {
                            chainer(out, bs, image, racine, &menu.entrees[sel]);
                            // si on revient, c'est que le chaînage a échoué
                            restant = -1;
                        }
                    }
                }
            }
            None => {
                if restant > 0 {
                    restant -= 1;
                    if restant == 0 {
                        chainer(out, bs, image, racine, &menu.entrees[sel]);
                        restant = -1;
                    }
                }
            }
        }
    }
}

// --------------------------- ouverture du volume ----------------------------
fn ouvrir_volume_boot(bs: *mut BootServices, image: Handle) -> Option<*mut FileProtocol> {
    unsafe {
        // LoadedImage -> device_handle (l'ESP)
        let mut li: *mut c_void = ptr::null_mut();
        if ((*bs).handle_protocol)(image, &LOADED_IMAGE_GUID, &mut li) != SUCCESS || li.is_null() {
            return None;
        }
        let dev = (*(li as *mut LoadedImage)).device_handle;
        // SimpleFileSystem sur ce volume
        let mut fsp: *mut c_void = ptr::null_mut();
        if ((*bs).handle_protocol)(dev, &SIMPLE_FS_GUID, &mut fsp) != SUCCESS || fsp.is_null() {
            return None;
        }
        let fs = fsp as *mut SimpleFileSystem;
        let mut racine: *mut FileProtocol = ptr::null_mut();
        if ((*fs).open_volume)(fs, &mut racine) != SUCCESS || racine.is_null() {
            return None;
        }
        Some(racine)
    }
}

// ------------------------------ lecture fichier -----------------------------
/// Lit tout un fichier de l'ESP dans un tampon alloué (pool). Renvoie
/// (pointeur, taille) ; le tampon doit être libéré par free_pool.
fn lire_fichier(
    bs: *mut BootServices,
    racine: *mut FileProtocol,
    chemin_utf16: *const Char16,
) -> Option<(*mut c_void, usize)> {
    unsafe {
        let mut f: *mut FileProtocol = ptr::null_mut();
        if ((*racine).open)(racine, &mut f, chemin_utf16, FILE_MODE_READ, 0) != SUCCESS || f.is_null() {
            return None;
        }
        // taille via FileInfo
        let mut info = [0u8; 512];
        let mut taille_info = info.len();
        let st = ((*f).get_info)(f, &FILE_INFO_GUID, &mut taille_info, info.as_mut_ptr() as *mut c_void);
        if st != SUCCESS {
            ((*f).close)(f);
            return None;
        }
        let fi = &*(info.as_ptr() as *const FileInfo);
        let taille = fi.file_size as usize;
        if taille == 0 || taille > (64 << 20) {
            ((*f).close)(f);
            return None;
        }
        // allouer + lire
        let mut buf: *mut c_void = ptr::null_mut();
        if ((*bs).allocate_pool)(LOADER_DATA, taille, &mut buf) != SUCCESS || buf.is_null() {
            ((*f).close)(f);
            return None;
        }
        let mut lu = taille;
        let st = ((*f).read)(f, &mut lu, buf);
        ((*f).close)(f);
        if st != SUCCESS || lu != taille {
            ((*bs).free_pool)(buf);
            return None;
        }
        Some((buf, taille))
    }
}

// ------------------------------ lecture config ------------------------------
fn lire_config(bs: *mut BootServices, racine: *mut FileProtocol, menu: &mut Menu) -> bool {
    let mut c16 = [0u16; 160];
    chemin_utf16(CONFIG, &mut c16);
    let (buf, taille) = match lire_fichier(bs, racine, c16.as_ptr()) {
        Some(x) => x,
        None => return false,
    };
    // copier dans une pile bornée puis libérer le pool
    let mut texte = [0u8; 4096];
    let n = core::cmp::min(taille, texte.len());
    unsafe {
        ptr::copy_nonoverlapping(buf as *const u8, texte.as_mut_ptr(), n);
        ((*bs).free_pool)(buf);
    }
    let s = core::str::from_utf8(&texte[..n]).unwrap_or("");
    let mut titre_courant: Option<&str> = None;
    for ligne in s.lines() {
        let l = ligne.trim();
        if l.is_empty() || l.starts_with('#') {
            continue;
        }
        if let Some((cle, val)) = l.split_once('=') {
            let (cle, val) = (cle.trim(), val.trim());
            match cle {
                "titre" | "title" => titre_courant = Some(val),
                "delai" | "timeout" => menu.delai = val.parse().unwrap_or(5),
                "defaut" | "default" => menu.defaut = val.parse().unwrap_or(0),
                "fichier" | "efi" => {
                    if let Some(t) = titre_courant {
                        ajouter_entree(menu, t, val);
                        titre_courant = None;
                    }
                }
                _ => {}
            }
        }
    }
    true
}

fn ajouter_entree(menu: &mut Menu, titre: &str, chemin: &str) {
    if menu.n >= MAX_ENTREES {
        return;
    }
    let e = &mut menu.entrees[menu.n];
    let tb = titre.as_bytes();
    let nt = core::cmp::min(tb.len(), e.titre.len());
    e.titre[..nt].copy_from_slice(&tb[..nt]);
    e.ntitre = nt;
    chemin_utf16(chemin, &mut e.chemin);
    menu.n += 1;
}

/// Convertit un chemin ASCII en UTF-16 terminé par 0 ; '/' devient '\\'.
fn chemin_utf16(s: &str, out: &mut [u16]) {
    let mut i = 0;
    for b in s.bytes() {
        if i + 1 >= out.len() {
            break;
        }
        out[i] = if b == b'/' { b'\\' as u16 } else { b as u16 };
        i += 1;
    }
    out[i] = 0;
}

// -------------------------------- chaînage ----------------------------------
fn chainer(
    out: *mut SimpleTextOutput,
    bs: *mut BootServices,
    parent: Handle,
    racine: *mut FileProtocol,
    e: &Entree,
) {
    unsafe { ((*out).clear_screen)(out) };
    ecrire(out, "\n  Demarrage de : ");
    ecrire(out, e.titre_str());
    ecrire(out, "\n");

    let (buf, taille) = match lire_fichier(bs, racine, e.chemin.as_ptr()) {
        Some(x) => x,
        None => {
            ecrire(out, "  Echec : image EFI introuvable ou illisible.\n");
            unsafe { ((*bs).stall)(3_000_000) };
            return;
        }
    };
    unsafe {
        let mut enfant: Handle = ptr::null_mut();
        let st = ((*bs).load_image)(false, parent, ptr::null(), buf, taille, &mut enfant);
        ((*bs).free_pool)(buf);
        if st != SUCCESS || enfant.is_null() {
            ecrire(out, "  Echec : image EFI refusee (signature Secure Boot ?).\n");
            ((*bs).stall)(3_000_000);
            return;
        }
        // on passe la main ; si StartImage revient, l'OS a rendu le contrôle
        let _ = ((*bs).start_image)(enfant, ptr::null_mut(), ptr::null_mut());
        ecrire(out, "  (l'OS a rendu la main a puppy-GRUB)\n");
        ((*bs).stall)(2_000_000);
    }
}

// ------------------------------ menu graphique ------------------------------
struct Ecran {
    fb: *mut u32,
    l: u32,
    h: u32,
    ligne: u32,  // pixels par ligne de balayage
    format: u32, // 0 = RGBX, 1 = BGRX
}

/// Trouve le protocole graphique GOP. None si absent ou format exotique.
fn ouvrir_gop(bs: *mut BootServices) -> Option<Ecran> {
    unsafe {
        let mut poignees = [ptr::null_mut::<c_void>(); 8];
        let mut taille = core::mem::size_of_val(&poignees);
        // 2 = ByProtocol
        if ((*bs).locate_handle)(2, &GOP_GUID, ptr::null(), &mut taille, poignees.as_mut_ptr()) != SUCCESS {
            return None;
        }
        let n = taille / core::mem::size_of::<Handle>();
        for &h in poignees.iter().take(n) {
            let mut gop: *mut c_void = ptr::null_mut();
            if ((*bs).handle_protocol)(h, &GOP_GUID, &mut gop) != SUCCESS || gop.is_null() {
                continue;
            }
            let g = gop as *mut GraphicsOutput;
            let m = (*g).mode;
            if m.is_null() || (*m).info.is_null() || (*m).frame_buffer_base == 0 {
                continue;
            }
            let i = &*(*m).info;
            if theme::pixel_gop(0, i.pixel_format).is_none() || i.horizontal_resolution < 320 || i.vertical_resolution < 200 {
                continue;
            }
            return Some(Ecran {
                fb: (*m).frame_buffer_base as *mut u32,
                l: i.horizontal_resolution,
                h: i.vertical_resolution,
                ligne: i.pixels_per_scan_line,
                format: i.pixel_format,
            });
        }
        None
    }
}

/// Lit \EFI\PUPPYOS\theme.pupgrub (facultatif). Toujours un thème valide.
fn lire_theme(bs: *mut BootServices, racine: *mut FileProtocol) -> theme::Theme {
    let mut c16 = [0u16; 160];
    chemin_utf16(THEME, &mut c16);
    match lire_fichier(bs, racine, c16.as_ptr()) {
        Some((buf, taille)) => {
            let mut texte = [0u8; theme::TAILLE_MAX + 1];
            let n = core::cmp::min(taille, texte.len());
            unsafe {
                ptr::copy_nonoverlapping(buf as *const u8, texte.as_mut_ptr(), n);
                ((*bs).free_pool)(buf);
            }
            theme::lire_pupgrub(&texte[..n])
        }
        None => theme::Theme::austere(),
    }
}

fn rect(e: &Ecran, x: u32, y: u32, l: u32, h: u32, c: u32) {
    let p = theme::pixel_gop(c, e.format).unwrap_or(0);
    let (x1, y1) = ((x + l).min(e.l), (y + h).min(e.h));
    for yy in y.min(e.h)..y1 {
        for xx in x.min(e.l)..x1 {
            unsafe { ptr::write_volatile(e.fb.add((yy * e.ligne + xx) as usize), p) };
        }
    }
}

/// Écrit du texte ASCII avec la police Truffe agrandie. Retourne la largeur.
fn texte(e: &Ecran, x: u32, y: u32, s: &[u8], c: u32, k: u32) -> u32 {
    let mut cx = x;
    for &b in s {
        let g = police::glyphe(b);
        for (ry, &bits) in g.iter().enumerate() {
            for col in 0..police::LARGEUR {
                if bits & (0x10 >> col) != 0 {
                    rect(e, cx + col as u32 * k, y + (ry as u32 + 2) * k, k, k, c);
                }
            }
        }
        cx += 6 * k;
    }
    cx - x
}

fn largeur(s: &[u8], k: u32) -> u32 {
    s.len() as u32 * 6 * k
}

fn dessiner_gop(e: &Ecran, th: &theme::Theme, menu: &Menu, sel: usize, restant: i32) {
    let d = theme::disposition(e.l, e.h, menu.n as u32);
    let k = d.echelle;
    let p = d.panneau;
    rect(e, 0, 0, e.l, e.h, th.fond);
    rect(e, p.x, p.y, p.l, p.h, th.panneau);
    rect(e, p.x, p.y, p.l, 2 * k, th.selection); // liseré haut
    let cx = p.x + p.l / 2;
    let kt = k + 1; // titre un cran plus grand
    texte(e, cx - largeur(th.titre(), kt) / 2, d.titre_y, th.titre(), th.texte, kt);
    let sous = b"puppy-GRUB - choisissez un systeme";
    texte(e, cx - largeur(sous, k) / 2, d.titre_y + 16 * kt, sous, th.texte_doux, k);
    for i in 0..menu.n {
        let y = d.entree_y + i as u32 * d.ligne_h;
        let t = menu.entrees[i].titre_str().as_bytes();
        if i == sel {
            rect(e, p.x + 8 * k, y, p.l - 16 * k, d.ligne_h - 4 * k, th.selection);
            texte(e, p.x + 16 * k, y + 2 * k, b">", th.texte_selection, k);
            texte(e, p.x + 26 * k, y + 2 * k, t, th.texte_selection, k);
        } else {
            texte(e, p.x + 26 * k, y + 2 * k, t, th.texte, k);
        }
    }
    let aide = b"Haut/Bas : choisir   Entree : demarrer   Echap : micrologiciel";
    let ka = if largeur(aide, k) + 16 * k > p.l { 1 } else { k };
    texte(e, cx - largeur(aide, ka) / 2, d.aide_y, aide, th.texte_doux, ka);
    if restant > 0 {
        let mut msg = *b"Demarrage automatique dans    s";
        let mut n = restant as u32;
        let mut i = 30usize;
        loop {
            msg[i - 1] = b'0' + (n % 10) as u8;
            n /= 10;
            i -= 1;
            if n == 0 || i == 27 { break; }
        }
        texte(e, cx - largeur(&msg, ka) / 2, d.aide_y + 14 * ka, &msg, th.texte_doux, ka);
    }
}

// ------------------------------ affichage menu ------------------------------
fn dessiner(out: *mut SimpleTextOutput, menu: &Menu, sel: usize, restant: i32) {
    unsafe { ((*out).clear_screen)(out) };
    ecrire(out, "\n   puppy-GRUB ");
    ecrire(out, VERSION);
    ecrire(out, "  (maison, pas GNU GRUB)\n");
    ecrire(out, "   Choisissez un systeme :\n\n");
    for i in 0..menu.n {
        if i == sel {
            unsafe { ((*out).set_attribute)(out, 0x70) }; // noir sur gris = selection
            ecrire(out, "   > ");
        } else {
            unsafe { ((*out).set_attribute)(out, 0x07) }; // gris sur noir
            ecrire(out, "     ");
        }
        ecrire(out, menu.entrees[i].titre_str());
        ecrire(out, "   \n");
    }
    unsafe { ((*out).set_attribute)(out, 0x07) };
    ecrire(out, "\n   ^/v : choisir    Entree : demarrer    Echap : micrologiciel\n");
    if restant > 0 {
        ecrire(out, "   Demarrage automatique dans ");
        ecrire_u32(out, restant as u32);
        ecrire(out, " s...\n");
    }
}

// --------------------------------- clavier ----------------------------------
/// Lit une touche, en attendant ~1 s (20 × 50 ms). None si rien n'est venu.
fn lire_touche_1s(inp: *mut SimpleTextInput, bs: *mut BootServices) -> Option<InputKey> {
    for _ in 0..20 {
        let mut k = InputKey::default();
        let st = unsafe { ((*inp).read_key_stroke)(inp, &mut k) };
        if st == SUCCESS {
            return Some(k);
        }
        unsafe { ((*bs).stall)(50_000) }; // 50 ms
    }
    None
}

// --------------------------------- divers -----------------------------------
fn ecrire_u32(out: *mut SimpleTextOutput, mut n: u32) {
    let mut chiffres = [0u8; 10];
    let mut i = chiffres.len();
    if n == 0 {
        ecrire(out, "0");
        return;
    }
    while n > 0 {
        i -= 1;
        chiffres[i] = b'0' + (n % 10) as u8;
        n /= 10;
    }
    ecrire(out, core::str::from_utf8(&chiffres[i..]).unwrap_or("?"));
}

#[panic_handler]
fn panique(_info: &PanicInfo) -> ! {
    loop {
        core::hint::spin_loop();
    }
}
