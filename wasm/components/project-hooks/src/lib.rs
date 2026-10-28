//! Component Hooks of the project context (dhf/spec/01-start-a-project/
//! hooks.feature): installs the pre-commit hook that runs "rdm-spec check".
#![cfg_attr(target_arch = "wasm32", no_std)]
extern crate alloc;

pub mod hook;

#[cfg(target_arch = "wasm32")]
mod component {
    use crate::hook;
    use alloc::format;
    use alloc::string::String;
    use alloc::vec::Vec;
    use rdm_kernel::path::join;
    use rdm_kernel::sys;
    rdm_kernel::bindings!("rdm:spec/project-hooks-component", with_host);
    use exports::rdm::spec::project_hooks::{Guest, Installed};

    struct Hooks;
    export!(Hooks);

    impl Guest for Hooks {
        async fn install(root: String) -> Result<Installed, String> {
            let root = sys::absolute(&root);
            // The hook needs a git repository: refuse, writing nothing, outside one.
            if sys::git(&root, &["rev-parse", "--is-inside-work-tree"]).await.map(|o| o.trim() == "true") != Ok(true) {
                return Err(format!("{root} is not a git repository, and the pre-commit hook needs one"));
            }
            let path = join(&root, hook::PATH);
            let hooks_path = sys::git(&root, &["config", "--get", "core.hooksPath"]).await.ok().map(|s| String::from(s.trim()));
            let plan = hook::plan(sys::exists(&path).await?, hooks_path.as_deref());
            let mut installed = Installed { wrote: Vec::new(), kept: Vec::new() };
            if plan.write_hook {
                sys::create_dir_all(&join(&root, ".githooks")).await?;
                sys::write_new(&path, hook::HOOK.as_bytes()).await?;
                // WASI carries no permission bits: the host runs chmod (ED-04).
                let out = sys::run(&["chmod", "+x", &path], &root).await?;
                if out.exit != Some(0) {
                    return Err(format!("{}: cannot be made executable", hook::PATH));
                }
                installed.wrote.push(hook::PATH.into());
            } else {
                installed.kept.push(hook::PATH.into());
            }
            if plan.set_hooks_path {
                sys::git(&root, &["config", "core.hooksPath", ".githooks"]).await?;
            }
            Ok(installed)
        }
    }
}
