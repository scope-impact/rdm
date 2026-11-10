import argparse
import os
import sys
import traceback
from pathlib import Path

import yaml

from rdm.compliance.gaps import audit_for_gaps, coverage_report, list_default_checklists
from rdm.publishing.collect import collect_from_files
from rdm.specification.init import init_command
from rdm.publishing.render import context_from_data_files, render_template_to_file
from rdm.kernel.util import load_yaml
from rdm.kernel.version import __version__


def print_error(message):
    print('\033[31m' + message + '\033[0m', file=sys.stderr)

def main():
    try:
        exit_code = cli(sys.argv[1:])
        sys.stdout.flush()
        sys.exit(exit_code)
    except BrokenPipeError:
        # The reader stopped early (`| head`): end quietly, as other tools do,
        # and keep Python from reporting the same broken pipe again at exit.
        os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
        sys.exit(141)
    except Exception:
        print_error(traceback.format_exc())
        sys.exit(1)


def cli(raw_arguments):
    exit_code = 0
    args = parse_arguments(raw_arguments)
    if args.command is None:
        parse_arguments(['-h'])
    elif args.command == 'render':
        import jinja2

        try:
            context = context_from_data_files(args.data_files)
            config = load_yaml(args.config) or {}  # an empty configuration loads no extensions
            render_template_to_file(config, args.template, context, sys.stdout)
        except (jinja2.UndefinedError, jinja2.TemplateSyntaxError, ValueError, OSError) as error:
            # OSError includes a template that does not exist (TemplateNotFound)
            print_error(f"Error: cannot render {args.template}: {error}")
            exit_code = 2
    elif args.command == 'init':
        exit_code = init_command(args.output)
    elif args.command == 'adopt':
        from rdm.specification.adopt import adopt_command
        exit_code = adopt_command(args.target)
    elif args.command == 'collect':
        try:
            snippets = collect_from_files(args.files)
        except (ValueError, OSError) as error:  # a malformed or repeated snippet, or a file it cannot read
            print_error(f"Error: cannot collect snippets: {error}")
            exit_code = 2
        else:
            yaml.dump(snippets, sys.stdout, default_style='|')
    elif args.command == 'gap' and args.list:
        list_default_checklists()
    elif args.command == 'gap' and args.coverage:
        # In coverage mode, checklist + files can all be checklists or source
        # files: a checklist is a .txt path or a built-in checklist name.
        from rdm.compliance.gaps import builtin_checklists
        builtins = builtin_checklists()
        all_files = ([args.checklist] if args.checklist else []) + args.files
        checklists = [f for f in all_files if f.endswith('.txt') or f in builtins]
        sources = [f for f in all_files if not (f.endswith('.txt') or f in builtins)]
        exit_code = coverage_report(checklists, sources, args.verbose)
    elif args.command == 'gap':
        exit_code = audit_for_gaps(args.checklist, args.files, args.verbose)
    elif args.command == 'story':
        exit_code = handle_story_command(args)
    elif args.command == 'graph':
        exit_code = handle_graph_command(args)
    return exit_code


def handle_graph_command(args):
    """Handle `rdm graph build | query | validate | serve | explorer-file | mcp` (the record as a graph)."""
    from rdm.graph import cli as graph_cli

    def _path(value):
        return Path(value) if value else None

    if args.graph_command == 'build':
        return graph_cli.graph_build_command(
            dhf_dir=_path(args.dhf), allure_results_dir=_path(args.allure_results),
            output=_path(args.output), store=_path(args.store), project_name=args.project,
            checklists=args.checklist,
        )
    if args.graph_command == 'query':
        return graph_cli.graph_query_command(
            args.sparql, store=_path(args.store), dhf_dir=_path(args.dhf),
            allure_results_dir=_path(args.allure_results), fmt=args.format, checklists=args.checklist,
        )
    if args.graph_command == 'serve':
        return graph_cli.graph_serve_command(store=_path(args.store), bind=args.bind)
    if args.graph_command == 'explorer-file':
        return graph_cli.graph_explorer_file_command(
            Path(args.output), store=_path(args.store), dhf_dir=_path(args.dhf),
            allure_results_dir=_path(args.allure_results), checklists=args.checklist,
            endpoint=args.endpoint, exclude=args.exclude,
        )
    if args.graph_command == 'validate':
        try:
            from rdm.graph.validate import validate_command
        except ImportError:
            return graph_cli._missing_extra()
        return validate_command(
            dhf_dir=_path(args.dhf), allure_results_dir=_path(args.allure_results),
            checklists=args.checklist, extra_shapes=[Path(s) for s in args.shapes or []],
        )
    if args.graph_command == 'mcp':
        try:
            from rdm.graph.agent import mcp_command
        except ImportError:
            return graph_cli._missing_extra()
        return mcp_command(dhf_dir=_path(args.dhf), allure_results_dir=_path(args.allure_results),
                           checklists=args.checklist)
    print("Unknown graph subcommand. Use: build, query, serve, validate, explorer-file, or mcp")
    return 1


def handle_story_command(args):
    """Handle the story subcommand and its sub-subcommands."""
    try:
        if args.story_command == 'verify':
            from rdm.release.verify import verify_command
            return verify_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
                output=Path(args.output) if args.output else None,
            )

        elif args.story_command == 'release-gate':
            from rdm.release.gate import story_release_gate_command
            return story_release_gate_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
            )

        elif args.story_command == 'dmr':
            from rdm.publishing.dmr import dmr_command
            return dmr_command(Path(args.documents_dir), Path(args.output))

        elif args.story_command == 'evidence-bundle':
            from rdm.publishing.bundle import evidence_bundle_command
            return evidence_bundle_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
                output=Path(args.output) if args.output else None,
            )

        elif args.story_command == 'evidence-report':
            from rdm.publishing.report import evidence_report_command
            return evidence_report_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
                output=Path(args.output) if args.output else None,
            )

        else:
            print(
                "Unknown story subcommand. Use: verify, release-gate, dmr, "
                "evidence-bundle, or evidence-report"
            )
            return 1

    except ImportError as e:
        print(f"Error: Missing dependency: {e}")
        return 1


def parse_arguments(arguments):
    parser = argparse.ArgumentParser(prog='rdm')
    parser.add_argument('--version', action='version', version=__version__)
    subparsers = parser.add_subparsers(dest='command', metavar='<command>')

    init_help = 'copy the default templates etc. into the output directory'
    init_parser = subparsers.add_parser('init', help=init_help)
    init_output_help = 'Path where templates are copied'
    init_parser.add_argument('-o', '--output', default='dhf', help=init_output_help)

    adopt_help = 'bring an EXISTING repository under RDM (DHF skeleton and CI gates; never overwrites)'
    adopt_parser = subparsers.add_parser('adopt', help=adopt_help)
    adopt_parser.add_argument('target', nargs='?', default='.',
                              help='repository root to adopt into (default: .)')

    render_help = 'render a template using the specified data files'
    render_parser = subparsers.add_parser('render', help=render_help)
    render_parser.add_argument('template')
    render_parser.add_argument('config', help='Path to project `config.yml` file')
    render_parser.add_argument('data_files', nargs='*')

    gap_help = 'use checklist to verify documents have expected references to particular standard(s)'
    gap_parser = subparsers.add_parser('gap', help=gap_help)
    gap_parser.add_argument('-l', '--list', action='store_true', help='List built-in checklists')
    gap_parser.add_argument('-c', '--coverage', action='store_true', help='Show coverage report')
    gap_parser.add_argument('-v', '--verbose', action='store_true', help='Show missing items')
    gap_parser.add_argument('checklist', nargs='?')
    gap_parser.add_argument('files', nargs='*')

    collect_help = 'collect documentation snippets into a yaml file'
    collect_parser = subparsers.add_parser('collect', help=collect_help)
    collect_parser.add_argument('files', nargs='*')

    # Design controls: the gates and traceability over the record
    story_help = 'traceability and audit: release gate, verification data, evidence'
    story_parser = subparsers.add_parser('story', help=story_help)
    story_subparsers = story_parser.add_subparsers(dest='story_command', metavar='<subcommand>')

    # rdm story verify
    story_verify_help = 'generate verification data (SDD user needs x Allure results) for the DHF'
    story_verify_parser = story_subparsers.add_parser('verify', help=story_verify_help)
    story_verify_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    story_verify_parser.add_argument('--allure-results', help='Path to an Allure results directory')
    story_verify_parser.add_argument('-o', '--output', help='Output data file (default: verification.yml)')

    # rdm story release-gate
    release_gate_help = 'block release unless every design input is verified by a passing test'
    release_gate_parser = story_subparsers.add_parser('release-gate', help=release_gate_help)
    release_gate_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    release_gate_parser.add_argument('--allure-results', help='Path to an Allure results directory (required)')

    # rdm story dmr
    dmr_help = 'generate device-master-record index data from controlled documents\' frontmatter'
    dmr_parser = story_subparsers.add_parser('dmr', help=dmr_help)
    dmr_parser.add_argument('documents_dir', help='directory of controlled documents (*.md with frontmatter)')
    dmr_parser.add_argument('-o', '--output', required=True, help='output data file (e.g. data/dmr.yml)')

    # rdm story evidence-bundle
    bundle_help = 'write the retained release evidence set: verification data, matrix, manifest'
    bundle_parser = story_subparsers.add_parser('evidence-bundle', help=bundle_help)
    bundle_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    bundle_parser.add_argument('--allure-results', help='Path to an Allure results directory (required)')
    bundle_parser.add_argument('-o', '--output', help='output directory (default: release-evidence/)')

    # rdm story evidence-report
    report_help = 'render the verification report (PDF): every run behind each design input, with its evidence'
    report_parser = story_subparsers.add_parser('evidence-report', help=report_help)
    report_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    report_parser.add_argument('--allure-results', help='Path to an Allure results directory (required)')
    report_parser.add_argument('-o', '--output', help='output PDF (default: verification_report.pdf)')

    _add_graph_parser(subparsers)


    return parser.parse_args(arguments)


def _add_graph_parser(subparsers):
    """`rdm graph`: the design record projected into RDF (needs extra: graph)."""
    graph_parser = subparsers.add_parser(
        'graph',
        help='the design record as a linked-data (RDF) graph: build, query, validate, serve, explorer-file, mcp')
    graph_sub = graph_parser.add_subparsers(dest='graph_command', metavar='<subcommand>')
    # The record a graph command reads: the same three options on every one.
    record = argparse.ArgumentParser(add_help=False)
    record.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    record.add_argument('--allure-results', help='Allure results dir (adds the test runs)')
    record.add_argument('--checklist', action='append',
                        help='add a checklist: built-in name (rdm gap --list) or a .txt/RDF file; repeatable')

    build = graph_sub.add_parser('build', parents=[record],
                                 help='project the record into RDF (sorted N-Quads and/or an Oxigraph store)')
    build.add_argument('-o', '--output', help='write sorted N-Quads here (default: stdout, unless --store)')
    build.add_argument('--store', help='(re)build an Oxigraph store in this directory, e.g. .rdm/graph')
    build.add_argument('--project', help='project name in instance IRIs (default: the repository name)')

    query = graph_sub.add_parser('query', parents=[record],
                                 help='answer a SPARQL query (SELECT/ASK/CONSTRUCT)')
    query.add_argument('sparql', help='the SPARQL query text')
    query.add_argument('--store', help='query this store (default: a fresh in-memory projection of --dhf)')
    query.add_argument('--format', choices=['tsv', 'csv', 'json'], default='tsv', help='SELECT result format')

    validate = graph_sub.add_parser('validate', parents=[record],
                                    help='check the graph against the SHACL gate shapes (+ your own)')
    validate.add_argument('--shapes', action='append', help='an additional SHACL shapes file; repeatable')

    explorer = graph_sub.add_parser(
        'explorer-file', parents=[record],
        help='write the whole record as an AWS Graph Explorer graph file (Load graph from file)')
    explorer.add_argument('-o', '--output', required=True, help='graph file to write, e.g. rdm.graph.json')
    explorer.add_argument('--store', help='read this store (default: a fresh projection of --dhf)')
    explorer.add_argument('--endpoint', help='SPARQL endpoint Graph Explorer reads details from '
                                             '(default: http://localhost:7878)')
    explorer.add_argument('--exclude', action='append',
                          help='leave out a class and its links, e.g. TestRun; repeatable')

    graph_sub.add_parser(
        'mcp', parents=[record],
        help='serve the record to agents, read-only, as an MCP server over stdio (schema, query, trace, validate)')

    serve = graph_sub.add_parser('serve', help='serve the store as a SPARQL 1.1 endpoint (for AWS Graph Explorer)')
    serve.add_argument('--store', help='store directory (default: .rdm/graph)')
    serve.add_argument('--bind', default='localhost:7878', help='host:port (default: localhost:7878)')


if __name__ == '__main__':
    main()
