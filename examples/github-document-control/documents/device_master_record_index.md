---
id: DMR-001
revision: 1
title: "Device Master Record — index of the controlled specification set"
---

# Purpose

The device master record (DMR) is the compilation of the procedures and
specifications for the product. In this system it is **the controlled document
set at the approved tip of the default branch**; this index enumerates it. The
DMR for a given release is this index (and the documents it lists) at the
release tag.

# Specification set

Generated from the repository record — do not edit the table by hand.

{% if dmr is defined %}
| Document | Title | Location | Revision |
| --- | --- | --- | --- |
{%- for entry in dmr.entries %}
| {{ entry.id }} | {{ entry.title }} | `{{ entry.path }}` | {{ entry.revision }} |
{%- endfor %}
{% endif %}

# Maintenance

This index is itself a controlled document: it carries identity and revision in
frontmatter, changes only through the approved pull-request path, and is
rendered from `data/dmr.yml` so the table is a record, not a transcription.
