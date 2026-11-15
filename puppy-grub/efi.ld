/* Script de liaison du repli sans rustup : image à adresse fixe 0x4000000,
   convertie ensuite en PE/EFI par objcopy (--image-base identique). */
ENTRY(efi_main)
SECTIONS {
  . = 0x4000000;
  ImageBase = .;
  . += 0x1000;
  .text ALIGN(0x1000) : { *(.text .text.*) }
  .rodata ALIGN(0x1000) : { *(.rodata .rodata.*) }
  .data ALIGN(0x1000) : { *(.data .data.* .got .got.plt) }
  .bss ALIGN(0x1000) : { *(.bss .bss.* COMMON) }
  /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) *(.debug*) }
}
