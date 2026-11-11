import collections
import os
import re
from importlib import import_module

from pathlib import Path

import jinja2
from jinja2.environment import TemplateStream

from rdm.publishing.first_pass_output import FirstPassOutput
from rdm.kernel.util import load_yaml
from rdm.md_extensions.base import post_processing_filter_list



def context_from_data_files(data_filenames):
    """The render context: each data file's YAML under its basename."""
    context = {}
    for data_filename in data_filenames:
        key, _ = os.path.splitext(os.path.basename(data_filename))
        if key in context:
            raise ValueError('There is already data attached to the key "{}"'.format(key))
        context[key] = load_yaml(data_filename)
    return context

def load_class(class_descriptor):
    module_name, _, class_name = class_descriptor.rpartition('.')
    return getattr(import_module(module_name), class_name)

def invert_dependencies(objects, id_key, dependencies_key):
    # TODO: add docstring
    inverted = collections.defaultdict(lambda: set())
    for o in objects:
        for d in o[dependencies_key]:
            inverted[d].add(o[id_key])
    inverted_as_list = list(inverted.items())
    return sorted(inverted_as_list, key=lambda i: i[0].split('-'))


def join_to(foreign_keys, table, primary_key='id'):
    '''
    Given a set of ids for an object, and a list of the objects these ids refer
    to, select out the objects by joining using the specified primary key
    (which defaults to 'id'). An id the table lacks is refused (ValueError),
    naming it, rather than rendered as a blank row.
    '''
    joined = []
    for foreign_key in foreign_keys:
        selected_row = next((row for row in table if row.get(primary_key) == foreign_key), None)
        if selected_row is None:
            raise ValueError(f"join_to: no row with {primary_key} {foreign_key!r} in the table")
        joined.append(selected_row)
    return joined


def include(path):
    """A text file's content, its YAML frontmatter (if any) left out, so a
    document can carry another file as written: a feature file of the spec,
    another document. The path is relative to where ``rdm render`` runs (the
    project's root); a file that is missing is refused, naming it."""
    text = Path(path).read_text(encoding="utf-8")
    if text.startswith("---\n"):
        end = text.find("\n---\n", 4)
        if end != -1:
            text = text[end + 5:]
    return text.strip("\n")


_MD_SPECIAL = str.maketrans({c: "\\" + c for c in "\\`*_[]<>$|#"})


def _md(text):
    """Text as Markdown shows it, its special characters escaped."""
    return (text or "").translate(_MD_SPECIAL)


def _table(rows):
    cells = [[_md(c["value"]) for c in row["cells"]] for row in rows]
    if not cells:
        return []
    lines = ["| " + " | ".join(cells[0]) + " |", "|" + "---|" * len(cells[0])]
    return lines + ["| " + " | ".join(r) + " |" for r in cells[1:]]


def gherkin(path):
    """A feature file as a document reads it: parsed with Cucumber's own
    parser and laid out in Markdown, not shown as code. The Feature's
    description is a paragraph, each Rule a bold line, each scenario a bold
    title led by its id with its other tags beneath it in italics, each step a
    line with its keyword in bold, a data table or the Examples of an outline
    a table, a doc string a code block. Comments are left out. Nothing is a
    heading, so the document's own headings and numbering stay its own."""
    from gherkin.parser import Parser
    from gherkin.token_scanner import TokenScanner

    feature = Parser().parse(TokenScanner(Path(path).read_text(encoding="utf-8"))).get("feature") or {}
    out = [f"**{_md(feature.get('keyword', 'Feature'))}: {_md(feature.get('name', ''))}**", ""]
    description = " ".join(line.strip() for line in (feature.get("description") or "").splitlines() if line.strip())
    if description:
        out += [_md(description), ""]

    def steps(block):
        lines = []
        for step in block.get("steps") or []:
            lines.append(f"**{step['keyword'].strip()}** {_md(step['text'])}  ")
            if step.get("dataTable"):
                lines[-1] = lines[-1].rstrip()
                lines += ["", *_table(step["dataTable"]["rows"]), ""]
            if step.get("docString"):
                lines[-1] = lines[-1].rstrip()
                lines += ["", "```", step["docString"]["content"], "```", ""]
        if lines:
            lines[-1] = lines[-1].rstrip()
        return lines

    def scenario(node):
        tags = [t["name"] for t in node.get("tags") or []]
        ids = [t for t in tags if re.fullmatch(r"@[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*-\d+", t)]
        stamp = f"`{ids[-1][1:]}` " if ids else ""
        lines = [f"{stamp}**{_md(node['name'])}**", ""]
        others = [t for t in tags if t not in ids[-1:]]
        if others:
            lines += ["*" + " ".join(_md(t) for t in others) + "*", ""]
        lines += steps(node) + [""]
        for examples in node.get("examples") or []:
            title = f"{examples.get('keyword', 'Examples')}: {examples.get('name', '')}".rstrip(": ")
            header = [examples["tableHeader"]] if examples.get("tableHeader") else []
            rows = header + (examples.get("tableBody") or [])
            lines += [f"*{_md(title)}*", "", *_table(rows), ""]
        return lines

    for child in feature.get("children") or []:
        if "background" in child:
            out += [f"*{_md(child['background'].get('keyword', 'Background'))}*", "", *steps(child["background"]), ""]
        elif "scenario" in child:
            out += scenario(child["scenario"])
        elif "rule" in child:
            out += ["---", "", f"**{_md(child['rule']['keyword'])}: {_md(child['rule']['name'])}**", ""]
            for grandchild in child["rule"].get("children") or []:
                if "scenario" in grandchild:
                    out += scenario(grandchild["scenario"])
    return "\n".join(out).rstrip() + "\n"


def glob(pattern):
    """The paths matching ``pattern``, sorted, relative to where ``rdm render``
    runs: every feature file of a spec, say, to ``include`` one by one."""
    return sorted(str(path) for path in Path(".").glob(pattern))


def md_indent(snippet, header_shift=0):
    lines = snippet.split('\n')
    in_code = False
    for j, line in enumerate(lines):
        if line.startswith('```'):
            in_code = not in_code
        if in_code:
            continue
        if header_shift < 0:
            if line.startswith('#'):
                if line.startswith('#' * (abs(header_shift) + 1)):
                    lines[j] = line[abs(header_shift):]
                else:
                    raise ValueError("Can't remove headings from snippet")
        else:
            if line.startswith('#'):
                lines[j] = '#' * header_shift + line
    processed_snippet = "\n".join(lines)
    return processed_snippet


def render_template_to_file(config, template_filename, context, output_file, loaders=None):
    generator = generate_template_output(config, template_filename, context, loaders=loaders)
    TemplateStream(generator).dump(output_file)


def render_template_to_string(config, template_filename, context, loaders=None):
    return ''.join(generate_template_output(config, template_filename, context, loaders=loaders))


def generate_template_output(config, template_filename, context, loaders=None):
    environment = _create_jinja_environment(config, loaders)
    first_pass_output = FirstPassOutput()
    environment.globals['first_pass_output'] = first_pass_output
    output_line_list = generate_template_output_lines(environment, template_filename, context)
    if first_pass_output.second_pass_is_requested:
        jinja2.clear_caches()
        first_pass_output_filled = FirstPassOutput(output_line_list)
        second_pass_environment = _create_jinja_environment(config, loaders)
        second_pass_environment.globals['first_pass_output'] = first_pass_output_filled
        output_line_list = generate_template_output_lines(second_pass_environment, template_filename, context)
    return iter(output_line_list)


def generate_template_output_lines(environment, template_filename, context):
    template = environment.get_template(template_filename)
    source_line_list = _generate_source_line_list(template, context)
    return list(_generate_output_lines(environment, source_line_list))


def _create_jinja_environment(config, loaders=None):
    extensions = [load_class(ed) for ed in config.get('md_extensions', [])]
    loader = _create_loader(loaders)
    environment = jinja2.Environment(
        cache_size=0,
        undefined=jinja2.StrictUndefined,
        loader=loader,
        extensions=extensions,
    )
    environment.filters['invert_dependencies'] = invert_dependencies
    environment.filters['join_to'] = join_to
    environment.filters['md_indent'] = md_indent
    environment.filters['include'] = include
    environment.filters['glob'] = glob
    environment.filters['gherkin'] = gherkin

    return environment


def _create_loader(loaders=None):
    if loaders is None:
        loaders = [
            jinja2.FileSystemLoader('.'),
        ]

    return jinja2.ChoiceLoader(loaders)


def _generate_source_line_list(template, context):
    generator = template.generate(**context)
    source = ''.join(generator)
    # template generator usually loses trailing new line.
    if source and source[-1] != '\n':
        source += '\n'
    return source.splitlines(keepends=True)


def _generate_output_lines(environment, source_line_list):
    output_generator = iter(source_line_list)
    post_process_filters = post_processing_filter_list(environment)
    for post_process_filter in post_process_filters:
        output_generator = post_process_filter(output_generator)
    return output_generator
