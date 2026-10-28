//! The native host of rdm-spec's WebAssembly build (ADR 0006): it runs the
//! composed command (`wasm/build/rdm-spec.wasm`) with the arguments,
//! environment and standard streams it was given, on WASI 0.3, with the
//! folder it runs in preopened as "." and the root as "/"
//! (dhf/spec/01-start-a-project/decisions.md, ED-05), and gives it
//! `rdm:host/process` (`dhf/wit/deps/rdm-host/host.wit`). It returns the
//! command's exit code, the one `wasi:cli/exit` was given (ED-03). It holds
//! no rdm-spec logic: it runs what the core asks, and reports.

mod process;

use std::path::PathBuf;
use wasmtime::component::{Component, HasSelf, Linker, ResourceTable};
use wasmtime::{Config, Engine, Store};
use wasmtime_wasi::p3::bindings::Command;
use wasmtime_wasi::FsPerms;
use wasmtime_wasi::{I32Exit, WasiCtx, WasiCtxView, WasiView};

pub struct State {
    wasi: WasiCtx,
    table: ResourceTable,
    pub processes: process::Processes,
}

impl WasiView for State {
    fn ctx(&mut self) -> WasiCtxView<'_> {
        WasiCtxView { ctx: &mut self.wasi, table: &mut self.table }
    }
}

fn main() {
    let mut argv = std::env::args_os().skip(1);
    let Some(wasm) = argv.next().map(PathBuf::from) else {
        eprintln!("cannot-run: usage: rdm-spec-host COMPONENT [ARGS...]");
        std::process::exit(2);
    };
    let args: Vec<String> = argv.map(|a| a.to_string_lossy().into_owned()).collect();
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build();
    let code = match runtime {
        Ok(rt) => rt.block_on(run(&wasm, &args)),
        Err(e) => Err(wasmtime::Error::from(e)),
    };
    match code {
        Ok(code) => std::process::exit(code),
        Err(e) => {
            let why = format!("{e:#}").replace('\n', " ");
            eprintln!("cannot-run: the command failed: {why}");
            std::process::exit(2);
        }
    }
}

async fn run(wasm: &std::path::Path, args: &[String]) -> wasmtime::Result<i32> {
    let mut config = Config::new();
    config.wasm_component_model_async(true);
    let engine = Engine::new(&config)?;
    let mut linker: Linker<State> = Linker::new(&engine);
    wasmtime_wasi::p3::add_to_linker(&mut linker)?;
    process::add_to_linker::<State, HasSelf<State>>(&mut linker, |s| s)?;
    let component = Component::from_file(&engine, wasm)?;

    let cwd = std::env::current_dir()?;
    let mut builder = WasiCtx::builder();
    let mut all = vec!["rdm-spec".to_string()];
    all.extend(args.iter().cloned());
    builder
        .inherit_stdio()
        .inherit_env()
        .args(&all)
        .initial_cwd(cwd.to_string_lossy())
        .preopened_dir("/", "/", FsPerms::ReadWrite)?
        .preopened_dir(&cwd, ".", FsPerms::ReadWrite)?;
    let state = State { wasi: builder.build(), table: ResourceTable::new(), processes: Default::default() };
    let mut store = Store::new(&engine, state);
    let command = Command::instantiate_async(&mut store, &component, &linker).await?;
    let result = store
        .run_concurrent(async move |store| command.wasi_cli_run().call_run(store).await)
        .await;
    let code = match result {
        Ok(Ok(Ok(()))) => 0,
        Ok(Ok(Err(()))) => 1,
        Ok(Err(e)) | Err(e) => match e.downcast_ref::<I32Exit>() {
            Some(I32Exit(code)) => *code,
            None => return Err(e),
        },
    };
    store.data_mut().processes.stop_all();
    Ok(code)
}
