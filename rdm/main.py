import argparse
import os
import sys
import traceback
from pathlib import Path

import yaml

from rdm.compliance.gaps import audit_for_gaps, coverage_report, list_default_checklists
from rdm.publishing.collect import collect_from_files
from rdm.specification.hooks import install_hooks
from rdm.specification.init import init_command
from rdm.publishing.render import context_from_data_files, render_template_to_file
from rdm.evidence.translate import translate_test_results, XML_FORMATS
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
    elif args.command == 'hooks':
        install_hooks(args.dest, with_issue_hooks=args.with_issue_hooks)
    elif args.command == 'collect':
        try:
            snippets = collect_from_files(args.files)
        except (ValueError, OSError) as error:  # a malformed or repeated snippet, or a file it cannot read
            print_error(f"Error: cannot collect snippets: {error}")
            exit_code = 2
        else:
            yaml.dump(snippets, sys.stdout, default_style='|')
    elif args.command == 'translate':
        from xml.etree.ElementTree import ParseError

        try:
            translate_test_results(args.format, args.input, args.output)
        except (ValueError, ParseError, OSError) as error:
            print_error(f"Error: cannot translate {args.input}: {error}")
            exit_code = 2
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
    elif args.command == 'c4' and args.c4_command == 'draw':
        from rdm.architecture.draw import draw_command
        exit_code = draw_command(args)
    elif args.command == 'c4':
        parse_arguments(['c4', '-h'])
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
            checklists=args.checklist, infer=args.infer,
        )
    if args.graph_command == 'query':
        return graph_cli.graph_query_command(
            args.sparql, store=_path(args.store), dhf_dir=_path(args.dhf),
            allure_results_dir=_path(args.allure_results), fmt=args.format, checklists=args.checklist,
            infer=args.infer,
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
        if args.story_command == 'design-gate':
            from rdm.specification.design_gate import story_design_gate_command
            results = Path(args.allure_results) if args.allure_results else None
            warnings = None
            if results is not None and results.exists():  # release reads results; the design gate shows its warnings
                from rdm.release.gate import verification_warnings

                def warnings(dhf):
                    return verification_warnings(dhf, results)
            return story_design_gate_command(dhf_dir=Path(args.dhf) if args.dhf else None,
                                             verification_warnings=warnings)

        elif args.story_command == 'verify':
            from rdm.release.verify import verify_command
            return verify_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
                output=Path(args.output) if args.output else None,
                unit_coverage_report=Path(args.unit_coverage) if args.unit_coverage else None,
            )

        elif args.story_command == 'release-gate':
            from rdm.release.gate import story_release_gate_command
            return story_release_gate_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
            )

        elif args.story_command == 'mutation-probe':
            from rdm.evidence.mutation import story_mutation_probe_command
            return story_mutation_probe_command(
                file=args.file,
                find=args.find,
                replace=args.replace,
                test=args.test,
            )

        elif args.story_command == 'trace':
            from rdm.release.gate import story_trace_command
            return story_trace_command(
                target=args.target,
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
            )

        elif args.story_command == 'persona':
            from rdm.specification.persona_cmd import persona_command
            return persona_command(
                vv_plan=Path(args.vv_plan) if args.vv_plan else None,
                persona_results=Path(args.persona_results) if args.persona_results else None,
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
                unit_coverage_report=Path(args.unit_coverage) if args.unit_coverage else None,
            )

        elif args.story_command == 'evidence-report':
            from rdm.publishing.report import evidence_report_command
            return evidence_report_command(
                dhf_dir=Path(args.dhf) if args.dhf else None,
                allure_results_dir=Path(args.allure_results) if args.allure_results else None,
                output=Path(args.output) if args.output else None,
            )

        elif args.story_command == 'new-input':
            from rdm.specification.new_input import story_new_input_command
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
                "Unknown story subcommand. Use: design-gate, verify, release-gate, trace, "
                "mutation-probe, new-input, dmr, evidence-bundle, evidence-report, or persona"
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

    adopt_help = 'bring an EXISTING repository under record-first design controls (never overwrites)'
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

    hooks_help = 'install githooks in current repository'
    hooks_parser = subparsers.add_parser('hooks', help=hooks_help)
    hooks_parser.add_argument('dest', nargs='?', help='Path where hooks are saved')
    hooks_parser.add_argument('--with-issue-hooks', action='store_true',
                              help='also install the commit-msg/prepare-commit-msg issue-reference hooks')

    collect_help = 'collect documentation snippets into a yaml file'
    collect_parser = subparsers.add_parser('collect', help=collect_help)
    collect_parser.add_argument('files', nargs='*')

    translate_help = 'translate test output to create test result yaml file'
    translate_parser = subparsers.add_parser('translate', help=translate_help)
    translate_parser.add_argument('format', choices=XML_FORMATS)
    translate_parser.add_argument('input')
    translate_parser.add_argument('output')

    # Design controls: the gates and traceability over the record
    story_help = 'design controls: design and release gates, traceability, design-input scaffolding'
    story_parser = subparsers.add_parser('story', help=story_help)
    story_subparsers = story_parser.add_subparsers(dest='story_command', metavar='<subcommand>')

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
    story_verify_parser.add_argument('--unit-coverage', metavar='REPORT',
                                     help="the unit tests' code coverage, one Cobertura XML or LCOV report: "
                                          "added per C4 component as unit-test evidence (never acceptance evidence)")

    # rdm story release-gate
    release_gate_help = ('block release unless design is approved and every design input is '
                         'verified by a passing test')
    release_gate_parser = story_subparsers.add_parser('release-gate', help=release_gate_help)
    release_gate_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    release_gate_parser.add_argument('--allure-results', help='Path to an Allure results directory (required)')

    # rdm story mutation-probe
    mutation_help = 'reviewer tool: prove a test catches a defect (apply a one-line mutation, run it, always revert)'
    mutation_parser = story_subparsers.add_parser('mutation-probe', help=mutation_help)
    mutation_parser.add_argument('--file', required=True, help='source file to mutate')
    mutation_parser.add_argument('--find', required=True, help='exact text to replace (must occur once)')
    mutation_parser.add_argument('--replace', required=True, help='replacement text (the mutation)')
    mutation_parser.add_argument('--test', required=True, help='pytest -k selector for the verifying test')

    # rdm story trace
    trace_help = 'show the traceability slice for a user need or design input (forward + backward)'
    trace_parser = story_subparsers.add_parser('trace', help=trace_help)
    trace_parser.add_argument('target', help='a user-need id (UN-…) or design-input id (DI-…)')
    trace_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    trace_parser.add_argument('--allure-results', help='Allure results dir (adds verification status)')

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
    bundle_parser.add_argument('--unit-coverage', help="the unit tests' code coverage report (Cobertura XML or LCOV), "
                               'carried and kept as verify takes it')

    # rdm story evidence-report
    report_help = 'render the verification report (PDF): every run behind each design input, with its evidence'
    report_parser = story_subparsers.add_parser('evidence-report', help=report_help)
    report_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    report_parser.add_argument('--allure-results', help='Path to an Allure results directory (required)')
    report_parser.add_argument('-o', '--output', help='output PDF (default: verification_report.pdf)')

    # rdm story new-input
    new_input_help = 'scaffold a traced design input: frontmatter entry, stub tagged test, checklist'
    new_input_parser = story_subparsers.add_parser('new-input', help=new_input_help)
    new_input_parser.add_argument('--dhf', help='Path to DHF directory (default: dhf/)')
    new_input_parser.add_argument('--context', help='bounded context that will OWN the input')
    new_input_parser.add_argument('--text', help='the requirement ("RDM shall ..."), each thing it requires verifiable')
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

    _add_graph_parser(subparsers)

    # rdm c4: the architecture workspace (DI-66, DI-70)
    c4_parser = subparsers.add_parser('c4', help='the architecture workspace: draw its model and views')
    c4_sub = c4_parser.add_subparsers(dest='c4_command', metavar='<subcommand>')
    c4_draw = c4_sub.add_parser('draw', help="export the workspace's model and draw each view, stamped "
                                             "(needs Java, Structurizr's CLI and Graphviz)")
    c4_draw.add_argument('--dhf', default='dhf', help='Path to DHF directory (default: dhf/)')

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
    build.add_argument('--infer', action='store_true',
                       help="add what the vocabulary's rules derive, in a separate inferred graph")

    query = graph_sub.add_parser('query', parents=[record], help='answer a SPARQL query (SELECT/ASK/CONSTRUCT)')
    query.add_argument('sparql', help='the SPARQL query text')
    query.add_argument('--store', help='query this store (default: a fresh in-memory projection of --dhf)')
    query.add_argument('--format', choices=['tsv', 'csv', 'json'], default='tsv', help='SELECT result format')
    query.add_argument('--infer', action='store_true',
                       help="include what the vocabulary's rules derive (in-memory projection only)")

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
