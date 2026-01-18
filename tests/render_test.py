import tempfile

import pytest
from jinja2 import TemplateSyntaxError
from pytest import raises

from rdm.render import invert_dependencies, join_to
from tests.util import render_from_string


def test_invert_dependencies_single():
    objects = [
        {'id': 'a', 'dependencies': ['r-1', 'r-2']}
    ]
    actual = invert_dependencies(objects, 'id', 'dependencies')
    expected = [('r-1', {'a'}), ('r-2', {'a'})]
    assert actual == expected


def test_invert_dependencies_multiple():
    objects = [
        {'id': 'a', 'dependencies': ['r-1', 'r-2', 'r-3-2']},
        {'id': 'b', 'dependencies': ['r-1', 'r-2', 'r-3-1']},
    ]
    actual = invert_dependencies(objects, 'id', 'dependencies')
    expected = [
        ('r-1', {'a', 'b'}),
        ('r-2', {'a', 'b'}),
        ('r-3-1', {'b'}),
        ('r-3-2', {'a'}),
    ]
    assert actual == expected


def test_join_to_basic():
    foreign_keys = ['1', '3']
    table = [
        {'id': '1', 'data': 'a'},
        {'id': '2', 'data': 'b'},
    ]
    assert join_to(foreign_keys, table) == [{'id': '1', 'data': 'a'}, None]
    assert join_to(foreign_keys, table, 'data') == [None, None]


def test_render_no_filtering():
    input_string = "apple\nbanana\ncherry\n"
    expected_result = input_string
    actual_result = render_from_string(input_string)
    assert actual_result == expected_result


def test_undefined():
    with raises(TemplateSyntaxError):
        input_string = "{% huhwhat 'hotel', 'california' %}"
        render_from_string(input_string)


class TestDuckDBQuery:
    """Tests for DuckDB query function in templates."""

    def test_query_returns_list_of_dicts(self) -> None:
        """query() function executes SQL and returns list of dicts."""
        duckdb = pytest.importorskip("duckdb")

        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = f"{tmpdir}/test.duckdb"

            conn = duckdb.connect(db_path)
            conn.execute("CREATE TABLE tasks (id TEXT, title TEXT, status TEXT)")
            conn.execute("INSERT INTO tasks VALUES ('t-1', 'Task One', 'Done')")
            conn.execute("INSERT INTO tasks VALUES ('t-2', 'Task Two', 'Open')")
            conn.close()

            template = """{% for task in query("SELECT * FROM tasks ORDER BY id") %}
- {{ task.id }}: {{ task.title }} ({{ task.status }})
{% endfor %}"""

            result = render_from_string(template, config={"duckdb": db_path})

            assert "- t-1: Task One (Done)" in result
            assert "- t-2: Task Two (Open)" in result

    def test_query_with_where_clause(self) -> None:
        """query() function supports WHERE clauses."""
        duckdb = pytest.importorskip("duckdb")

        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = f"{tmpdir}/test.duckdb"

            conn = duckdb.connect(db_path)
            conn.execute("CREATE TABLE items (name TEXT, active BOOLEAN)")
            conn.execute("INSERT INTO items VALUES ('apple', true)")
            conn.execute("INSERT INTO items VALUES ('banana', false)")
            conn.execute("INSERT INTO items VALUES ('cherry', true)")
            conn.close()

            template = "{% for i in query(\"SELECT name FROM items WHERE active = true ORDER BY name\") %}{{ i.name }} {% endfor %}"

            result = render_from_string(template, config={"duckdb": db_path})

            assert result.strip() == "apple cherry"

    def test_query_not_available_without_config(self) -> None:
        """query() function is not available when duckdb not configured."""
        template = "{% for x in query('SELECT 1') %}{{ x }}{% endfor %}"

        with raises(Exception):
            render_from_string(template, config={})

    def test_full_report_template_with_tables_and_aggregations(self) -> None:
        """End-to-end test: render a full markdown report with tables and aggregations."""
        duckdb = pytest.importorskip("duckdb")

        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = f"{tmpdir}/backlog.duckdb"

            conn = duckdb.connect(db_path)
            conn.execute("""
                CREATE TABLE tasks (
                    global_id TEXT,
                    title TEXT,
                    status TEXT,
                    priority TEXT
                )
            """)
            conn.execute("""
                INSERT INTO tasks VALUES
                ('proj-001', 'Setup CI/CD Pipeline', 'Done', 'high'),
                ('proj-002', 'Implement Authentication', 'In Progress', 'high'),
                ('proj-003', 'Add Unit Tests', 'To Do', 'medium'),
                ('proj-004', 'Write Documentation', 'To Do', 'low')
            """)
            conn.execute("""
                CREATE TABLE risks (
                    id TEXT,
                    title TEXT,
                    severity TEXT
                )
            """)
            conn.execute("""
                INSERT INTO risks VALUES
                ('risk-001', 'Data breach via API', 'High'),
                ('risk-002', 'Service downtime', 'Medium')
            """)
            conn.close()

            template = """# Project Report

## Open Tasks

| ID | Title | Status | Priority |
| --- | --- | --- | --- |
{% for t in query("SELECT * FROM tasks WHERE status != 'Done' ORDER BY global_id") -%}
| {{ t.global_id }} | {{ t.title }} | {{ t.status }} | {{ t.priority }} |
{% endfor %}

## Completed

{% for t in query("SELECT global_id, title FROM tasks WHERE status = 'Done'") -%}
- **{{ t.global_id }}**: {{ t.title }}
{% endfor %}

## Risks

| Risk | Severity |
| --- | --- |
{% for r in query("SELECT id, title, severity FROM risks ORDER BY id") -%}
| {{ r.id }}: {{ r.title }} | {{ r.severity }} |
{% endfor %}

## Summary

- Total: {{ query("SELECT COUNT(*) as n FROM tasks")[0].n }}
- Done: {{ query("SELECT COUNT(*) as n FROM tasks WHERE status = 'Done'")[0].n }}
- Open: {{ query("SELECT COUNT(*) as n FROM tasks WHERE status != 'Done'")[0].n }}
"""

            result = render_from_string(template, config={"duckdb": db_path})

            # Verify markdown table structure
            assert "| ID | Title | Status | Priority |" in result
            assert "| proj-002 | Implement Authentication | In Progress | high |" in result
            assert "| proj-003 | Add Unit Tests | To Do | medium |" in result

            # Verify completed section
            assert "- **proj-001**: Setup CI/CD Pipeline" in result

            # Verify risks table
            assert "| risk-001: Data breach via API | High |" in result
            assert "| risk-002: Service downtime | Medium |" in result

            # Verify aggregations
            assert "- Total: 4" in result
            assert "- Done: 1" in result
            assert "- Open: 3" in result

    def test_query_with_joins(self) -> None:
        """query() function supports JOIN operations."""
        duckdb = pytest.importorskip("duckdb")

        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = f"{tmpdir}/test.duckdb"

            conn = duckdb.connect(db_path)
            conn.execute("CREATE TABLE tasks (id TEXT, title TEXT, milestone_id TEXT)")
            conn.execute("CREATE TABLE milestones (id TEXT, name TEXT)")
            conn.execute("INSERT INTO milestones VALUES ('m-1', 'Phase 1'), ('m-2', 'Phase 2')")
            conn.execute("""
                INSERT INTO tasks VALUES
                ('t-1', 'Task A', 'm-1'),
                ('t-2', 'Task B', 'm-1'),
                ('t-3', 'Task C', 'm-2')
            """)
            conn.close()

            template = """{% for row in query("SELECT t.title, m.name as milestone FROM tasks t JOIN milestones m ON t.milestone_id = m.id ORDER BY t.id") %}
{{ row.title }} ({{ row.milestone }})
{%- endfor %}"""

            result = render_from_string(template, config={"duckdb": db_path})

            assert "Task A (Phase 1)" in result
            assert "Task B (Phase 1)" in result
            assert "Task C (Phase 2)" in result
