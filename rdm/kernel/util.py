"""YAML files: the one way every context reads and writes them."""

from collections import OrderedDict

import yaml

# libyaml when installed (about 10x faster), as for frontmatter.
_LOADER = getattr(yaml, "CSafeLoader", yaml.SafeLoader)


class _Dumper(yaml.SafeDumper):
    """Never writes anchors or aliases, and keeps an OrderedDict's order. A
    subclass, so the shared ``yaml.SafeDumper`` stays as it is."""

    def ignore_aliases(self, data):
        return True


_Dumper.add_representer(OrderedDict, lambda dumper, data: dumper.represent_dict(data.items()))


def load_yaml(data_filename):
    with open(data_filename, encoding="utf-8") as data_file:
        try:
            return yaml.load(data_file, Loader=_LOADER)
        except yaml.YAMLError as e:
            raise ValueError('"{}" contains invalid YAML: {}'.format(data_filename, e))


def write_yaml(data, yml_file):
    return yaml.dump(data, yml_file, default_flow_style=False, Dumper=_Dumper)
