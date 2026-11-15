//! Fonctions mémoire minimales (memcpy, memset…) pour le repli sans rustup :
//! sans elles, core ne se lie pas hors de la cible UEFI officielle.
#![no_std]
#[no_mangle] pub unsafe extern "C" fn memcpy(d:*mut u8,s:*const u8,n:usize)->*mut u8{let mut i=0;while i<n{core::ptr::write_volatile(d.add(i),*s.add(i));i+=1;}d}
#[no_mangle] pub unsafe extern "C" fn memmove(d:*mut u8,s:*const u8,n:usize)->*mut u8{if (d as usize)<(s as usize){let mut i=0;while i<n{core::ptr::write_volatile(d.add(i),*s.add(i));i+=1;}}else{let mut i=n;while i>0{i-=1;core::ptr::write_volatile(d.add(i),*s.add(i));}}d}
#[no_mangle] pub unsafe extern "C" fn memset(d:*mut u8,c:i32,n:usize)->*mut u8{let mut i=0;while i<n{core::ptr::write_volatile(d.add(i),c as u8);i+=1;}d}
#[no_mangle] pub unsafe extern "C" fn memcmp(a:*const u8,b:*const u8,n:usize)->i32{let mut i=0;while i<n{let x=*a.add(i);let y=*b.add(i);if x!=y{return x as i32-y as i32;}i+=1;}0}
#[no_mangle] pub unsafe extern "C" fn bcmp(a:*const u8,b:*const u8,n:usize)->i32{memcmp(a,b,n)}
