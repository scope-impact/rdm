import re
from collections import defaultdict


def _error(line, line_number, filename, message):
    if filename is not None:
        raise ValueError(message + ' on line ' + str(line_number) + ' of ' + filename + ':\n' + line)
    else:
        raise ValueError(message + ':\n' + line)


def collect_from_files(input_filenames):
    snippets = {}
    collected_from = {}
    for filename in input_filenames:
        with open(filename, 'r') as f:
            snippets_in_file = collect_from_lines(f, filename=filename)
        for key in snippets_in_file:
            if key in collected_from:
                raise ValueError('Multiple snippets with the key: "{}" in {} and {}'.format(
                    key, collected_from[key], filename))
            collected_from[key] = filename
        snippets.update(snippets_in_file)
    return snippets


def _marker(line, token):
    """The column of ``token`` as a whole word in ``line`` (never part of a
    longer word, such as PRDOC_LIMIT), or -1."""
    match = re.search(r"(?<![\w]){}(?![\w])".format(re.escape(token)), line)
    return match.start() if match else -1


def collect_from_lines(lines, start_token='RDOC', stop_token='ENDRDOC', filename=None):
    rdoc_key = None
    rdoc_offset = None
    rdocs = defaultdict(list)
    i = 0
    for line in lines:
        i += 1
        if rdoc_key is None:
            start_token_offset = _marker(line, start_token)
            if start_token_offset != -1:
                rdoc_offset = start_token_offset
                rdoc_key = line[rdoc_offset + len(start_token):].strip()
                if rdoc_key == '':
                    raise _error(line, i, filename, 'Empty snippet key on line')
                elif rdoc_key in rdocs:
                    msg = 'Multiple snippets with the key: "{}"'.format(rdoc_key)
                    raise _error(line, i, filename, msg)
        else:
            stop_token_offset = _marker(line, stop_token)
            if stop_token_offset == rdoc_offset:
                rdoc_key = None
                rdoc_offset = None
            elif stop_token_offset != -1:
                msg = "End snippet offset doesn't match the start snippet offset"
                raise _error(line, i, filename, msg)
            else:
                rdocs[rdoc_key].append(line[rdoc_offset:].rstrip())
    if rdoc_key is not None:
        msg = 'Missing a closing snippet token'
        raise _error(line, i, filename, msg)
    return {k: '\n'.join(v) for k, v in rdocs.items()}
