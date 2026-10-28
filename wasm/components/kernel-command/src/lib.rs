//! Kernel component Command (dhf/spec/01-start-a-project/command.feature,
//! dhf/spec/11-project-in-a-folder/command.feature), built to
//! kernel-command-component (dhf/wit/kernel-command.wit). Its rules are in the
//! kernel's shared crate (kernel/, rdm-kernel), where they are unit-tested.
#![cfg_attr(target_arch = "wasm32", no_std)]

extern crate alloc;

#[cfg(target_arch = "wasm32")]
mod component;
