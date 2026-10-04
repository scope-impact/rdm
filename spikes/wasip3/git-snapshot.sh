#!/usr/bin/env bash
# Spike: write the repository's git state, as git_snapshot.py reads it, to $1.
# Run from the repository's root. Needs git only.
if git rev-parse --is-inside-work-tree > /dev/null 2>&1; then
    {
        echo "head $(git rev-parse HEAD 2>/dev/null)"
        echo "origin $(git remote get-url origin 2>/dev/null)"
        git rev-parse -q --verify MERGE_HEAD > /dev/null && echo merging
        git status --porcelain --ignored --untracked-files=all | sed 's/^/S /'
        git ls-files -v | sed 's/^/F /'
    } > "$1"
else
    : > "$1"   # not a work tree: every git question goes unanswered, as natively
fi
