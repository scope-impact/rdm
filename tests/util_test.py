import io
from collections import OrderedDict

from rdm.kernel.util import write_yaml


def test_write_yaml():
    string_out = io.StringIO()
    data = OrderedDict([
        ('one', 1),
        ('two', 2),
    ])
    write_yaml(data, string_out)
    assert string_out.getvalue() == 'one: 1\ntwo: 2\n'
