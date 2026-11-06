#!/bin/sh
# ---------------------------------------------------------------------
#  Construit puppy-GRUB (application UEFI) et, avec --tester, le lance
#  dans QEMU+OVMF avec un menu d'exemple et un faux noyau, pour vérifier
#  le chaînage. Ne touche PAS le démarrage réel de PuppyOS.
#
#  Dépendance (une fois) : rustup target add x86_64-unknown-uefi
#  Pour --tester : qemu-system-x86_64, OVMF, mkfs.vfat, mtools.
# ---------------------------------------------------------------------
set -eu
ICI=$(cd "$(dirname "$0")" && pwd)       # le dossier puppy-grub/
SORTIE="${B:-$ICI/build}"
mkdir -p "$SORTIE"

echo "🐾 Compilation de puppy-GRUB (cible x86_64-unknown-uefi)…"
rustc --target x86_64-unknown-uefi -C panic=abort -O \
    "$ICI/puppy-grub.rs" -o "$SORTIE/BOOTX64.EFI"
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

echo "🐾 ESP de test…"
ESP="$SORTIE/esp.img"
rm -f "$ESP"; truncate -s 32M "$ESP"
mkfs.vfat -F 16 -n PUPPYGRUB "$ESP" >/dev/null
mmd -i "$ESP" ::/EFI ::/EFI/BOOT ::/EFI/PUPPYOS
mcopy -i "$ESP" "$SORTIE/BOOTX64.EFI" ::/EFI/BOOT/BOOTX64.EFI
mcopy -i "$ESP" "$SORTIE/puppyos.efi" ::/EFI/PUPPYOS/puppyos.efi
mcopy -i "$ESP" "$ICI/grub.conf.exemple" ::/EFI/PUPPYOS/grub.conf
cp "$OVMF_VARS" "$SORTIE/vars.fd"

echo "🐾 Lancement QEMU (Échap pour quitter, démarrage auto après le délai)…"
exec qemu-system-x86_64 -machine q35 -m 256 \
    -drive if=pflash,format=raw,readonly=on,file="$OVMF_CODE" \
    -drive if=pflash,format=raw,file="$SORTIE/vars.fd" \
    -drive format=raw,file="$ESP" -net none
