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

mod uefi;

use core::ffi::c_void;
use core::panic::PanicInfo;
use core::ptr;
use uefi::*;

const VERSION: &str = "0.2-menu";
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

    // Boucle du menu.
    let mut sel = if menu.defaut < menu.n { menu.defaut } else { 0 };
    let mut restant = menu.delai;
    loop {
        dessiner(out, &menu, sel, restant);
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
