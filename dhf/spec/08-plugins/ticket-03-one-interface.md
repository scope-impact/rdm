# Ticket 03: One interface: what a plugin says becomes the task's report

Slice: `08-plugins` (`plan.md`, Scope In: "one interface"); `plugins.feature`,
Rules "A subscriber receives each event it subscribes to" and "A plugin
answers through one interface". Builds on ticket 02.

## What it delivers

kernel/plugins, every plugin's run (ED-08) read into an `answer`, and the
plugins stage's answer to check (ED-09):

- Subscribers in the order declared, after the core's own stages (the
  plugins stage is check's last), each given its events (G-01).
- Exit 0: its warning lines, as warnings; exit 1: its finding lines, as
  findings, each unchanged (spec-0354; G-06 for a line of neither shape, and
  for exit 1 naming none); exit 2: cannot run, naming the plugin and relaying
  its line (spec-0354); any other exit as G-07 settles; its standard output
  as G-05 settles.
- Past its `:seconds`: stopped with its process group by the host, cannot
  run, saying it timed out after its seconds and relaying what it printed;
  no process of it left (spec-0355).
- A command that cannot be started: cannot run, naming the plugin and its
  command (spec-0356).
- Several plugins answering cannot-run, or one cannot-run beside another's
  findings: G-02.

## Scenarios it satisfies

- `@spec-0352` Plugins run in the order declared, after the core's own stages, `plugins.feature`
- `@spec-0354` What a plugin says becomes the task's own report (4 rows), `plugins.feature`
- `@spec-0355` A plugin that runs past its time limit is stopped and named as having timed out, `plugins.feature`
- `@spec-0356` A plugin whose command cannot be started cannot run, `plugins.feature`

4 scenarios, verified in cycle 5.

## Components and interfaces it touches

kernel/plugins (`plugins-stage`, the `answer` every way in shares),
`rdm:host/process` (`run`, its time limit and its stopping of the process
group).

## Done when

kernel/plugins' unit tests cover each exit, a timeout, a command not
started, and the round trip of a finding and a warning line through
check's finding record (ED-09); `wasm/test.sh` writes a passing result for
each scenario above, spec-0355 within its 5 seconds; tickets 01 and 02's
results unchanged.
