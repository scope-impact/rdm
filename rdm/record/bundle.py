"""
Release evidence bundle (DI-30): the retained artifact set for a release.

Writes, to an output directory: the verification data (declared design inputs
reconciled against executed Allure results), the rendered traceability matrix,
a copy of the faithfulness verdicts, and a manifest describing the bundle —
the DHR-shaped set a team attaches to a release tag so the evidence outlives
CI artifact retention.
"""

from __future__ import annotations

import json
import shutil
from datetime import datetime, timezone
from pathlib import Path

from rdm.record.sdd import find_dhf_doc
from rdm.record.verify import write_verification_file


def evidence_bundle(dhf_dir: Path, allure_results_dir: Path, out_dir: Path) -> dict:
    """Produce the bundle; returns the manifest that was written."""
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1. Verification data: design inputs x executed results.
    verification_path = out_dir / "verification.yml"
    data = write_verification_file(dhf_dir, allure_results_dir, verification_path)

    # 2. The rendered traceability matrix (generated, never hand-edited).
    matrix_path = out_dir / "traceability_matrix.md"
    template = find_dhf_doc(dhf_dir, "traceability_matrix.md")
    if template is not None:
        import jinja2
        import yaml

        from rdm.render import render_template_to_file
        from rdm.util import load_yaml

        config_file = dhf_dir / "config.yml"
        config = load_yaml(config_file) if config_file.exists() else {}
        context = {"verification": yaml.safe_load(verification_path.read_text())}
        with matrix_path.open("w", encoding="utf-8") as handle:
            render_template_to_file(config, template.name, context, handle,
                                    loaders=[jinja2.FileSystemLoader(str(template.parent))])

    # 3. The faithfulness verdicts (the §820.30(e) review record).
    verdicts_src = dhf_dir / "faithfulness"
    verdict_files = sorted(verdicts_src.glob("*-faithfulness.json")) if verdicts_src.is_dir() else []
    verdicts_out = out_dir / "faithfulness"
    verdicts_out.mkdir(exist_ok=True)
    for verdict in verdict_files:
        shutil.copy2(verdict, verdicts_out / verdict.name)

    # 4. The manifest describing what this bundle contains.
    summary = data["summary"]
    manifest = {
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "dhf": str(dhf_dir),
        "design_inputs": summary["total"],
        "verified": summary["verified"],
        "failed": summary["failed"],
        "untested": summary["untested"],
        "faithfulness_verdicts": len(verdict_files),
        "files": sorted(
            p.relative_to(out_dir).as_posix() for p in out_dir.rglob("*")
            if p.is_file() and p.name != "manifest.json"
        ),
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest


def evidence_bundle_command(
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
    output: Path | None = None,
) -> int:
    """Run `rdm story evidence-bundle --dhf … --allure-results … -o <dir>`."""
    dhf = Path(dhf_dir or "dhf").resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2
    if not allure_results_dir or not Path(allure_results_dir).exists():
        print("Error: --allure-results <dir> is required (run the acceptance suite first)")
        return 2
    out = Path(output or "release-evidence")
    manifest = evidence_bundle(dhf, Path(allure_results_dir), out)
    print(f"Wrote release evidence bundle to {out}:")
    print(f"  design inputs : {manifest['verified']}/{manifest['design_inputs']} verified "
          f"({manifest['failed']} failed, {manifest['untested']} untested)")
    print(f"  verdicts      : {manifest['faithfulness_verdicts']}")
    print(f"  files         : {len(manifest['files'])} + manifest.json")
    return 0
