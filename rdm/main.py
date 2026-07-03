import argparse
import sys
import traceback
from pathlib import Path

import yaml

from rdm.gaps import audit_for_gaps, list_default_checklists
from rdm.collect import collect_from_files
from rdm.hooks import install_hooks
from rdm.init import init
from rdm.pull import pull_from_project_manager
from rdm.render import render_template_to_file
from rdm.translate import translate_test_results, XML_FORMATS
from rdm.util import context_from_data_files, print_error, load_yaml
from rdm.version import __version__


def main():
    try:
        exit_code = cli(sys.argv[1:])
        sys.exit(exit_code)
    except Exception:
        print_error(traceback.format_exc())
        sys.exit(1)


def cli(raw_arguments):
    exit_code = 0
    args = parse_arguments(raw_arguments)
    if args.command is None:
        parse_arguments(['-h'])
    elif args.command == 'render':
        context = context_from_data_files(args.data_files)
        config = load_yaml(args.config)
        render_template_to_file(config, args.template, context, sys.stdout)
    elif args.command == 'init':
        init(args.output)
    elif args.command == 'pull':
        pull_from_project_manager(args.config)
    elif args.command == 'hooks':
        install_hooks(args.dest)
    elif args.command == 'collect':
        snippets = collect_from_files(args.files)
        yaml.dump(snippets, sys.stdout, default_style='|')
    elif args.command == 'translate':
        translate_test_results(args.format, args.input, args.output)
    elif args.command == 'gap' and args.list:
        list_default_checklists()
    elif args.command == 'gap' and args.coverage:
        # In coverage mode, checklist + files can all be checklists or source files
        all_files = ([args.checklist] if args.checklist else []) + args.files
        checklists = [f for f in all_files if f.endswith('.txt')]
        sources = [f for f in all_files if not f.endswith('.txt')]
        exit_code = audit_for_gaps(checklists, sources, True, args.verbose)
    elif args.command == 'gap':
        exit_code = audit_for_gaps(args.checklist, args.files, False, args.verbose)
    elif args.command == 'story':
        exit_code = handle_story_command(args)
    elif args.command == 'pm':
        exit_code = handle_pm_command(args)
    return exit_code


def handle_story_command(args):
    """Handle the story subcommand and its sub-subcommands."""
    try:
        if args.story_command == 'audit':
            from rdm.story_audit.audit import story_audit_command
            repo_path = Path(args.repo) if args.repo else None
            return story_audit_command(repo_path)

        elif args.story_command == 'validate':
            from rdm.story_audit.validate import story_validate_command
            return story_validate_command(
                requirements_dir=Path(args.requirements) if args.requirements else None,
                file_path=Path(args.file) if args.file else None,
                strict=args.strict,
                verbose=args.verbose,
                quiet=args.quiet,
            )

        elif args.story_command == 'sync':
            from rdm.story_audit.sync import story_sync_command
            return story_sync_command(
                backlog_dir=Path(args.backlog_dir) if args.backlog_dir else None,
                output_path=Path(args.output) if args.output else None,
                migrate_only=args.migrate_only,
            )

        elif args.story_command == 'check-ids':
            from rdm.story_audit.check_ids import story_check_ids_command
            files = [Path(f) for f in args.files] if args.files else None
            return story_check_ids_command(files)

        elif args.story_command == 'backlog-validate':
            from rdm.story_audit.backlog_validate import story_backlog_validate_command
            return story_backlog_validate_command(
                backlog_dir=Path(args.backlog_dir) if args.backlog_dir else None,
                file_path=Path(args.file) if args.file else None,
                strict=args.strict,
                verbose=args.verbose,
                quiet=args.quiet,
            )

        elif args.story_command == 'design-gate':
            from rdm.story_audit.design_gate import story_design_gate_command
            return story_design_gate_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
            )

        elif args.story_command == 'verify':
            from rdm.record.verify import verify_command
            return verify_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
                output=Path(args.output) if args.output else None,
            )

        elif args.story_command == 'release-gate':
            from rdm.story_audit.design_gate import story_release_gate_command
            return story_release_gate_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
                faithfulness_dir=Path(args.faithfulness) if args.faithfulness else None,
            )

        elif args.story_command == 'faithfulness':
            from rdm.story_audit.design_gate import story_faithfulness_command
            return story_faithfulness_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                faithfulness_dir=Path(args.faithfulness) if args.faithfulness else None,
            )

        elif args.story_command == 'trace':
            from rdm.story_audit.design_gate import story_trace_command
            return story_trace_command(
                target=args.target,
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
                faithfulness_dir=Path(args.faithfulness) if args.faithfulness else None,
            )

        elif args.story_command == 'mutation-probe':
            from rdm.story_audit.mutation import story_mutation_probe_command
            return story_mutation_probe_command(
                file=args.file,
                find=args.find,
                replace=args.replace,
                test=args.test,
            )

        elif args.story_command == 'verdict':
            from rdm.story_audit.design_gate import story_verdict_command
            return story_verdict_command(
                target=args.target,
                verdict=args.verdict,
                reviewer=args.reviewer,
                rationale=args.rationale,
                reviewed_tests=args.reviewed_tests,
                uncovered=args.uncovered,
                dhf_dir=Path(args.dhf) if args.dhf else None,
                faithfulness_dir=Path(args.faithfulness) if args.faithfulness else None,
            )

        elif args.story_command == 'persona':
            from rdm.record.persona_cmd import persona_command
            return persona_command(
                vv_plan=Path(args.vv_plan) if args.vv_plan else None,
                persona_results=Path(args.persona_results) if args.persona_results else None,
            )

        elif args.story_command == 'new-input':
            from rdm.story_audit.new_input import story_new_input_command
            return story_new_input_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                context=args.context,
                text=args.text,
                traces_to=args.traces_to,
                test_file=Path(args.test_file) if args.test_file else None,
                list_only=args.list,
            )

        else:
            print(
                "Unknown story subcommand. Use: audit, validate, sync, check-ids, "
                "backlog-validate, design-gate, verify, release-gate, faithfulness, "
                "verdict, mutation-probe, trace, new-input, or persona"
            )
            return 1

    except ImportError as e:
        print(f"Error: Missing dependency for story_audit: {e}")
        print("Install with: pip install rdm[story-audit]")
        return 1


def handle_pm_command(args):
    """Handle the pm (project management) subcommand."""
    try:
        if args.pm_command == 'sync':
            from rdm.project_management.sync import pm_sync_command
            return pm_sync_command(
                repo=args.repo,
                db_path=Path(args.db) if args.db else None,
                pull=args.pull,
                push=args.push,
                status=args.status,
                backlog_dir=Path(args.backlog) if args.backlog else None,
                base_branch=args.branch,
                dhf_dir=Path(args.dhf) if args.dhf else None,
                skip_design_gate=args.skip_design_gate,
            )
        else:
            print("Unknown pm subcommand. Use: sync")
            return 1

    except ImportError as e:
        print(f"Error: Missing dependency: {e}")
        print("Install with: pip install rdm[github] rdm[analytics]")
        return 1


def parse_arguments(arguments):
    parser = argparse.ArgumentParser(prog='rdm')
    parser.add_argument('--version', action='version', version=__version__)
    subparsers = parser.add_subparsers(dest='command', metavar='<command>')

    init_help = 'copy the default templates etc. into the output directory'
    init_parser = subparsers.add_parser('init', help=init_help)
    init_output_help = 'Path where templates are copied'
    init_parser.add_argument('-o', '--output', default='dhf', help=init_output_help)

    render_help = 'render a template using the specified data files'
    render_parser = subparsers.add_parser('render', help=render_help)
    render_parser.add_argument('template')
    render_parser.add_argument('config', help='Path to project `config.yml` file')
    render_parser.add_argument('data_files', nargs='*')

    pull_help = 'pull data from the project management tool'
    pull_parser = subparsers.add_parser('pull', help=pull_help)
    pull_parser.add_argument('config', help='Path to project `config.yml` file')

    gap_help = 'use checklist to verify documents have expected references to particular standard(s)'
    gap_parser = subparsers.add_parser('gap', help=gap_help)
    gap_parser.add_argument('-l', '--list', action='store_true', help='List built-in checklists')
    gap_parser.add_argument('-c', '--coverage', action='store_true', help='Show coverage report')
    gap_parser.add_argument('-v', '--verbose', action='store_true', help='Show missing items')
    gap_parser.add_argument('checklist', nargs='?')
    gap_parser.add_argument('files', nargs='*')

    hooks_help = 'install githooks in current repository'
    hooks_parser = subparsers.add_parser('hooks', help=hooks_help)
    hooks_parser.add_argument('dest', nargs='?', help='Path where hooks are saved')

    collect_help = 'collect documentation snippets into a yaml file'
    collect_parser = subparsers.add_parser('collect', help=collect_help)
    collect_parser.add_argument('files', nargs='*')

    translate_help = 'translate test output to create test result yaml file'
    translate_parser = subparsers.add_parser('translate', help=translate_help)
    translate_parser.add_argument('format', choices=XML_FORMATS)
    translate_parser.add_argument('input')
    translate_parser.add_argument('output')

    # Story audit commands
    story_help = 'requirements traceability and story audit tools'
    story_parser = subparsers.add_parser('story', help=story_help)
    story_subparsers = story_parser.add_subparsers(dest='story_command', metavar='<subcommand>')

    # rdm story audit
    story_audit_help = 'run traceability audit on repository'
    story_audit_parser = story_subparsers.add_parser('audit', help=story_audit_help)
    story_audit_parser.add_argument('repo', nargs='?', default='.', help='Repository path (default: .)')

    # rdm story validate
    story_validate_help = 'validate requirements YAML against schema'
    story_validate_parser = story_subparsers.add_parser('validate', help=story_validate_help)
    story_validate_parser.add_argument('-r', '--requirements', help='Path to requirements directory')
    story_validate_parser.add_argument('-f', '--file', help='Validate single file')
    story_validate_parser.add_argument('-s', '--strict', action='store_true', help='Fail on extra fields')
    story_validate_parser.add_argument('-v', '--verbose', action='store_true', help='Show warnings')
    story_validate_parser.add_argument('-q', '--quiet', action='store_true', help='Only show summary')

    # rdm story sync
    story_sync_help = 'sync Backlog.md to DuckDB for analytics'
    story_sync_parser = story_subparsers.add_parser('sync', help=story_sync_help)
    story_sync_parser.add_argument('backlog_dir', nargs='?', help='Path to Backlog.md directory')
    story_sync_parser.add_argument('-o', '--output', help='Output database path')
    story_sync_parser.add_argument('--migrate-only', action='store_true', help='Only run migrations')

    # rdm story check-ids
    story_check_help = 'check for duplicate story IDs'
    story_check_parser = story_subparsers.add_parser('check-ids', help=story_check_help)
    story_check_parser.add_argument('files', nargs='*', help='Files to check (default: requirements/)')

    # rdm story backlog-validate
    backlog_validate_help = 'validate Backlog.md markdown files for consistency'
    backlog_validate_parser = story_subparsers.add_parser('backlog-validate', help=backlog_validate_help)
    backlog_validate_parser.add_argument('backlog_dir', nargs='?', help='Path to backlog directory')
    backlog_validate_parser.add_argument('-f', '--file', help='Validate single file')
    backlog_validate_parser.add_argument('-s', '--strict', action='store_true', help='Treat warnings as errors')
    backlog_validate_parser.add_argument('-v', '--verbose', action='store_true', help='Show warnings')
    backlog_validate_parser.add_argument('-q', '--quiet', action='store_true', help='Only show summary')

    # rdm story design-gate
    design_gate_help = 'verify design input and design review exist before tasks transition'
    design_gate_parser = story_subparsers.add_parser('design-gate', help=design_gate_help)
    design_gate_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    design_gate_parser.add_argument(
        '--allure-results',
        help='Path to an Allure results directory; reconcile SDD user needs against executed test results',
    )

    # rdm story verify
    story_verify_help = 'generate verification data (SDD user needs x Allure results) for the DHF'
    story_verify_parser = story_subparsers.add_parser('verify', help=story_verify_help)
    story_verify_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    story_verify_parser.add_argument('--allure-results', help='Path to an Allure results directory')
    story_verify_parser.add_argument('-o', '--output', help='Output data file (default: verification.yml)')

    # rdm story release-gate
    release_gate_help = ('block release unless design is approved and every design input is '
                         'verified and faithfully reviewed')
    release_gate_parser = story_subparsers.add_parser('release-gate', help=release_gate_help)
    release_gate_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    release_gate_parser.add_argument('--allure-results', help='Path to an Allure results directory (required)')
    release_gate_parser.add_argument(
        '--faithfulness',
        help='Path to a directory of *-faithfulness.json verdicts (default: <dhf>/faithfulness)',
    )

    # rdm story faithfulness
    faithfulness_help = 'report independent faithfulness review (does each verifying test verify its design input?)'
    faithfulness_parser = story_subparsers.add_parser('faithfulness', help=faithfulness_help)
    faithfulness_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    faithfulness_parser.add_argument(
        '--faithfulness',
        help='Path to a directory of *-faithfulness.json verdicts (default: <dhf>/faithfulness)',
    )

    # rdm story mutation-probe
    mutation_help = 'prove a test catches a defect: apply a one-line mutation, run the test, always revert'
    mutation_parser = story_subparsers.add_parser('mutation-probe', help=mutation_help)
    mutation_parser.add_argument('--file', required=True, help='source file to mutate')
    mutation_parser.add_argument('--find', required=True, help='exact text to replace (must occur once)')
    mutation_parser.add_argument('--replace', required=True, help='replacement text (the mutation)')
    mutation_parser.add_argument('--test', required=True, help='pytest -k selector for the verifying test')

    # rdm story verdict
    verdict_help = 'record an independent faithfulness verdict for a design input (hash-pinned to its test)'
    verdict_parser = story_subparsers.add_parser('verdict', help=verdict_help)
    verdict_parser.add_argument('target', help='the design-input id (DI-…) being reviewed')
    verdict_parser.add_argument('--verdict', required=True,
                                choices=['faithful', 'partial', 'unfaithful', 'weak'])
    verdict_parser.add_argument('--reviewer', required=True,
                                help='who reviewed (must be independent of the test author)')
    verdict_parser.add_argument('--rationale', required=True,
                                help='per-clause reasoning incl. the failing mutation(s)')
    verdict_parser.add_argument('--reviewed-tests', help='comma-separated test names examined')
    verdict_parser.add_argument('--uncovered', help='semicolon-separated requirement clauses NOT covered')
    verdict_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    verdict_parser.add_argument('--faithfulness', help='Verdicts dir (default: <dhf>/faithfulness)')

    # rdm story trace
    trace_help = 'show the traceability slice for a user need or design input (forward + backward)'
    trace_parser = story_subparsers.add_parser('trace', help=trace_help)
    trace_parser.add_argument('target', help='a user-need id (UN-…) or design-input id (DI-…)')
    trace_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    trace_parser.add_argument('--allure-results', help='Allure results dir (adds verification status)')
    trace_parser.add_argument('--faithfulness', help='Faithfulness verdicts dir (adds review status)')

    # rdm story new-input
    new_input_help = 'scaffold a traced design input: frontmatter entry, stub tagged test, checklist'
    new_input_parser = story_subparsers.add_parser('new-input', help=new_input_help)
    new_input_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    new_input_parser.add_argument('--context', help='bounded context that will OWN the input')
    new_input_parser.add_argument('--text', help='the requirement ("RDM shall ..."), in verifiable clauses')
    new_input_parser.add_argument('--traces-to', help='comma-separated user-need id(s) the input refines')
    new_input_parser.add_argument('--test-file',
                                  help='stub test destination (default: tests/acceptance/test_<context>.py)')
    new_input_parser.add_argument('--list', action='store_true',
                                  help='print contexts, taken DI ids, next free id, and user needs')

    # rdm story persona
    persona_help = 'report formative usability evidence from AI-persona simulated-use runs'
    persona_parser = story_subparsers.add_parser('persona', help=persona_help)
    persona_parser.add_argument('--vv-plan', help='Path to the V&V plan (carries the user_needs registry)')
    persona_parser.add_argument('--persona-results', help='Path to a directory of *-persona.json run files')

    # =========================================================================
    # rdm pm (project management)
    # =========================================================================
    pm_help = 'project management commands (GitHub sync)'
    pm_parser = subparsers.add_parser('pm', help=pm_help)
    pm_subparsers = pm_parser.add_subparsers(dest='pm_command', metavar='<subcommand>')

    # rdm pm sync
    pm_sync_help = 'sync GitHub issues/PRs with DuckDB'
    pm_sync_parser = pm_subparsers.add_parser('sync', help=pm_sync_help)
    pm_sync_parser.add_argument('--repo', help='GitHub repo (owner/name)')
    pm_sync_parser.add_argument('--db', help='DuckDB path (default: github_sync.duckdb)')
    pm_sync_parser.add_argument('--pull', action='store_true', help='Pull from GitHub only')
    pm_sync_parser.add_argument('--push', action='store_true', help='Push to GitHub only')
    pm_sync_parser.add_argument('--status', action='store_true', help='Show sync status')
    pm_sync_parser.add_argument('--backlog', help='Backlog directory (default: backlog/)')
    pm_sync_parser.add_argument('--branch', help='Base branch filter for PRs (default: all)')
    pm_sync_parser.add_argument('--dhf', help='DHF directory for the design gate (default: dhf/)')
    pm_sync_parser.add_argument(
        '--skip-design-gate', action='store_true',
        help='Skip the design input/review gate before pushing tasks',
    )

    return parser.parse_args(arguments)


if __name__ == '__main__':
    main()
