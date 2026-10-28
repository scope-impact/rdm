//! The kernel's shared code (the glossary's "Kernel"; ED-13): what every
//! component of the build may use. kernel/command's rules are here too, so
//! they are unit-tested natively; its component is the crate above.
//!
//! On wasm32 the crate has no standard library: every capability is WASI 0.3
//! (`wasip3`) or `rdm:host/process`, so a component imports nothing older.
#![cfg_attr(target_arch = "wasm32", no_std)]

extern crate alloc;

pub mod edn;
pub mod path;
pub mod report;
pub mod stages;
pub mod table;
pub mod command;

#[cfg(target_arch = "wasm32")]
pub mod rt;
#[cfg(target_arch = "wasm32")]
pub mod sys;

/// A component's bindings to its world in dhf/wit, its WASI 0.3 imports
/// taken from `wasip3` and rdm:host/process from the kernel, so every
/// component of the build shares one set of them. Called from a crate under
/// wasm/components/.
#[cfg(target_arch = "wasm32")]
#[macro_export]
macro_rules! bindings {
    ($world:literal) => {
        $crate::bindings!($world, {});
    };
    ($world:literal, with_host) => {
        $crate::bindings!($world, { "rdm:host/process@0.1.0": rdm_kernel::sys::host::process, });
    };
    ($world:literal, { $($k:literal : $v:path,)* }) => {
        wit_bindgen::generate!({
            path: "../../../dhf/wit",
            world: $world,
            with: {
                "wasi:cli/environment@0.3.2": wasip3::cli::environment,
                "wasi:cli/exit@0.3.2": wasip3::cli::exit,
                "wasi:cli/types@0.3.2": wasip3::cli::types,
                "wasi:cli/stdin@0.3.2": wasip3::cli::stdin,
                "wasi:cli/stdout@0.3.2": wasip3::cli::stdout,
                "wasi:cli/stderr@0.3.2": wasip3::cli::stderr,
                "wasi:cli/terminal-input@0.3.2": wasip3::cli::terminal_input,
                "wasi:cli/terminal-output@0.3.2": wasip3::cli::terminal_output,
                "wasi:cli/terminal-stdin@0.3.2": wasip3::cli::terminal_stdin,
                "wasi:cli/terminal-stdout@0.3.2": wasip3::cli::terminal_stdout,
                "wasi:cli/terminal-stderr@0.3.2": wasip3::cli::terminal_stderr,
                "wasi:clocks/types@0.3.2": wasip3::clocks::types,
                "wasi:clocks/monotonic-clock@0.3.2": wasip3::clocks::monotonic_clock,
                "wasi:clocks/system-clock@0.3.2": wasip3::clocks::system_clock,
                "wasi:filesystem/types@0.3.2": wasip3::filesystem::types,
                "wasi:filesystem/preopens@0.3.2": wasip3::filesystem::preopens,
                "wasi:sockets/types@0.3.2": wasip3::sockets::types,
                "wasi:sockets/ip-name-lookup@0.3.2": wasip3::sockets::ip_name_lookup,
                "wasi:random/random@0.3.2": wasip3::random::random,
                "wasi:random/insecure@0.3.2": wasip3::random::insecure,
                "wasi:random/insecure-seed@0.3.2": wasip3::random::insecure_seed,
                $($k: $v,)*
            },
        });
    };
}
