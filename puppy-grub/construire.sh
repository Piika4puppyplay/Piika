#!/bin/sh
# ---------------------------------------------------------------------
#  Construit puppy-GRUB (application UEFI) et, avec --tester, le lance
#  dans QEMU+OVMF avec un menu d'exemple et un faux noyau, pour vérifier
#  le chaînage. Ne touche PAS le démarrage réel de PuppyOS.
#
#  Avec rustup : rustup target add x86_64-unknown-uefi (sinon repli binutils).
#  Pour --tester : qemu-system-x86_64 et OVMF (ESP = dossier FAT virtuel).
# ---------------------------------------------------------------------
set -eu
ICI=$(cd "$(dirname "$0")" && pwd)       # le dossier puppy-grub/
SORTIE="${B:-$ICI/build}"
mkdir -p "$SORTIE"

# 0.3 : tests de la brique pure (thèmes .pupgrub + mise en page), sur le PC.
echo "🐾 Tests Rust de la brique thème/mise en page…"
rustc --edition 2021 --test "$ICI/theme.rs" -o "$SORTIE/test-theme" && "$SORTIE/test-theme" -q

# Cible UEFI officielle si rustup l'a installée ; sinon (rustc de la
# distribution, sans « core » UEFI) : objet x86_64 sans std, lié en ELF à
# adresse fixe puis converti en PE/EFI par objcopy (binutils).
if rustc --print target-libdir --target x86_64-unknown-uefi 2>/dev/null | xargs -I{} sh -c 'ls {}/libcore-* >/dev/null 2>&1'; then
    echo "🐾 Compilation de puppy-GRUB (cible x86_64-unknown-uefi)…"
    rustc --target x86_64-unknown-uefi -C panic=abort -O \
        "$ICI/puppy-grub.rs" -o "$SORTIE/BOOTX64.EFI"
else
    echo "🐾 Compilation de puppy-GRUB (sans rustup : ELF -> PE via objcopy)…"
    rustc --edition 2021 --target x86_64-unknown-linux-gnu --crate-type=staticlib -C panic=abort \
        -C opt-level=2 -C relocation-model=static "$ICI/puppy-grub.rs" -o "$SORTIE/libpuppygrub.a" 2>/dev/null
    rustc --edition 2021 --target x86_64-unknown-linux-gnu --crate-type=lib --emit=obj -C panic=abort \
        -C opt-level=2 -C relocation-model=static "$ICI/efi-mem.rs" -o "$SORTIE/efi-mem.o"
    ld -static -nostdlib --gc-sections -T "$ICI/efi.ld" -o "$SORTIE/puppy-grub.elf" "$SORTIE/efi-mem.o" "$SORTIE/libpuppygrub.a"
    objcopy -O efi-app-x86_64 --subsystem=10 --image-base=0x4000000 "$SORTIE/puppy-grub.elf" "$SORTIE/BOOTX64.EFI"
fi
echo "🐾 puppy-GRUB prêt : $SORTIE/BOOTX64.EFI"

[ "${1:-}" = "--tester" ] || exit 0

OVMF_CODE=${OVMF_CODE:-/usr/share/OVMF/OVMF_CODE_4M.fd}
OVMF_VARS=${OVMF_VARS:-/usr/share/OVMF/OVMF_VARS_4M.fd}
[ -f "$OVMF_CODE" ] || { echo "OVMF introuvable ($OVMF_CODE)"; exit 1; }

echo "🐾 Faux noyau de test…"
cat > "$SORTIE/mini-os.rs" <<'EOF'
#![no_std]
#![no_main]
use core::panic::PanicInfo;
#[repr(C)] struct Sto { reset: usize, output_string: extern "efiapi" fn(*mut Sto,*const u16)->usize, rest:[usize;6] }
#[repr(C)] struct St { hdr:[u8;24], vendor:*const u16, rev:u32, cih:usize, ci:usize, coh:usize, con_out:*mut Sto }
#[no_mangle] pub extern "efiapi" fn efi_main(_h:usize, t:*mut St)->usize {
    let msg:&[u8]=b"\r\n\r\n  === chaine par puppy-GRUB : faux noyau PuppyOS OK ===\r\n";
    let mut u=[0u16;128]; let mut i=0; for &b in msg { u[i]=b as u16; i+=1; } u[i]=0;
    unsafe { let o=(*t).con_out; ((*o).output_string)(o,u.as_ptr()); }
    loop { core::hint::spin_loop(); }
}
#[panic_handler] fn p(_:&PanicInfo)->!{loop{}}
EOF
rustc --target x86_64-unknown-uefi -C panic=abort -O "$SORTIE/mini-os.rs" -o "$SORTIE/puppyos.efi"

echo "🐾 ESP de test (dossier FAT virtuel de QEMU, sans mtools)…"
ESP="$SORTIE/esp"
rm -rf "$ESP"; mkdir -p "$ESP/EFI/BOOT" "$ESP/EFI/PUPPYOS"
cp "$SORTIE/BOOTX64.EFI" "$ESP/EFI/BOOT/BOOTX64.EFI"
# NOYAU=<bzImage EFI> pour chaîner un vrai noyau PuppyOS au lieu du faux
cp "${NOYAU:-$SORTIE/puppyos.efi}" "$ESP/EFI/PUPPYOS/puppyos.efi"
cp "$ICI/grub.conf.exemple" "$ESP/EFI/PUPPYOS/grub.conf"
[ -n "${THEME_TEST:-}" ] && cp "$THEME_TEST" "$ESP/EFI/PUPPYOS/theme.pupgrub"
cp "$OVMF_VARS" "$SORTIE/vars.fd"

echo "🐾 Lancement QEMU (Échap pour quitter, démarrage auto après le délai)…"
exec qemu-system-x86_64 -machine q35 -m 1024 \
    -drive if=pflash,format=raw,readonly=on,file="$OVMF_CODE" \
    -drive if=pflash,format=raw,file="$SORTIE/vars.fd" \
    -drive format=raw,file=fat:rw:"$ESP" -net none ${QEMU_OPTIONS:-}
