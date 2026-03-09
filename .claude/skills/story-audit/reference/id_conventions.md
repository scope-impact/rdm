# Story ID Conventions

Standard conventions for requirements traceability IDs.

---

## Backlog.md Format (Current)

The current standard uses Backlog.md with markdown files and project-specific prefixes.

### Task Prefix (from config.yml)

Each project defines its `task_prefix` in `backlog/config.yml`:

```yaml
# backlog/config.yml
task_prefix: "hh-infra"  # Project-specific prefix
```

**Example prefixes by project:**

| Project | `task_prefix` | Task ID | Subtask ID |
|---------|---------------|---------|------------|
| Infrastructure | `hh-infra` | `hh-infra-003` | `hh-infra-003.01` |
| LLM Framework | `hh-llm` | `hh-llm-005` | `hh-llm-005.02` |
| Studio | `hh-studio` | `hh-studio-001` | `hh-studio-001.03` |
| Wallet | `hh-wallet` | `hh-wallet-002` | `hh-wallet-002.01` |
| Mobile App | `hh-app` | `hh-app-004` | `hh-app-004.02` |

### ID Types

| Type | Format | Example | Location |
|------|--------|---------|----------|
| Milestone | `m-N` | `m-1` | `milestones/*.md` |
| Task | `{task_prefix}-NNN` | `hh-infra-003` | `tasks/*.md` |
| Subtask | `{task_prefix}-NNN.NN` | `hh-infra-003.01` | `tasks/*.md` |
| Risk | `{task_prefix}-risks-NNN` | `hh-infra-risks-003` | `docs/risks/{task_prefix}-risks-NNN - RC-*.md` |
| Decision | `decision-NNN` | `decision-001` | `decisions/decision-NNN - *.md` |
| AC Reference | `{task_prefix}-NNN.NN:#N` | `hh-infra-003.01:#2` | In control refs |

### Hierarchy

```
m-1 (Milestone: Platform Foundation)
├── hh-infra-003 (Task: Compute Infrastructure)
│   ├── hh-infra-003.01 (Subtask: K3s cluster EC2)
│   ├── hh-infra-003.02 (Subtask: EC2 security group)
│   └── hh-infra-003.03 (Subtask: EC2 instance profile)
├── hh-infra-004 (Task: GitOps Flux CD)
│   ├── hh-infra-004.01 (Subtask: Flux bootstrap)
│   └── hh-infra-004.02 (Subtask: Kubeconfig SSM)
└── ...
```

### Risk Clusters

Risk documents use `{task_prefix}-risks-NNN` format in `docs/risks/` with cluster in the filename.
Individual risks are sections within the doc file: `## RISK-{CLUSTER}-NNN: ...`

| Cluster | Example File | Section Format |
|---------|--------------|----------------|
| Availability + Ops | `{task_prefix}-risks-001 - RC-AVAIL-OPS.md` | `## RISK-AVAIL-001:`, `## RISK-OPS-001:` |
| Data | `{task_prefix}-risks-002 - RC-DATA.md` | `## RISK-DATA-001:` |
| IAM | `{task_prefix}-risks-003 - RC-IAM.md` | `## RISK-IAM-001:` |
| Network + Supply | `{task_prefix}-risks-004 - RC-NET-SUPPLY.md` | `## RISK-NET-001:`, `## RISK-SUPPLY-001:` |

### Traceability Flow

```
docs/risks/hh-infra-risks-003 - RC-IAM.md
└── ## RISK-IAM-001: OIDC Trust Boundary Bypass
    ├── Control: Condition key restricts to specific repo
    │   └── refs: hh-infra-001.03:#2
    └── Residual: Low
```

**Chain:** `Risk → Control → Task AC → Test/Code`

### File Structure

```
backlog/
├── config.yml           # Defines task_prefix
├── milestones/
│   └── m-1 - Platform-Foundation.md
├── tasks/
│   ├── hh-infra-003 - Compute-Infrastructure.md
│   └── hh-infra-003.01 - K3s-cluster-EC2.md
├── completed/           # Archived done tasks
├── docs/
│   ├── risks/           # Risk cluster documents
│   │   ├── hh-infra-risks-001 - RC-AVAIL-OPS.md
│   │   ├── hh-infra-risks-002 - RC-DATA.md
│   │   ├── hh-infra-risks-003 - RC-IAM.md
│   │   └── hh-infra-risks-004 - RC-NET-SUPPLY.md
│   ├── sdd/             # Software Design Documents
│   ├── ots/             # Off-the-Shelf components
│   └── api/             # API specifications
├── decisions/
│   └── decision-001 - Public-Subnet-EC2.md
└── design-transfer/     # Deployment runbooks
```

### In Tests (Verification)

```python
@allure.feature("hh-infra-003 Compute Infrastructure")
@allure.story("hh-infra-003.01 K3s cluster EC2")
class TestK3sCluster:
    ...
```

### In Source (Implementation)

```python
@trace("hh-infra-003.01", "K3s cluster provisioning")
def provision_k3s_cluster() -> bool:
    ...
```

### Quick Reference

```bash
# List all task IDs (use your task_prefix)
grep -rhoE "hh-infra-[0-9]+(\.[0-9]+)?" backlog/ | sort -u

# Find risk documents
ls backlog/docs/risks/*.md

# Check where an ID is used
grep -rn "hh-infra-003.01" .
```

---

## Legacy YAML Format (Archived)

> **Note:** This format is archived. New projects should use Backlog.md format above.

### ID Prefixes

| Prefix | Full Name | Purpose | Example |
|--------|-----------|---------|---------|
| `EP-XXX` | Epic | High-level business capability | EP-001: User Authentication |
| `FT-XXX` | Feature | Deliverable functionality | FT-007: Password Reset |
| `US-XXX` | User Story | Individual requirement | US-042: Email validation |
| `RSK-XXX` | Risk | Identified risk | RSK-001: Data breach |
| `RC-XXX` | Risk Control | Mitigation control | RC-001: Encryption at rest |
| `DC-XXX` | Data Contract | Schema/API contracts | DC-001: UserProfile schema |
| `GR-XXX` | Guardrail | Safety/compliance rules | GR-003: PII detection |
| `ADR-XXX` | Architecture Decision | Design decisions | ADR-008: Database choice |

### ID Format

```
[PREFIX]-[NUMBER]
   │        │
   │        └── 3-digit zero-padded number (001-999)
   └── 2-3 letter prefix from table above
```

**Examples:**
- ✅ `US-042` (correct)
- ✅ `FT-007` (correct)
- ❌ `US-42` (wrong: not zero-padded)
- ❌ `USER-042` (wrong: invalid prefix)
- ❌ `us-042` (wrong: lowercase)

### ID Ranges

Organize IDs by phase/module to avoid conflicts:

| Range | Purpose | Phase |
|-------|---------|-------|
| US-001 to US-049 | Foundation/Infrastructure | Foundation |
| US-050 to US-099 | Core data models | Foundation |
| US-100 to US-149 | Phase 1 features | Phase 1 |
| US-150 to US-199 | Phase 2 features | Phase 2 |
| US-200 to US-299 | Phase 3 features | Phase 3 |
| US-300+ | Future/Backlog | Backlog |

### Requirements Hierarchy

```
EP-001 (Epic)
├── FT-001 (Feature)
│   ├── US-001 (Story)
│   ├── US-002 (Story)
│   └── US-003 (Story)
├── FT-002 (Feature)
│   ├── US-004 (Story)
│   └── US-005 (Story)
└── ...
```

### Risk Traceability Chain

```
RSK-001 (Risk)
├── RC-001 (Control)
│   └── implemented_by: [US-001, US-002]
├── RC-002 (Control)
│   └── implemented_by: [US-003]
└── ...
```

**Traceability flow:** `Risk → Control → User Story → Test/Code`

### Registry Location

**Single source of truth:** `requirements/_index.yaml`

```yaml
# requirements/_index.yaml
epics:
  - id: EP-001
    title: "Healthcare Dataset Curation"
    features: [FT-001, FT-002, FT-003]

features:
  - id: FT-001
    title: "Dataset Schema & Validation"
    epic: EP-001
    status: implemented
```

### Creating New IDs

#### Before Creating

1. Check `requirements/_index.yaml` for existing IDs
2. Use next available number in appropriate range
3. Verify no conflicts:
   ```bash
   grep -r "US-042" requirements/
   ```

#### When Creating

1. Add to registry (`_index.yaml`) first
2. Then add to feature file
3. Never reuse deleted IDs

### ID Lifecycle

| State | Action | ID Status |
|-------|--------|-----------|
| Created | Added to registry | Reserved |
| Implemented | Code references it | Active |
| Deprecated | Marked for removal | Deprecated |
| Deleted | Removed from registry | **Never reuse** |

### Traceability Locations (Legacy)

#### In Requirements (Definition)

```yaml
# requirements/features/FT-001-dataset-schema.yaml
stories:
  - id: US-001
    title: "Dataset validation"
    acceptance_criteria:
      - Data must conform to schema
```

#### In Tests (Verification)

```python
# tests/data/test_schema.py
@allure.feature("FT-001 Dataset Schema")
@allure.story("US-001 Dataset Validation")
class TestDatasetValidation:
    ...
```

#### In Source (Implementation)

```python
# src/data/schema.py
from src.traceability import trace

@trace("US-001", "Dataset validation logic")
def validate_dataset(data: dict) -> bool:
    ...
```

#### In Documentation (Reference)

```markdown
<!-- docs/architecture.md -->
The validation system (US-001) ensures...
```

---

## ID Format Migration

### Legacy → Current Mapping

| Legacy | Current | Notes |
|--------|---------|-------|
| `EP-XXX` | `m-N` | Epic → Milestone |
| `FT-XXX` | `{task_prefix}-NNN` | Feature → Task |
| `US-XXX` | `{task_prefix}-NNN.NN` | Story → Subtask |
| `RSK-XXX` | `{task_prefix}-risks-NNN` | Risk doc in `docs/risks/` |
| `RC-XXX` | (inline in risk doc) | Controls in risk markdown |
| `ADR-XXX` | `decision-NNN` | Architecture decisions |

### Migration Notes

- `task_prefix` defined in `backlog/config.yml` (e.g., `hh-infra`, `hh-llm`)
- Subtasks use **dot notation** (`hh-infra-003.01`)
- Risks use `{task_prefix}-risks-NNN` format in `docs/risks/` with **cluster in filename** (`hh-infra-risks-003 - RC-IAM.md`)
- AC references use `#N` format (e.g., `hh-infra-001.03:#2`)
- Registry moved from YAML to **markdown files**

---

## Anti-Patterns

### ❌ Duplicate IDs

```yaml
# FT-001.yaml
- id: US-042  # First definition

# FT-007.yaml
- id: US-042  # CONFLICT! Same ID
```

**Fix:** Rename one to next available ID.

### ❌ ID in Comments Only

```python
# US-042: This handles validation  ❌ Not traceable
def validate():
    ...
```

**Fix:** Use @trace decorator instead.

### ❌ Hardcoded ID References

```python
if story_id == "US-042":  # ❌ Hardcoded
    ...
```

**Fix:** Use constants or configuration.

### ❌ Skipping Numbers

```yaml
- id: US-040
- id: US-041
- id: US-045  # ❌ Skipped 042-044
```

**Fix:** Use sequential numbers or document gaps.

## Validation Rules

1. **Format:** Must match `[A-Z]{2,3}-\d{3}`
2. **Uniqueness:** No duplicate IDs across all files
3. **Registry:** All IDs must be in `_index.yaml`
4. **References:** All references must point to existing IDs
5. **Range:** ID must be in appropriate range for its type

## Quick Reference

```bash
# List all IDs in requirements
grep -rhoE "(FT|US|EP|DC|GR)-[0-9]+" requirements/ | sort -u

# Check for duplicates
grep -rhoE "(FT|US|EP)-[0-9]+" requirements/ | sort | uniq -d

# Find where an ID is used
grep -rn "US-042" .

# Count IDs by prefix
grep -rhoE "(FT|US|EP)-[0-9]+" requirements/ | cut -d- -f1 | sort | uniq -c
```
