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
                suggest_fixes=args.suggest_fixes,
            )

        elif args.story_command == 'sync':
            from rdm.story_audit.sync import story_sync_command
            return story_sync_command(
                requirements_dir=Path(args.requirements) if args.requirements else None,
                output_path=Path(args.output) if args.output else None,
                repo_name=args.repo,
                validate_only=args.validate_only,
            )

        elif args.story_command == 'check-ids':
            from rdm.story_audit.check_ids import story_check_ids_command
            return story_check_ids_command(
                requirements_dir=Path(args.requirements) if args.requirements else None,
                explain=args.explain,
            )

        elif args.story_command == 'schema':
            from rdm.story_audit.schema_docs import story_schema_command
            return story_schema_command(model=args.model)

        else:
            print("Unknown story subcommand. Use: audit, validate, sync, check-ids, schema")
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
    story_validate_parser.add_argument('--suggest-fixes', action='store_true', help='Show fix suggestions')

    # rdm story sync
    story_sync_help = 'sync requirements to DuckDB for analytics'
    story_sync_parser = story_subparsers.add_parser('sync', help=story_sync_help)
    story_sync_parser.add_argument('-r', '--requirements', help='Path to requirements directory')
    story_sync_parser.add_argument('-o', '--output', help='Output database path')
    story_sync_parser.add_argument('--repo', help='Repository name for cross-repo auditing')
    story_sync_parser.add_argument('--validate-only', action='store_true', help='Only validate, no DB')

    # rdm story check-ids
    story_check_help = 'check for duplicate story IDs'
    story_check_parser = story_subparsers.add_parser('check-ids', help=story_check_help)
    story_check_parser.add_argument('-r', '--requirements', help='Path to requirements directory')
    story_check_parser.add_argument('--explain', action='store_true', help='Show fix guidance')

    # rdm story schema
    story_schema_help = 'show YAML schema documentation'
    story_schema_parser = story_subparsers.add_parser('schema', help=story_schema_help)
    story_schema_parser.add_argument(
        '--model', '-m',
        choices=['Feature', 'Epic', 'UserStory', 'Risk', 'Index', 'All'],
        default='All',
        help='Model to show schema for (default: All)'
    )

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

    return parser.parse_args(arguments)


if __name__ == '__main__':
    main()
