//! The stand-in for every slice not built yet (dhf/wit/rdm-spec.wit, world
//! not-built; ED-02): its tasks name none and run none, so the command answers
//! each task of those slices that it is not built. No logic.
#![cfg_attr(target_arch = "wasm32", no_std)]

#[cfg(target_arch = "wasm32")]
mod component {
    extern crate alloc;
    use alloc::vec::Vec;

    // The kernel's runtime: allocator, cabi_realloc, panic handler.
    use rdm_kernel as _;

    wit_bindgen::generate!({
        path: "../../dhf/wit",
        world: "rdm:spec/not-built",
    });

    struct NotBuilt;
    export!(NotBuilt);

    macro_rules! stand_in {
        ($($m:ident),*) => {$(
            impl exports::$m::Guest for NotBuilt {
                fn entries() -> Vec<exports::$m::Entry> {
                    Vec::new()
                }
                async fn run(_: exports::$m::Request) -> Option<exports::$m::Outcome> {
                    None
                }
            }
        )*};
    }
    stand_in!(write_the_spec, ground_the_spec, model_the_architecture, verify_by_results, regenerate, measure, plugins, publish, regulated_profile);
}
