"""
Bidirectional GitHub sync using DuckDB as intermediate store.

- Pull: GitHub PRs -> DuckDB (for IEC 62304 traceability)
- Push: Backlog.md tasks -> GitHub Issues (for team visibility)
- Milestones: Backlog.md milestones -> GitHub Milestones + Projects v2

Usage:
    rdm pm sync --repo owner/name             # Sync both directions
    rdm pm sync --repo owner/name --pull      # PRs from GitHub only
    rdm pm sync --repo owner/name --push      # Tasks to GitHub only
    rdm pm sync --status                      # Show sync counts
    rdm pm sync --push --backlog ./backlog    # Custom backlog dir

Requires: GH_API_TOKEN env var, pip install rdm[github] rdm[analytics]
"""

from __future__ import annotations

import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    from github import Github, Auth
    from github.GithubException import GithubException
except ImportError:
    Github = None  # type: ignore
    Auth = None  # type: ignore
    GithubException = Exception  # type: ignore

try:
    import duckdb
except ImportError:
    duckdb = None  # type: ignore


# =============================================================================
# DATABASE SCHEMA
# =============================================================================

TABLES_SQL = """
CREATE TABLE IF NOT EXISTS github_prs (
    id BIGINT PRIMARY KEY,
    number INTEGER,
    title VARCHAR,
    body VARCHAR,
    state VARCHAR,
    merged BOOLEAN,
    base_branch VARCHAR,
    head_branch VARCHAR,
    labels VARCHAR[],
    author VARCHAR,
    reviewers VARCHAR[],
    linked_tasks VARCHAR[],
    created_at TIMESTAMP,
    merged_at TIMESTAMP,
    url VARCHAR,
    synced_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS github_issues (
    id BIGINT PRIMARY KEY,
    number INTEGER,
    source_id VARCHAR,
    source_type VARCHAR,
    title VARCHAR,
    body VARCHAR,
    state VARCHAR,
    labels VARCHAR[],
    milestone VARCHAR,
    url VARCHAR,
    synced_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sync_meta (
    key VARCHAR PRIMARY KEY,
    value VARCHAR
);
"""


def init_db(db_path: Path) -> object:
    """Initialize DuckDB with schema."""
    conn = duckdb.connect(str(db_path))
    conn.execute(TABLES_SQL)
    return conn


# =============================================================================
# GITHUB -> DUCKDB (PULL PRS)
# =============================================================================

# Matches explicit [TASK-ID] brackets (most reliable)
BRACKET_TASK_PATTERN = re.compile(r"\[([A-Za-z][\w-]*-\d{2,}(?:\.\d+)?)\]")

# Matches task IDs with zero-padded numbers: RDM-001, ft-042.01, hh-infra-003
# Requires 3+ digits to avoid matching dependency versions like setuptools-80
# Task prefixes are short (1-15 chars) and purely alphabetic with optional hyphens
TASK_ID_PATTERN = re.compile(
    r"\b([A-Za-z][A-Za-z]*(?:-[A-Za-z]+)*-\d{3,}(?:\.\d{2,})?)\b"
)


# Prefixes that look like task IDs but aren't
FALSE_POSITIVE_PREFIXES = {"CVE", "SHA", "MD5", "UTC", "ISO", "RFC"}


def extract_linked_tasks(pr) -> list[str]:
    """Extract Backlog.md task IDs from PR title, body, and branch name.

    Matches:
      - Explicit brackets: [RDM-001], [ft-042.01]
      - Inline references: RDM-001, hh-infra-003 (3+ digit IDs only)
      - Branch names: feature/rdm-003-some-description

    Filters out common false positives like dependency versions and CVEs.
    """
    sources = [pr.title or "", pr.body or "", pr.head.ref or ""]
    task_ids = set()
    for text in sources:
        for match in BRACKET_TASK_PATTERN.finditer(text):
            task_ids.add(match.group(1))
        for match in TASK_ID_PATTERN.finditer(text):
            candidate = match.group(1)
            prefix = candidate.split("-")[0].upper()
            if prefix not in FALSE_POSITIVE_PREFIXES:
                task_ids.add(candidate)
    return sorted(task_ids)


def pull_prs(
    gh_repo: object,
    conn: object,
    base_branch: str | None = None,
    since: datetime | None = None,
) -> int:
    """Pull PRs from GitHub into DuckDB.

    Args:
        gh_repo: PyGithub repository object
        conn: DuckDB connection with sync schema
        base_branch: Filter to PRs targeting this branch (None = all)
        since: Only fetch PRs updated after this timestamp (incremental)

    Returns:
        Number of PRs synced
    """
    now = datetime.now(timezone.utc)
    count = 0

    kwargs = {"state": "all", "sort": "updated", "direction": "desc"}
    if base_branch:
        kwargs["base"] = base_branch

    for pr in gh_repo.get_pulls(**kwargs):
        # Incremental: stop when we reach PRs older than last sync
        if since and pr.updated_at and pr.updated_at < since:
            break

        reviewers = [r.user.login for r in pr.get_reviews() if r.state == "APPROVED"]
        linked_tasks = extract_linked_tasks(pr)

        conn.execute("""
            INSERT OR REPLACE INTO github_prs
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, [
            pr.id, pr.number, pr.title, pr.body or "", pr.state, pr.merged,
            pr.base.ref, pr.head.ref, [lbl.name for lbl in pr.labels],
            pr.user.login, reviewers, linked_tasks,
            pr.created_at, pr.merged_at, pr.html_url, now,
        ])
        count += 1

    return count


# =============================================================================
# GITHUB PROJECTS (GraphQL API)
# =============================================================================


def graphql(token: str, query: str, variables: dict = None) -> dict | None:
    """Execute a GitHub GraphQL query."""
    import urllib.request
    import urllib.error
    import json

    data = json.dumps({"query": query, "variables": variables or {}}).encode()
    req = urllib.request.Request(
        "https://api.github.com/graphql",
        data=data,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        print(f"  GraphQL HTTP {e.code}: {e.reason}")
        return None
    except urllib.error.URLError as e:
        print(f"  GraphQL network error: {e.reason}")
        return None
    except TimeoutError:
        print("  GraphQL request timed out")
        return None


def get_or_create_project(token: str, owner: str, repo: str, epic: dict) -> str | None:
    """Get or create a GitHub Project for an epic."""
    epic_id = epic["epic_id"]
    epic_title = epic.get("title", epic_id)

    # List existing projects
    query = """
    query($owner: String!, $repo: String!) {
        repository(owner: $owner, name: $repo) {
            projectsV2(first: 50) {
                nodes { id title }
            }
        }
    }
    """
    result = graphql(token, query, {"owner": owner, "repo": repo})
    if not result or "errors" in result:
        return None

    projects = result.get("data", {}).get("repository", {}).get("projectsV2", {}).get("nodes", [])
    for p in projects:
        if p["title"] == epic_id:
            return p["id"]

    # Get owner node ID
    owner_query = """
    query($owner: String!, $repo: String!) {
        repository(owner: $owner, name: $repo) {
            owner { id }
        }
    }
    """
    owner_result = graphql(token, owner_query, {"owner": owner, "repo": repo})
    if not owner_result:
        return None
    owner_node_id = owner_result["data"]["repository"]["owner"]["id"]

    # Create project
    create_mutation = """
    mutation($ownerId: ID!, $title: String!) {
        createProjectV2(input: {ownerId: $ownerId, title: $title}) {
            projectV2 { id }
        }
    }
    """
    create_result = graphql(token, create_mutation, {"ownerId": owner_node_id, "title": epic_id})
    if not create_result or "errors" in create_result:
        print(f"  Could not create project: {create_result.get('errors', [])}")
        return None

    project_id = create_result["data"]["createProjectV2"]["projectV2"]["id"]
    print(f"  Created project: {epic_id}")

    # Update project description
    update_mutation = """
    mutation($projectId: ID!, $title: String!, $shortDescription: String) {
        updateProjectV2(input: {projectId: $projectId, title: $title, shortDescription: $shortDescription}) {
            projectV2 { id }
        }
    }
    """
    graphql(token, update_mutation, {
        "projectId": project_id,
        "title": epic_id,
        "shortDescription": epic_title,
    })

    return project_id


def add_issue_to_project(token: str, project_id: str, issue_node_id: str) -> str | None:
    """Add an issue to a GitHub Project. Returns item ID."""
    mutation = """
    mutation($projectId: ID!, $contentId: ID!) {
        addProjectV2ItemById(input: {projectId: $projectId, contentId: $contentId}) {
            item { id }
        }
    }
    """
    result = graphql(token, mutation, {"projectId": project_id, "contentId": issue_node_id})
    if result and "data" in result:
        return result["data"]["addProjectV2ItemById"]["item"]["id"]
    return None


# =============================================================================
# BACKLOG.MD -> GITHUB ISSUES (PUSH TASKS)
# =============================================================================


def _format_acceptance_criteria(criteria) -> str:
    """Format acceptance criteria as compact checkbox list."""
    lines = []
    for ac in criteria:
        check = "x" if ac.completed else " "
        lines.append(f"- [{check}] AC-{ac.number}: {ac.text}")
    return "### Acceptance Criteria\n" + "\n".join(lines)


def build_task_body(task) -> str:
    """Build GitHub issue body from a Backlog.md Task object."""
    parts = []

    if task.description:
        parts.append(task.description)

    if task.business_value:
        parts.append(f"### Business Value\n{task.business_value}")

    if task.acceptance_criteria:
        parts.append(_format_acceptance_criteria(task.acceptance_criteria))

    if task.subtask_ids:
        parts.append("### Subtasks\n" + "\n".join(f"- {sid}" for sid in task.subtask_ids))

    parts.append(f"---\n_Source: {task.id} | Priority: {task.priority}_")

    return "\n\n".join(parts)


def build_subtask_body(subtask, parent_issue_number: int) -> str:
    """Build GitHub issue body from a Backlog.md subtask."""
    parts = []

    if subtask.description:
        parts.append(subtask.description)

    if subtask.acceptance_criteria:
        parts.append(_format_acceptance_criteria(subtask.acceptance_criteria))

    parts.append(
        f"---\n_Parent: #{parent_issue_number} | "
        f"Source: {subtask.id} | Priority: {subtask.priority}_"
    )

    return "\n\n".join(parts)


def task_labels(task) -> list[str]:
    """Build GitHub labels from task metadata."""
    labels = list(task.labels) if task.labels else []
    if task.priority and task.priority != "medium":
        labels.append(f"priority:{task.priority}")
    if task.is_subtask:
        labels.append("subtask")
    else:
        labels.append("task")
    return labels


def gh_state_for_status(status: str) -> str:
    """Map Backlog.md task status to GitHub issue state."""
    closed_statuses = {"Done", "Cancelled"}
    return "closed" if status in closed_statuses else "open"


def push_tasks(
    gh_repo: object,
    conn: object,
    backlog_data: object,
    token: str | None = None,
) -> int:
    """Push Backlog.md tasks as GitHub Issues.

    Args:
        gh_repo: PyGithub repository object
        conn: DuckDB connection with sync schema
        backlog_data: BacklogData from backlog_parser.extract_backlog_data()
        token: GitHub API token for Projects v2 (optional)

    Returns:
        Number of issues created
    """
    count = 0
    owner, repo_name = gh_repo.full_name.split("/")

    # Get or create GitHub milestones from backlog milestones
    gh_milestones = {m.title: m for m in gh_repo.get_milestones(state="all")}
    milestone_map = {}  # backlog milestone id -> GitHub milestone object
    for ms in backlog_data.milestones:
        if ms.id not in gh_milestones and ms.title not in gh_milestones:
            try:
                gh_ms = gh_repo.create_milestone(
                    title=ms.id,
                    description=f"{ms.title}\n\n{ms.description}" if ms.description else ms.title,
                )
                gh_milestones[ms.id] = gh_ms
                print(f"  Milestone: {ms.id} ({ms.title})")
            except GithubException as e:
                print(f"  Milestone {ms.id} failed: {e}")
        milestone_map[ms.id] = gh_milestones.get(ms.id) or gh_milestones.get(ms.title)

    # Get or create GitHub Projects v2 for milestones
    projects = {}
    if token:
        for ms in backlog_data.milestones:
            epic = {"epic_id": ms.id, "title": ms.title}
            project_id = get_or_create_project(token, owner, repo_name, epic)
            if project_id:
                projects[ms.id] = project_id
                print(f"  Project: {ms.id}")

    # Ensure labels exist in the repo
    existing_labels = {lbl.name for lbl in gh_repo.get_labels()}
    all_needed_labels = set()
    for task in backlog_data.tasks:
        all_needed_labels.update(task_labels(task))
    for subtask in backlog_data.subtasks:
        all_needed_labels.update(task_labels(subtask))
    for label_name in all_needed_labels - existing_labels:
        try:
            gh_repo.create_label(name=label_name, color="ededed")
        except GithubException:
            pass

    # Load already-synced issues from DuckDB
    existing = {}
    for row in conn.execute("SELECT source_id, number FROM github_issues").fetchall():
        existing[row[0]] = row[1]

    # Push parent tasks
    task_issue_numbers = {}  # task.id -> GitHub issue number
    for task in backlog_data.tasks:
        if task.id in existing:
            task_issue_numbers[task.id] = existing[task.id]
            continue

        body = build_task_body(task)
        labels = task_labels(task)

        try:
            issue_kwargs = {
                "title": f"[{task.id}] {task.title}",
                "body": body,
                "labels": labels,
            }
            milestone = milestone_map.get(task.milestone) if task.milestone else None
            if milestone:
                issue_kwargs["milestone"] = milestone
            issue = gh_repo.create_issue(**issue_kwargs)

            # Close if task is Done/Cancelled
            if gh_state_for_status(task.status) == "closed":
                issue.edit(state="closed")

            conn.execute("""
                INSERT OR REPLACE INTO github_issues
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, [
                issue.id, issue.number, task.id, "task", issue.title,
                body, gh_state_for_status(task.status), labels,
                task.milestone, issue.html_url, datetime.now(timezone.utc),
            ])

            task_issue_numbers[task.id] = issue.number

            # Add to project if milestone has one
            if task.milestone and task.milestone in projects and token:
                add_issue_to_project(token, projects[task.milestone], issue.node_id)
                print(f"  Created: #{issue.number} {task.id} (added to {task.milestone})")
            else:
                print(f"  Created: #{issue.number} {task.id}")
            count += 1

        except GithubException as e:
            print(f"  Failed {task.id}: {e}")

    # Build task->milestone lookup for subtask project assignment
    task_milestone_map = {task.id: task.milestone for task in backlog_data.tasks}

    # Push subtasks
    for subtask in backlog_data.subtasks:
        if subtask.id in existing:
            continue

        parent_number = task_issue_numbers.get(subtask.parent_task_id)
        if not parent_number:
            print(f"  Skipped {subtask.id}: parent {subtask.parent_task_id} not synced")
            continue

        body = build_subtask_body(subtask, parent_number)
        labels = task_labels(subtask)

        try:
            issue = gh_repo.create_issue(
                title=f"[{subtask.id}] {subtask.title}",
                body=body,
                labels=labels,
            )

            if gh_state_for_status(subtask.status) == "closed":
                issue.edit(state="closed")

            conn.execute("""
                INSERT OR REPLACE INTO github_issues
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, [
                issue.id, issue.number, subtask.id, "subtask", issue.title,
                body, gh_state_for_status(subtask.status), labels,
                None, issue.html_url, datetime.now(timezone.utc),
            ])

            # Add to parent's project
            parent_milestone = task_milestone_map.get(subtask.parent_task_id)
            if parent_milestone and parent_milestone in projects and token:
                add_issue_to_project(token, projects[parent_milestone], issue.node_id)

            print(f"    Created: #{issue.number} {subtask.id} (parent: #{parent_number})")
            count += 1

        except GithubException as e:
            print(f"    Failed {subtask.id}: {e}")

    return count


# =============================================================================
# CLI
# =============================================================================


def _get_last_sync(conn, key: str = "last_sync") -> datetime | None:
    """Get last sync timestamp from DuckDB (timezone-aware UTC)."""
    rows = conn.execute(
        "SELECT value FROM sync_meta WHERE key = ?", [key]
    ).fetchall()
    if rows:
        dt = datetime.fromisoformat(rows[0][0])
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt
    return None


def pm_sync_command(
    repo: str | None = None,
    db_path: Path | None = None,
    pull: bool = False,
    push: bool = False,
    status: bool = False,
    backlog_dir: Path | None = None,
    base_branch: str | None = None,
) -> int:
    """Run pm sync command."""
    if not duckdb:
        print("Error: duckdb required. Install with: pip install rdm[analytics]")
        return 1

    db = db_path or Path("github_sync.duckdb")

    # --status only needs the DB, no GitHub credentials
    if status:
        conn = init_db(db)
        try:
            prs = conn.execute("SELECT COUNT(*) FROM github_prs").fetchone()[0]
            issues = conn.execute("SELECT COUNT(*) FROM github_issues").fetchone()[0]
            last_pull = _get_last_sync(conn, "last_pull")
            last_push = _get_last_sync(conn, "last_push")
            print(f"Database:   {db}")
            print(f"PRs:        {prs}")
            print(f"Issues:     {issues}")
            print(f"Last pull:  {last_pull or 'never'}")
            print(f"Last push:  {last_push or 'never'}")
        finally:
            conn.close()
        return 0

    # Validate all inputs before opening DB or making API calls
    if not Github:
        print("Error: PyGithub required. Install with: pip install rdm[github]")
        return 1

    repo_name = repo or os.getenv("GITHUB_REPOSITORY")
    if not repo_name:
        print("Error: Specify --repo or set GITHUB_REPOSITORY")
        return 1

    token = os.getenv("GH_API_TOKEN")
    if not token:
        print("Error: Set GH_API_TOKEN env var")
        return 1

    do_pull = pull or (not pull and not push)
    do_push = push or (not pull and not push)

    # Validate backlog dir early if pushing
    backlog = backlog_dir or Path("backlog")
    if do_push and not (backlog / "config.yml").exists():
        print(f"Error: No config.yml found in {backlog}")
        print("Run from repo root or use --backlog <path>")
        return 1

    gh = Github(auth=Auth.Token(token))
    gh_repo = gh.get_repo(repo_name)
    print(f"Repository: {repo_name}")
    print(f"Database:   {db}")

    conn = init_db(db)
    try:
        if do_pull:
            since = _get_last_sync(conn, "last_pull")
            mode = f"incremental (since {since})" if since else "full"
            print(f"\nPulling PRs from GitHub ({mode})...")
            pr_count = pull_prs(
                gh_repo, conn,
                base_branch=base_branch,
                since=since,
            )
            print(f"  Synced {pr_count} PRs")
            conn.execute(
                "INSERT OR REPLACE INTO sync_meta VALUES ('last_pull', ?)",
                [datetime.now(timezone.utc).isoformat()],
            )

        if do_push:
            print("\nPushing Backlog.md tasks to GitHub...")
            from rdm.story_audit.backlog_parser import extract_backlog_data
            data = extract_backlog_data(backlog)
            print(f"  Found {len(data.tasks)} tasks, {len(data.subtasks)} subtasks")
            created = push_tasks(gh_repo, conn, data, token=token)
            print(f"  Created {created} issues")
            conn.execute(
                "INSERT OR REPLACE INTO sync_meta VALUES ('last_push', ?)",
                [datetime.now(timezone.utc).isoformat()],
            )
    finally:
        conn.close()

    print("\nDone!")
    return 0


def main() -> None:
    import argparse
    parser = argparse.ArgumentParser(description="Sync Backlog.md with GitHub")
    parser.add_argument("--repo", help="GitHub repo (owner/name)")
    parser.add_argument("--db", type=Path, help="Database path")
    parser.add_argument("--pull", action="store_true", help="Pull PRs from GitHub")
    parser.add_argument("--push", action="store_true", help="Push tasks to GitHub")
    parser.add_argument("--status", action="store_true", help="Show sync status")
    parser.add_argument("--backlog", type=Path, help="Backlog directory (default: backlog/)")
    parser.add_argument("--branch", help="Base branch filter for PRs (default: all)")
    args = parser.parse_args()
    sys.exit(pm_sync_command(
        args.repo, args.db, args.pull, args.push, args.status,
        args.backlog, args.branch,
    ))


if __name__ == "__main__":
    main()
