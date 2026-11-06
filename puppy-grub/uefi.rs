//! uefi.rs — liaisons UEFI écrites à la main pour puppy-GRUB.
//!
//! On n'utilise AUCUNE crate externe (pas de Cargo, pas de crates.io : c'est la
//! règle du projet). On déclare donc nous-mêmes le minimum de l'interface UEFI
//! dont le gestionnaire d'amorçage a besoin : la table système, la sortie texte
//! (bannière + secours), les services de démarrage (temporisation, mémoire,
//! chargement/lancement d'une image EFI pour le chaînage dualboot), et le
//! protocole graphique GOP (menu). Tout le reste des tables est laissé opaque.
//!
//! Références : spec UEFI 2.x. Les décalages/signatures sont fixés par la spec,
//! donc ces structures `#[repr(C)]` doivent respecter l'ordre exact des champs.
#![allow(dead_code, non_snake_case)]

use core::ffi::c_void;

pub type Status = usize;
pub type Handle = *mut c_void;
pub type Char16 = u16;

pub const SUCCESS: Status = 0;
// bit de poids fort positionné = erreur (spec UEFI)
pub const ERREUR: Status = 1usize << (usize::BITS - 1);
pub const NOT_FOUND: Status = ERREUR | 14;
pub const NOT_READY: Status = ERREUR | 6;

// Types d'allocation mémoire (AllocatePool) : 2 = EfiLoaderData.
pub const LOADER_DATA: usize = 2;

// Codes de touches (EFI_INPUT_KEY.scan_code)
pub const SCAN_UP: u16 = 0x01;
pub const SCAN_DOWN: u16 = 0x02;
pub const SCAN_ESC: u16 = 0x17;
pub const CHAR_CR: u16 = 0x0d; // Entrée (unicode_char)

// GUID UEFI (données brutes, comparées octet à octet).
#[repr(C)]
#[derive(Clone, Copy, PartialEq, Eq)]
pub struct Guid {
    pub d1: u32,
    pub d2: u16,
    pub d3: u16,
    pub d4: [u8; 8],
}

// Touche lue par le protocole d'entrée texte.
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct InputKey {
    pub scan_code: u16,
    pub unicode_char: u16,
}

// Protocole d'entrée texte simple (clavier : navigation du menu).
#[repr(C)]
pub struct SimpleTextInput {
    pub reset: extern "efiapi" fn(*mut SimpleTextInput, bool) -> Status,
    pub read_key_stroke: extern "efiapi" fn(*mut SimpleTextInput, *mut InputKey) -> Status,
    pub wait_for_key: *mut c_void,
}

// Protocole de sortie texte simple (bannière, messages de secours).
#[repr(C)]
pub struct SimpleTextOutput {
    pub reset: extern "efiapi" fn(*mut SimpleTextOutput, bool) -> Status,
    pub output_string: extern "efiapi" fn(*mut SimpleTextOutput, *const Char16) -> Status,
    pub test_string: extern "efiapi" fn(*mut SimpleTextOutput, *const Char16) -> Status,
    pub query_mode: extern "efiapi" fn(*mut SimpleTextOutput, usize, *mut usize, *mut usize) -> Status,
    pub set_mode: extern "efiapi" fn(*mut SimpleTextOutput, usize) -> Status,
    pub set_attribute: extern "efiapi" fn(*mut SimpleTextOutput, usize) -> Status,
    pub clear_screen: extern "efiapi" fn(*mut SimpleTextOutput) -> Status,
    // (suite ignorée : set_cursor_position, enable_cursor, mode)
}

// En-tête commun à toutes les tables UEFI.
#[repr(C)]
pub struct TableHeader {
    pub signature: u64,
    pub revision: u32,
    pub header_size: u32,
    pub crc32: u32,
    pub reserved: u32,
}

// Services de démarrage : on type ce dont on a besoin, le reste reste opaque via
// un tableau d'octets de remplissage pour conserver les bons décalages.
#[repr(C)]
pub struct BootServices {
    pub hdr: TableHeader,

    // Task Priority (2 fn)
    _raise_tpl: *const c_void,
    _restore_tpl: *const c_void,

    // Memory Services (4 fn) — allocate_pages, free_pages, get_memory_map, allocate_pool, free_pool
    _allocate_pages: *const c_void,
    _free_pages: *const c_void,
    _get_memory_map: *const c_void,
    pub allocate_pool: extern "efiapi" fn(usize, usize, *mut *mut c_void) -> Status,
    pub free_pool: extern "efiapi" fn(*mut c_void) -> Status,

    // Event & Timer (6 fn)
    _create_event: *const c_void,
    _set_timer: *const c_void,
    _wait_for_event: *const c_void,
    _signal_event: *const c_void,
    _close_event: *const c_void,
    _check_event: *const c_void,

    // Protocol Handler (9 fn)
    _install_protocol_interface: *const c_void,
    _reinstall_protocol_interface: *const c_void,
    _uninstall_protocol_interface: *const c_void,
    pub handle_protocol: extern "efiapi" fn(Handle, *const Guid, *mut *mut c_void) -> Status,
    _reserved: *const c_void,
    _register_protocol_notify: *const c_void,
    pub locate_handle: extern "efiapi" fn(usize, *const Guid, *const c_void, *mut usize, *mut Handle) -> Status,
    _locate_device_path: *const c_void,
    _install_configuration_table: *const c_void,

    // Image Services (5 fn) — c'est le cœur du chaînage dualboot
    pub load_image: extern "efiapi" fn(bool, Handle, *const c_void, *const c_void, usize, *mut Handle) -> Status,
    pub start_image: extern "efiapi" fn(Handle, *mut usize, *mut *mut Char16) -> Status,
    _exit: *const c_void,
    _unload_image: *const c_void,
    _exit_boot_services: *const c_void,

    // Misc (2 fn)
    _get_next_monotonic_count: *const c_void,
    pub stall: extern "efiapi" fn(usize) -> Status, // microsecondes

    // (la suite des services n'est pas nécessaire ici)
}

// Table système : fournisseur + révision + consoles + services de démarrage.
#[repr(C)]
pub struct SystemTable {
    pub hdr: TableHeader,
    pub firmware_vendor: *const Char16,
    pub firmware_revision: u32,
    pub console_in_handle: Handle,
    pub con_in: *mut SimpleTextInput,
    pub console_out_handle: Handle,
    pub con_out: *mut SimpleTextOutput,
    pub standard_error_handle: Handle,
    pub std_err: *mut SimpleTextOutput,
    pub runtime_services: *mut c_void,
    pub boot_services: *mut BootServices,
    pub number_of_table_entries: usize,
    pub configuration_table: *mut c_void,
}

// ---------------------------- GOP (menu graphique) ----------------------------
pub const GOP_GUID: Guid = Guid {
    d1: 0x9042a9de,
    d2: 0x23dc,
    d3: 0x4a38,
    d4: [0x96, 0xfb, 0x7a, 0xde, 0xd0, 0x80, 0x51, 0x6a],
};

#[repr(C)]
pub struct GopModeInfo {
    pub version: u32,
    pub horizontal_resolution: u32,
    pub vertical_resolution: u32,
    pub pixel_format: u32,
    pub pixel_information: [u32; 4],
    pub pixels_per_scan_line: u32,
}

#[repr(C)]
pub struct GopMode {
    pub max_mode: u32,
    pub mode: u32,
    pub info: *mut GopModeInfo,
    pub size_of_info: usize,
    pub frame_buffer_base: u64,
    pub frame_buffer_size: usize,
}

#[repr(C)]
pub struct GraphicsOutput {
    pub query_mode: extern "efiapi" fn(*mut GraphicsOutput, u32, *mut usize, *mut *mut GopModeInfo) -> Status,
    pub set_mode: extern "efiapi" fn(*mut GraphicsOutput, u32) -> Status,
    pub blt: *const c_void,
    pub mode: *mut GopMode,
}

// ----------------------- image chargée (volume de boot) -----------------------
pub const LOADED_IMAGE_GUID: Guid = Guid {
    d1: 0x5b1b31a1, d2: 0x9562, d3: 0x11d2,
    d4: [0x8e, 0x3f, 0x00, 0xa0, 0xc9, 0x69, 0x72, 0x3b],
};

#[repr(C)]
pub struct LoadedImage {
    pub revision: u32,
    pub parent_handle: Handle,
    pub system_table: *mut SystemTable,
    pub device_handle: Handle, // le volume depuis lequel on a été chargé (l'ESP)
    pub file_path: *mut c_void,
    pub reserved: *mut c_void,
    pub load_options_size: u32,
    pub load_options: *mut c_void,
    pub image_base: *mut c_void,
    pub image_size: u64,
    pub image_code_type: u32,
    pub image_data_type: u32,
    pub unload: *const c_void,
}

// --------------------------- système de fichiers ESP --------------------------
pub const SIMPLE_FS_GUID: Guid = Guid {
    d1: 0x964e5b22, d2: 0x6459, d3: 0x11d2,
    d4: [0x8e, 0x39, 0x00, 0xa0, 0xc9, 0x69, 0x72, 0x3b],
};
pub const FILE_INFO_GUID: Guid = Guid {
    d1: 0x09576e92, d2: 0x6d3f, d3: 0x11d2,
    d4: [0x8e, 0x39, 0x00, 0xa0, 0xc9, 0x69, 0x72, 0x3b],
};

// mode d'ouverture lecture seule
pub const FILE_MODE_READ: u64 = 0x0000000000000001;

#[repr(C)]
pub struct SimpleFileSystem {
    pub revision: u64,
    pub open_volume: extern "efiapi" fn(*mut SimpleFileSystem, *mut *mut FileProtocol) -> Status,
}

#[repr(C)]
pub struct FileProtocol {
    pub revision: u64,
    pub open: extern "efiapi" fn(*mut FileProtocol, *mut *mut FileProtocol, *const Char16, u64, u64) -> Status,
    pub close: extern "efiapi" fn(*mut FileProtocol) -> Status,
    _delete: *const c_void,
    pub read: extern "efiapi" fn(*mut FileProtocol, *mut usize, *mut c_void) -> Status,
    _write: *const c_void,
    _get_position: *const c_void,
    pub set_position: extern "efiapi" fn(*mut FileProtocol, u64) -> Status,
    pub get_info: extern "efiapi" fn(*mut FileProtocol, *const Guid, *mut usize, *mut c_void) -> Status,
    // (set_info, flush : non utilisés)
}

// EFI_FILE_INFO : on ne lit que la taille du fichier (offset 8).
#[repr(C)]
pub struct FileInfo {
    pub size: u64,
    pub file_size: u64,
    pub physical_size: u64,
    pub create_time: [u8; 16],
    pub last_access_time: [u8; 16],
    pub modification_time: [u8; 16],
    pub attribute: u64,
    // suivi du nom (tableau de Char16) — non lu
}

// -------------------------------- aides sûres --------------------------------

/// Écrit une chaîne ASCII sur la console texte (conversion en UTF-16 à la volée,
/// par petits morceaux, sans allocation). Sert à la bannière et au secours.
pub fn ecrire(out: *mut SimpleTextOutput, s: &str) {
    const N: usize = 64;
    let mut tampon = [0u16; N + 1];
    let mut i = 0;
    for b in s.bytes() {
        // '\n' -> "\r\n" pour les consoles UEFI
        if b == b'\n' {
            tampon[i] = b'\r' as u16;
            i += 1;
            if i >= N {
                tampon[i] = 0;
                unsafe { ((*out).output_string)(out, tampon.as_ptr()) };
                i = 0;
            }
        }
        tampon[i] = b as u16;
        i += 1;
        if i >= N {
            tampon[i] = 0;
            unsafe { ((*out).output_string)(out, tampon.as_ptr()) };
            i = 0;
        }
    }
    if i > 0 {
        tampon[i] = 0;
        unsafe { ((*out).output_string)(out, tampon.as_ptr()) };
    }
}
