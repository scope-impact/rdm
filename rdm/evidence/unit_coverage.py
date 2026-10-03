"""
The unit tests' code coverage, read from one report (DI-76).

Code coverage measures how much of a unit's code its unit tests ran: evidence
of unit verification, never acceptance evidence, so it is never read from or
carried in Allure (Design Review 63). One report is read, Cobertura XML (what
coverage.py, JaCoCo through a converter and Istanbul write) or LCOV (what gcov,
Istanbul and others write), its format told by its content. For each file it
measures, relative to the project root, it gives the lines the tests ran and
the lines it measured. A report that cannot be read is an error, never no
coverage.
"""

from __future__ import annotations

import os
import xml.etree.ElementTree as ET
from pathlib import Path


def _project_path(path: str, root: Path) -> str:
    """A file's path relative to the project ``root`` (as given when outside it)."""
    norm = os.path.normpath(path)
    if os.path.isabs(norm):
        try:
            return Path(norm).resolve().relative_to(Path(root).resolve()).as_posix()
        except ValueError:
            return Path(norm).as_posix()
    return Path(norm).as_posix()


def _hits(value) -> int:
    """A line's hit count (0 when it is not a number)."""
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


def _cobertura(text: str, root: Path, report: Path) -> dict[str, dict[str, int]]:
    try:
        tree = ET.fromstring(text)
    except ET.ParseError as exc:
        raise ValueError(f"{report}: not well-formed Cobertura XML ({exc})") from None
    if tree.tag != "coverage":
        raise ValueError(f"{report}: XML, but not a Cobertura coverage report (root <{tree.tag}>)")
    sources = [s.text.strip() for s in tree.iter("source") if s.text and s.text.strip()]
    root_dir = Path(root)
    lines: dict[str, dict[str, int]] = {}
    for cls in tree.iter("class"):
        name = cls.get("filename")
        if not name:
            continue
        # A class's filename is relative to one of the report's source roots.
        candidates = [_project_path(os.path.join(source, name), root_dir) for source in sources]
        candidates.append(_project_path(name, root_dir))
        path = next((c for c in candidates if (root_dir / c).is_file()), candidates[0])
        file_lines = lines.setdefault(path, {})
        for line in cls.iter("line"):  # a method's lines repeat the class's: counted once by number
            number = line.get("number")
            if number is not None:
                file_lines[number] = max(file_lines.get(number, 0), _hits(line.get("hits")))
    return lines


def _lcov(text: str, root: Path, report: Path) -> dict[str, dict[str, int]]:
    lines: dict[str, dict[str, int]] = {}
    current = None
    for raw in text.splitlines():  # SF:<file>, then DA:<line>,<hits>[,<checksum>], up to end_of_record
        line = raw.strip()
        if line.startswith("SF:"):
            current = lines.setdefault(_project_path(line[3:], root), {})
        elif line.startswith("DA:") and current is not None:
            fields = line[3:].split(",")
            if len(fields) < 2:
                raise ValueError(f"{report}: malformed LCOV line {line!r}")
            current[fields[0]] = max(current.get(fields[0], 0), _hits(fields[1]))
        elif line == "end_of_record":
            current = None
    if not lines:
        raise ValueError(f"{report}: neither a Cobertura XML nor an LCOV coverage report")
    return lines


def read_unit_coverage(report: Path, root: Path) -> dict[str, tuple[int, int]]:
    """Each file the report measures, relative to the project ``root``, with
    its (lines executed, lines measured).

    Raises ValueError, naming the report, when it cannot be read: missing, not
    UTF-8, broken XML, or neither format.
    """
    report = Path(report)
    try:
        text = report.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise ValueError(f"{report}: coverage report not found") from None
    except (OSError, UnicodeDecodeError) as exc:
        raise ValueError(f"{report}: coverage report cannot be read ({exc})") from None
    parse = _cobertura if text.lstrip().startswith("<") else _lcov
    lines = parse(text, Path(root), report)
    return {path: (sum(1 for hits in by_line.values() if hits > 0), len(by_line))
            for path, by_line in sorted(lines.items())}
