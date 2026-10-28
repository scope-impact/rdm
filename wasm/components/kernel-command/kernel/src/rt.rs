//! What a component without the standard library needs: an allocator, the
//! canonical ABI's `cabi_realloc`, and a panic handler (a trap, which the host
//! reports as cannot-run). Every component of the build links it once.

use alloc::alloc::{alloc, realloc, Layout};

#[global_allocator]
static ALLOCATOR: dlmalloc::GlobalDlmalloc = dlmalloc::GlobalDlmalloc;

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}

/// # Safety
/// Called by the canonical ABI only, as its contract says.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cabi_realloc(old: *mut u8, old_len: usize, align: usize, new_len: usize) -> *mut u8 {
    unsafe {
        let p = if old_len == 0 {
            if new_len == 0 {
                return align as *mut u8;
            }
            alloc(Layout::from_size_align_unchecked(new_len, align))
        } else {
            realloc(old, Layout::from_size_align_unchecked(old_len, align), new_len)
        };
        if p.is_null() {
            core::arch::wasm32::unreachable()
        }
        p
    }
}

/// Keeps this module linked into a component that uses nothing else of it.
#[inline(never)]
pub fn link() {}

// The C memory functions the compiler calls and no C library provides here
// (a component without the standard library links none). Volatile reads keep
// the compiler from turning a loop back into a call to itself.

/// # Safety
/// As C's memcmp: both regions readable for n bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn memcmp(a: *const u8, b: *const u8, n: usize) -> i32 {
    let mut i = 0;
    while i < n {
        let (x, y) = unsafe { (core::ptr::read_volatile(a.add(i)), core::ptr::read_volatile(b.add(i))) };
        if x != y {
            return x as i32 - y as i32;
        }
        i += 1;
    }
    0
}

/// # Safety
/// As C's bcmp.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bcmp(a: *const u8, b: *const u8, n: usize) -> i32 {
    unsafe { memcmp(a, b, n) }
}
