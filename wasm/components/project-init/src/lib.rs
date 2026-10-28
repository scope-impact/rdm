//! Component Init of the project context (dhf/spec/01-start-a-project/
//! init.feature): writes a new project's skeleton into a path that does not
//! exist yet.
#![cfg_attr(target_arch = "wasm32", no_std)]
extern crate alloc;

pub mod skeleton;

#[cfg(target_arch = "wasm32")]
mod component {
    use crate::skeleton;
    use alloc::format;
    use alloc::string::String;
    use alloc::vec::Vec;
    use rdm_kernel::path::{join, parent};
    use rdm_kernel::sys;
    rdm_kernel::bindings!("rdm:spec/project-init-component", with_host);
    use exports::rdm::spec::project_init::{Guest, Initialised, SkeletonFile};

    struct Init;
    export!(Init);

    impl Guest for Init {
        fn skeleton(id_prefix: String) -> Vec<SkeletonFile> {
            skeleton::files(&id_prefix)
                .into_iter()
                .map(|(path, content)| SkeletonFile { path: path.into(), content })
                .collect()
        }

        async fn init(path: String, id_prefix: Option<String>) -> Result<Initialised, String> {
            let prefix = id_prefix.unwrap_or_else(|| "spec".into());
            skeleton::check_prefix(&prefix)?;
            let abs = sys::absolute(&path);
            if sys::exists(&abs).await? {
                return Err(format!("{path} already exists: init writes only into a path that does not"));
            }
            if !sys::is_dir(&parent(&abs)).await {
                sys::create_dir_all(&parent(&abs)).await?;
            }
            sys::create_dir_all(&abs).await?;
            sys::git(&abs, &["init", "-q"]).await?;
            let mut wrote = Vec::new();
            for (rel, content) in skeleton::files(&prefix) {
                let file = join(&abs, rel);
                sys::create_dir_all(&parent(&file)).await?;
                sys::write_new(&file, content.as_bytes()).await?;
                wrote.push(String::from(rel));
            }
            let hooks = rdm::spec::project_hooks::install(abs.clone()).await?;
            wrote.extend(hooks.wrote);
            wrote.sort();
            Ok(Initialised { path: path.clone(), wrote, next_steps: skeleton::next_steps(&path) })
        }
    }
}
