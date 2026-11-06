//! coffre.rs — coffre de chiffrement AU REPOS des applications privilégiées.
//!
//! PuppyOS n'a que deux binaires à droits élevés : l'autorité des paquets
//! (`paquets-autorite`) et la mise à jour (`puppy-maj`). On les range chiffrés
//! sur le disque, et un petit lanceur de confiance les déchiffre en mémoire
//! seulement (memfd + execveat) pour les exécuter, avec une clé **unique par
//! machine, scellée dans le TPM** et **renouvelée à chaque mise à jour**.
//!
//! CE QUE ÇA PROTÈGE (honnêtement) :
//!   - confidentialité AU REPOS : on ne peut pas lire/désassembler le binaire
//!     privilégié hors ligne sans d'abord vaincre le TPM + le démarrage mesuré ;
//!   - anti-copie : le coffre copié sur une autre machine est inouvrable (clé
//!     scellée au TPM local) ;
//!   - anti-rejeu / anti-retour arrière : la génération est authentifiée et
//!     monotone (une vieille version chiffrée est refusée).
//! CE QUE ÇA NE PROTÈGE PAS : le processus EN COURS (le clair vit en RAM le
//!   temps de tourner). fs-verity (déjà en place) couvre l'intégrité ; ce coffre
//!   ajoute la confidentialité. Deux murs distincts.
//!
//! PROPRIÉTAIRE JAMAIS VERROUILLÉ DEHORS : le clair faisant autorité vient
//! toujours de la mise à jour **signée**. Si le TPM ne peut plus desceller
//! (firmware réinitialisé, PCR changés par une MAJ noyau légitime), une mise à
//! jour signée reprovisionne une clé neuve et rechiffre : on ne peut pas se
//! retrouver bloqué par le coffre.
//!
//! Chiffrement : XChaCha20-Poly1305 (crypto_aead_lock/unlock de Monocypher),
//! AEAD authentifié ; l'en-tête (magie + génération + taille + nonce) sert de
//! données associées, donc toute altération de la génération ou de la taille
//! casse l'authentification. Module PUR : aucune E/S, aucun privilège, aucun
//! appel TPM — ceux-là sont faits par le lanceur C, qui appelle ce cœur testé.
#![allow(dead_code)]

extern "C" {
    fn crypto_aead_lock(
        cipher: *mut u8,
        mac: *mut u8,
        key: *const u8,
        nonce: *const u8,
        ad: *const u8,
        ad_size: usize,
        plain: *const u8,
        text_size: usize,
    );
    fn crypto_aead_unlock(
        plain: *mut u8,
        mac: *const u8,
        key: *const u8,
        nonce: *const u8,
        ad: *const u8,
        ad_size: usize,
        cipher: *const u8,
        text_size: usize,
    ) -> i32;
    fn crypto_wipe(secret: *mut u8, size: usize);
    fn hmac_sha256(cle: *const u8, ncle: usize, msg: *const u8, nmsg: usize, out: *mut u8);
}

pub const MAGIE: &[u8; 8] = b"PUPCOF01";
/// En-tête authentifié : magie(8) + génération(4) + taille_claire(8) + nonce(24).
pub const TETE: usize = 8 + 4 + 8 + 24;
pub const MAC: usize = 16;
/// Les binaires privilégiés sont petits ; on borne large mais fini.
pub const TAILLE_BINAIRE_MAX: u64 = 64 * 1024 * 1024;
/// Première génération, posée au provisionnement.
pub const GENERATION_PROVISION: u32 = 1;

#[derive(Debug, PartialEq, Eq)]
pub enum ErreurCoffre {
    TropGrand,       // clair au-dessus de la borne
    TropCourt,       // blob plus petit que l'en-tête + MAC
    MauvaiseMagie,
    TailleAberrante, // taille_claire incohérente avec le blob
    Downgrade,       // génération < minimum accepté (retour arrière)
    Authentification, // MAC/AD : clé fausse, altération, mauvaise génération
}

/// Contenu ouvert : la génération vérifiée et le binaire en clair.
#[derive(Debug, PartialEq, Eq)]
pub struct Coffre {
    pub generation: u32,
    pub clair: Vec<u8>,
}

fn u32_le(v: u32) -> [u8; 4] {
    v.to_le_bytes()
}
fn u64_le(v: u64) -> [u8; 8] {
    v.to_le_bytes()
}
fn lire_u32(p: &[u8]) -> u32 {
    u32::from_le_bytes([p[0], p[1], p[2], p[3]])
}
fn lire_u64(p: &[u8]) -> u64 {
    let mut b = [0u8; 8];
    b.copy_from_slice(&p[..8]);
    u64::from_le_bytes(b)
}

/// Construit l'en-tête (= données associées authentifiées).
fn entete(generation: u32, taille_claire: u64, nonce: &[u8; 24]) -> [u8; TETE] {
    let mut t = [0u8; TETE];
    t[..8].copy_from_slice(MAGIE);
    t[8..12].copy_from_slice(&u32_le(generation));
    t[12..20].copy_from_slice(&u64_le(taille_claire));
    t[20..44].copy_from_slice(nonce);
    t
}

/// Chiffre `clair` pour cette machine. `nonce` DOIT être unique par (clé) :
/// le lanceur le tire au hasard à chaque scellement (chaque rotation génère
/// de toute façon une clé neuve). Renvoie le blob complet (en-tête ‖ MAC ‖
/// chiffré).
pub fn sceller(cle: &[u8; 32], generation: u32, nonce: &[u8; 24], clair: &[u8]) -> Result<Vec<u8>, ErreurCoffre> {
    if clair.len() as u64 > TAILLE_BINAIRE_MAX {
        return Err(ErreurCoffre::TropGrand);
    }
    let tete = entete(generation, clair.len() as u64, nonce);
    let mut blob = Vec::with_capacity(TETE + MAC + clair.len());
    blob.extend_from_slice(&tete);
    let pos_mac = blob.len();
    blob.extend_from_slice(&[0u8; MAC]);
    blob.resize(pos_mac + MAC + clair.len(), 0);
    // SAFETY : les trois zones (mac, cipher, ad=en-tête) sont distinctes et
    // dans les bornes du Vec ; les pointeurs clé/nonce/clair sont valides.
    unsafe {
        let mac_ptr = blob.as_mut_ptr().add(pos_mac);
        let cipher_ptr = blob.as_mut_ptr().add(pos_mac + MAC);
        crypto_aead_lock(
            cipher_ptr,
            mac_ptr,
            cle.as_ptr(),
            nonce.as_ptr(),
            tete.as_ptr(),
            TETE,
            clair.as_ptr(),
            clair.len(),
        );
    }
    Ok(blob)
}

/// Ouvre un blob pour cette machine. Refuse : magie fausse, blob tronqué,
/// taille incohérente, génération < `generation_min` (retour arrière), et toute
/// altération (clé fausse, octet modifié, génération/taille trafiquées) via
/// l'échec d'authentification AEAD. Jamais de panique sur entrée malformée.
pub fn ouvrir(cle: &[u8; 32], blob: &[u8], generation_min: u32) -> Result<Coffre, ErreurCoffre> {
    if blob.len() < TETE + MAC {
        return Err(ErreurCoffre::TropCourt);
    }
    if &blob[..8] != MAGIE {
        return Err(ErreurCoffre::MauvaiseMagie);
    }
    let generation = lire_u32(&blob[8..12]);
    let taille_claire = lire_u64(&blob[12..20]);
    if taille_claire > TAILLE_BINAIRE_MAX {
        return Err(ErreurCoffre::TailleAberrante);
    }
    // longueur attendue : en-tête + MAC + chiffré (= taille du clair)
    let attendu = (TETE + MAC) as u64 + taille_claire;
    if blob.len() as u64 != attendu {
        return Err(ErreurCoffre::TailleAberrante);
    }
    if generation < generation_min {
        return Err(ErreurCoffre::Downgrade);
    }
    let mut nonce = [0u8; 24];
    nonce.copy_from_slice(&blob[20..44]);
    let tete = entete(generation, taille_claire, &nonce);
    let n = taille_claire as usize;
    let mut clair = vec![0u8; n];
    let mac = &blob[TETE..TETE + MAC];
    let cipher = &blob[TETE + MAC..];
    // SAFETY : tampons distincts, tailles bornées et vérifiées ci-dessus.
    let r = unsafe {
        crypto_aead_unlock(
            clair.as_mut_ptr(),
            mac.as_ptr(),
            cle.as_ptr(),
            nonce.as_ptr(),
            tete.as_ptr(),
            TETE,
            cipher.as_ptr(),
            n,
        )
    };
    if r != 0 {
        // on efface le tampon : aucune fuite de clair non authentifié
        unsafe { crypto_wipe(clair.as_mut_ptr(), clair.len()) };
        return Err(ErreurCoffre::Authentification);
    }
    Ok(Coffre { generation, clair })
}

/// Génération suivante pour une rotation (monotone, bornée). None si la borne
/// u32 est atteinte (jamais en pratique).
pub fn rotation_suivante(generation: u32) -> Option<u32> {
    generation.checked_add(1)
}

/// Dérive la clé du coffre d'une application À PARTIR du secret de machine déjà
/// scellé au TPM (SCEAU_SECRET), par un KDF à clé (HMAC-SHA256). On NE scelle
/// pas une clé indépendante : on réutilise le secret de machine existant (et
/// donc toute la machinerie TPM déjà validée — prédiction du noyau suivant,
/// clé de secours, anti-rejeu). La clé change à chaque rotation car la
/// `generation` entre dans le KDF, et diffère par application via `app`.
///
/// clé = HMAC-SHA256(secret_machine, "puppy-coffre-v1\0" ‖ app ‖ 0 ‖ generation_LE)
pub fn deriver_cle(secret_machine: &[u8; 32], app: &str, generation: u32) -> [u8; 32] {
    let mut msg = Vec::with_capacity(16 + app.len() + 1 + 4);
    msg.extend_from_slice(b"puppy-coffre-v1\0");
    msg.extend_from_slice(app.as_bytes());
    msg.push(0);
    msg.extend_from_slice(&generation.to_le_bytes());
    let mut cle = [0u8; 32];
    // SAFETY : pointeurs et longueurs valides ; out fait 32 octets.
    unsafe {
        hmac_sha256(secret_machine.as_ptr(), 32, msg.as_ptr(), msg.len(), cle.as_mut_ptr());
    }
    cle
}

// ===========================================================================
//                                AUTOTESTS
//  rustc --edition 2021 --test system/pupdate/pupdate.rs \
//        -C link-arg=crypto.o -C link-arg=monocypher.o -C link-arg=mced.o
// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    fn cle(graine: u8) -> [u8; 32] {
        [graine; 32]
    }
    fn nonce(graine: u8) -> [u8; 24] {
        [graine; 24]
    }

    #[test]
    fn aller_retour() {
        let k = cle(7);
        let clair = b"#!/binaire privilegie fictif\x00\x01\x02 ... plein d'octets";
        let blob = sceller(&k, 3, &nonce(1), clair).unwrap();
        // l'en-tête porte la bonne magie et la bonne génération
        assert_eq!(&blob[..8], MAGIE);
        let c = ouvrir(&k, &blob, 1).unwrap();
        assert_eq!(c.generation, 3);
        assert_eq!(c.clair, clair);
    }

    #[test]
    fn mauvaise_cle_refusee() {
        let blob = sceller(&cle(7), 2, &nonce(1), b"secret").unwrap();
        assert_eq!(ouvrir(&cle(8), &blob, 1), Err(ErreurCoffre::Authentification));
    }

    #[test]
    fn chiffre_altere_refuse() {
        let mut blob = sceller(&cle(7), 2, &nonce(1), b"un binaire assez long pour avoir du chiffre").unwrap();
        let dernier = blob.len() - 1;
        blob[dernier] ^= 0x80; // un octet du chiffré
        assert_eq!(ouvrir(&cle(7), &blob, 1), Err(ErreurCoffre::Authentification));
    }

    #[test]
    fn mac_altere_refuse() {
        let mut blob = sceller(&cle(7), 2, &nonce(1), b"charge utile").unwrap();
        blob[TETE] ^= 1; // premier octet du MAC
        assert_eq!(ouvrir(&cle(7), &blob, 1), Err(ErreurCoffre::Authentification));
    }

    #[test]
    fn generation_trafiquee_refusee() {
        // modifier la génération dans l'en-tête (données associées) casse l'auth
        let mut blob = sceller(&cle(7), 5, &nonce(1), b"charge utile").unwrap();
        blob[8] = 9; // génération LE : 5 -> 9
        // 9 >= min, donc pas Downgrade : c'est l'AEAD qui doit refuser
        assert_eq!(ouvrir(&cle(7), &blob, 1), Err(ErreurCoffre::Authentification));
    }

    #[test]
    fn retour_arriere_refuse() {
        let blob = sceller(&cle(7), 2, &nonce(1), b"vieille version").unwrap();
        // la machine exige au moins la génération 3 : une gén. 2 est un downgrade
        assert_eq!(ouvrir(&cle(7), &blob, 3), Err(ErreurCoffre::Downgrade));
    }

    #[test]
    fn magie_fausse_refusee() {
        let mut blob = sceller(&cle(7), 2, &nonce(1), b"x").unwrap();
        blob[0] = b'Z';
        assert_eq!(ouvrir(&cle(7), &blob, 1), Err(ErreurCoffre::MauvaiseMagie));
    }

    #[test]
    fn trop_grand_refuse() {
        // on ne peut pas allouer 64 Mio ici : on vérifie juste la borne logique
        let k = cle(7);
        // un faux « clair » déclaré trop grand est impossible via sceller (prend
        // un vrai &[u8]) ; on teste la borne via un blob dont la taille ment.
        let mut blob = sceller(&k, 2, &nonce(1), b"petit").unwrap();
        blob[12..20].copy_from_slice(&(TAILLE_BINAIRE_MAX + 1).to_le_bytes());
        assert_eq!(ouvrir(&k, &blob, 1), Err(ErreurCoffre::TailleAberrante));
    }

    #[test]
    fn taille_mensongere_refusee() {
        let mut blob = sceller(&cle(7), 2, &nonce(1), b"charge").unwrap();
        // prétend un clair plus long que le blob ne le permet
        blob[12..20].copy_from_slice(&999u64.to_le_bytes());
        assert_eq!(ouvrir(&cle(7), &blob, 1), Err(ErreurCoffre::TailleAberrante));
    }

    #[test]
    fn tronque_ne_panique_pas() {
        let blob = sceller(&cle(7), 2, &nonce(1), b"de quoi avoir un blob complet a tronquer").unwrap();
        for n in 0..blob.len() {
            // aucune troncature ne doit paniquer ; toutes doivent être refusées
            let r = ouvrir(&cle(7), &blob[..n], 1);
            assert!(r.is_err());
        }
    }

    #[test]
    fn clair_vide_ok() {
        let blob = sceller(&cle(7), 2, &nonce(1), b"").unwrap();
        let c = ouvrir(&cle(7), &blob, 1).unwrap();
        assert!(c.clair.is_empty());
    }

    #[test]
    fn rotation_monotone() {
        assert_eq!(rotation_suivante(1), Some(2));
        assert_eq!(rotation_suivante(u32::MAX), None);
    }

    #[test]
    fn derivation_deterministe_et_separee() {
        let secret = [0x5au8; 32];
        // déterministe : même entrée -> même clé
        assert_eq!(deriver_cle(&secret, "paquets-autorite", 1), deriver_cle(&secret, "paquets-autorite", 1));
        // la rotation change la clé
        assert_ne!(deriver_cle(&secret, "paquets-autorite", 1), deriver_cle(&secret, "paquets-autorite", 2));
        // chaque application a une clé distincte
        assert_ne!(deriver_cle(&secret, "paquets-autorite", 1), deriver_cle(&secret, "puppy-maj", 1));
        // un autre secret machine -> clé différente
        assert_ne!(deriver_cle(&secret, "paquets-autorite", 1), deriver_cle(&[0x5bu8; 32], "paquets-autorite", 1));
    }

    #[test]
    fn bout_en_bout_avec_derivation() {
        // chaîne complète : secret machine -> clé dérivée -> sceller -> ouvrir
        let secret = [0x11u8; 32];
        let cle = deriver_cle(&secret, "paquets-autorite", 4);
        let clair = b"ELF du binaire privilegie (fictif) ................";
        let blob = sceller(&cle, 4, &nonce(3), clair).unwrap();
        // la bonne machine, bonne génération -> OK
        let cle2 = deriver_cle(&secret, "paquets-autorite", 4);
        assert_eq!(ouvrir(&cle2, &blob, 4).unwrap().clair, clair);
        // une autre machine (autre secret) ne peut pas ouvrir
        let autre = deriver_cle(&[0x22u8; 32], "paquets-autorite", 4);
        assert_eq!(ouvrir(&autre, &blob, 4), Err(ErreurCoffre::Authentification));
    }
}
