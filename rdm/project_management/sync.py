"""
Bidirectional GitHub sync using DuckDB as intermediate store.

- PRs: Pull from GitHub
- Features/Stories: Push to GitHub Issues
- Epics: Push to GitHub Milestones

Usage:
    rdm pm sync --repo owner/name
    rdm pm sync --pull    # PRs from GitHub
    rdm pm sync --push    # Features/Stories to GitHub

Requires: GH_API_TOKEN env var, pip install rdm[github] rdm[analytics]
"""

from __future__ import annotations

import os
import sys
from datetime import datetime
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


def pull_prs(gh_repo: object, conn: object) -> int:
    """Pull PRs from GitHub into DuckDB."""
    now = datetime.now()
    count = 0

    for pr in gh_repo.get_pulls(state="all"):
        reviewers = [r.user.login for r in pr.get_reviews() if r.state == "APPROVED"]

        conn.execute("""
            INSERT OR REPLACE INTO github_prs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, [
            pr.id, pr.number, pr.title, pr.body or "", pr.state, pr.merged,
            pr.base.ref, pr.head.ref, [lbl.name for lbl in pr.labels],
            pr.user.login, reviewers, pr.created_at, pr.merged_at, pr.html_url, now,
        ])
        count += 1

    return count


# =============================================================================
# GITHUB PROJECTS
# =============================================================================


def get_or_create_project(gh: object, owner: str, repo: str, epic: dict) -> int | None:
    """Get or create a GitHub Project for an epic. Returns project ID."""
    # GraphQL to list projects
    query = """
    query($owner: String!, $repo: String!) {
        repository(owner: $owner, name: $repo) {
            projectsV2(first: 100) {
                nodes { id title }
            }
        }
    }
    """
    try:
        result = gh._Github__requester.requestJsonAndCheck(
            "POST", "/graphql",
            input={"query": query, "variables": {"owner": owner, "repo": repo}}
        )
        projects = result[1].get("data", {}).get("repository", {}).get("projectsV2", {}).get("nodes", [])

        epic_id = epic["epic_id"]
        for p in projects:
            if p["title"] == epic_id:
                return p["id"]

        # Create new project
        mutation = """
        mutation($owner: ID!, $title: String!) {
            createProjectV2(input: {ownerId: $owner, title: $title}) {
                projectV2 { id }
            }
        }
        """
        # Get owner ID first
        owner_query = """
        query($owner: String!, $repo: String!) {
            repository(owner: $owner, name: $repo) { owner { id } }
        }
        """
        owner_result = gh._Github__requester.requestJsonAndCheck(
            "POST", "/graphql",
            input={"query": owner_query, "variables": {"owner": owner, "repo": repo}}
        )
        owner_id = owner_result[1]["data"]["repository"]["owner"]["id"]

        create_result = gh._Github__requester.requestJsonAndCheck(
            "POST", "/graphql",
            input={"query": mutation, "variables": {"owner": owner_id, "title": epic_id}}
        )
        return create_result[1]["data"]["createProjectV2"]["projectV2"]["id"]

    except Exception as e:
        print(f"  Project API error: {e}")
        return None


def add_issue_to_project(gh: object, project_id: str, issue_node_id: str) -> bool:
    """Add an issue to a GitHub Project."""
    mutation = """
    mutation($project: ID!, $content: ID!) {
        addProjectV2ItemByContentId(input: {projectId: $project, contentId: $content}) {
            item { id }
        }
    }
    """
    try:
        gh._Github__requester.requestJsonAndCheck(
            "POST", "/graphql",
            input={"query": mutation, "variables": {"project": project_id, "content": issue_node_id}}
        )
        return True
    except Exception:
        return False


# =============================================================================
# DUCKDB -> GITHUB (PUSH FEATURES/STORIES)
# =============================================================================


def push_features_as_issues(
    gh_repo: object,
    conn: object,
    data: dict[str, list[dict]],
    gh: object = None,
) -> int:
    """Push features as GitHub issues."""
    count = 0
    owner, repo = gh_repo.full_name.split("/")

    # Get or create projects for epics
    projects = {}
    if gh:
        for epic in data.get("epics", []):
            project_id = get_or_create_project(gh, owner, repo, epic)
            if project_id:
                projects[epic["epic_id"]] = project_id
                print(f"  Project: {epic['epic_id']}")

    # Also create milestones as fallback
    milestones = {m.title: m for m in gh_repo.get_milestones(state="all")}
    for epic in data.get("epics", []):
        if epic["epic_id"] not in milestones:
            try:
                m = gh_repo.create_milestone(title=epic["epic_id"], description=epic.get("title", ""))
                milestones[epic["epic_id"]] = m
            except GithubException:
                pass

    # Check existing issues by source_id
    existing = {}
    for row in conn.execute("SELECT source_id, number FROM github_issues").fetchall():
        existing[row[0]] = row[1]

    for feature in data.get("features", []):
        fid = feature["feature_id"]

        if fid in existing:
            continue  # Already synced

        # Build issue body
        body = f"**{feature.get('title', '')}**\n\n"
        body += feature.get("description", "") or ""
        body += f"\n\n---\n_Source: {fid}_"

        try:
            milestone = milestones.get(feature.get("epic_id"))
            issue = gh_repo.create_issue(
                title=f"[{fid}] {feature.get('title', '')}",
                body=body,
                labels=["feature"] + (feature.get("labels") or []),
                milestone=milestone,
            )

            # Track in DB
            conn.execute("""
                INSERT INTO github_issues VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, [
                issue.id, issue.number, fid, "feature", issue.title,
                body, "open", ["feature"], feature.get("epic_id"),
                issue.html_url, datetime.now(),
            ])

            # Add to project if epic has one
            epic_id = feature.get("epic_id")
            if epic_id and epic_id in projects and gh:
                if add_issue_to_project(gh, projects[epic_id], issue.node_id):
                    print(f"  Created: #{issue.number} {fid} (added to {epic_id})")
                else:
                    print(f"  Created: #{issue.number} {fid}")
            else:
                print(f"  Created: #{issue.number} {fid}")
            count += 1

            # Push user stories as sub-issues
            feature_stories = [s for s in data.get("user_stories", []) if s.get("feature_id") == fid]
            count += push_stories_for_feature(gh_repo, conn, feature, feature_stories, issue.number, gh, projects)

        except GithubException as e:
            print(f"  Failed {fid}: {e}")

    return count


def push_stories_for_feature(
    gh_repo: object,
    conn: object,
    feature: dict,
    stories: list[dict],
    feature_issue_number: int,
    gh: object = None,
    projects: dict = None,
) -> int:
    """Push user stories as issues linked to feature."""
    count = 0
    projects = projects or {}

    existing = {r[0] for r in conn.execute("SELECT source_id FROM github_issues").fetchall()}

    for story in stories:
        sid = story.get("story_id") or story.get("id")
        if not sid or sid in existing:
            continue

        # Build story body
        body = f"**As a** {story.get('role', '?')}\n"
        body += f"**I want** {story.get('goal', '?')}\n"
        body += f"**So that** {story.get('benefit', '?')}\n\n"

        if story.get("acceptance_criteria"):
            body += "### Acceptance Criteria\n"
            for ac in story["acceptance_criteria"]:
                body += f"- [ ] {ac}\n"

        body += f"\n---\n_Parent: #{feature_issue_number} | Source: {sid}_"

        try:
            issue = gh_repo.create_issue(
                title=f"[{sid}] {story.get('goal', '')[:50]}",
                body=body,
                labels=["user-story"],
            )

            conn.execute("""
                INSERT INTO github_issues VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, [
                issue.id, issue.number, sid, "user_story", issue.title,
                body, "open", ["user-story"], None, issue.html_url, datetime.now(),
            ])

            # Add to project
            epic_id = feature.get("epic_id")
            if epic_id and epic_id in projects and gh:
                add_issue_to_project(gh, projects[epic_id], issue.node_id)

            print(f"    Created: #{issue.number} {sid}")
            count += 1

        except GithubException as e:
            print(f"    Failed {sid}: {e}")

    return count


# =============================================================================
# CLI
# =============================================================================


def pm_sync_command(
    repo: str | None = None,
    db_path: Path | None = None,
    pull: bool = False,
    push: bool = False,
    status: bool = False,
) -> int:
    """Run pm sync command."""
    if not duckdb:
        print("Error: duckdb required. Install with: pip install rdm[analytics]")
        return 1

    db = db_path or Path("github_sync.duckdb")
    conn = init_db(db)

    if status:
        prs = conn.execute("SELECT COUNT(*) FROM github_prs").fetchone()[0]
        issues = conn.execute("SELECT COUNT(*) FROM github_issues").fetchone()[0]
        print(f"Database: {db}")
        print(f"PRs:      {prs}")
        print(f"Issues:   {issues}")
        conn.close()
        return 0

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

    gh = Github(auth=Auth.Token(token))
    gh_repo = gh.get_repo(repo_name)
    print(f"Repository: {repo_name}")
    print(f"Database:   {db}")

    do_pull = pull or (not pull and not push)
    do_push = push or (not pull and not push)

    if do_pull:
        print("\nPulling PRs from GitHub...")
        prs = pull_prs(gh_repo, conn)
        print(f"  Synced {prs} PRs")

    if do_push:
        print("\nPushing features/stories to GitHub...")
        # Load from story_audit sync
        try:
            from rdm.story_audit.sync import extract_data
            data = extract_data(Path("requirements"))
            created = push_features_as_issues(gh_repo, conn, data, gh=gh)
            print(f"  Created {created} issues")
        except Exception as e:
            print(f"  Error loading requirements: {e}")
            print("  Run from repo root with requirements/ directory")

    conn.execute("INSERT OR REPLACE INTO sync_meta VALUES ('last_sync', ?)", [datetime.now().isoformat()])
    conn.close()
    print("\nDone!")
    return 0


def main() -> None:
    import argparse
    parser = argparse.ArgumentParser(description="Sync GitHub with DuckDB")
    parser.add_argument("--repo", help="GitHub repo (owner/name)")
    parser.add_argument("--db", type=Path, help="Database path")
    parser.add_argument("--pull", action="store_true", help="Pull PRs from GitHub")
    parser.add_argument("--push", action="store_true", help="Push features to GitHub")
    parser.add_argument("--status", action="store_true", help="Show sync status")
    args = parser.parse_args()
    sys.exit(pm_sync_command(args.repo, args.db, args.pull, args.push, args.status))


if __name__ == "__main__":
    main()
