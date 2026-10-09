"""Acceptance tests for the architecture context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest

from rdm.architecture import draw as drawing
from rdm.architecture.draw import DrawError, Drawing, RdmC4, draw, sources_of
from rdm.architecture.model import read_model

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402

ROOT = Path(__file__).parents[2]
WORKSPACE = '''workspace "Device" {
  model {
    clinician = person "Clinician"
    device = softwareSystem "Device" {
      ui = container "UI" "Shows alarms" "Web" {
        group "alarms" {
          bell = component "Bell" "Rings" "Python" {
            properties { "code" "device/bell.py" }
          }
        }
      }
    }
    clinician -> bell "silences"
  }
  !include views.dsl
}
'''
VIEWS = '''views {
  systemContext device "C1" { include * }
  component ui "C3_ui" { include * }
  dynamic ui "D_alarm" { clinician -> bell "silences" }
}
'''


def _program() -> str:
    found = shutil.which("rdm-c4") or str(ROOT / "providers" / "c4" / "target" / "release" / "rdm-c4")
    if not Path(found).is_file():
        pytest.skip("rdm-c4 is not built: cargo build --release --manifest-path providers/c4/Cargo.toml")
    return found


@allure.story("DI-84")
@allure.label("component", "Architecture drawing")
def test_the_workspace_is_drawn_through_one_c4_interface(tmp_path: Path, monkeypatch) -> None:
    """DI-84: the architecture workspace is exported and drawn through one c4
    interface that takes the workspace's sources and returns the model and each
    view's drawing; the command line and the component build the same model; a
    view the provider cannot draw is refused, naming the view."""
    provider = RdmC4(_program())
    dhf = tmp_path / "dhf"
    (dhf / "c4").mkdir(parents=True)
    (dhf / "c4" / "workspace.dsl").write_text(WORKSPACE)
    (dhf / "c4" / "views.dsl").write_text(VIEWS)

    with verification_step("the interface takes the workspace's sources, the files it includes among them"):
        sources = sources_of(dhf)
        assert sorted(sources) == ["views.dsl", "workspace.dsl"]
        drawn = provider.draw(sources)
        attach("drawing", {"views": sorted(drawn.views), "model": drawn.model})
        assert isinstance(drawn, Drawing) and sorted(drawn.views) == ["C1", "C3_ui"]
        assert all(svg.lstrip().startswith("<") and "</svg>" in svg for svg in drawn.views.values())

    with verification_step("the model it exports is the one RDM reads: identifiers, groups, code, relationships"):
        (dhf / "c4" / "workspace.json").write_text(json.dumps(drawn.model))
        model = read_model(dhf, root=tmp_path)
        bell = model.elements["bell"]
        assert (bell.kind, bell.parent, bell.context, bell.link) == ("component", "ui", "alarms", "device/bell.py")
        assert [(r.source, r.target, r.label) for r in model.relationships] == [("clinician", "bell", "silences")]
        assert model.views == ["C3_ui", "D_alarm", "C1"]

    with verification_step("the same workspace gives the same model on both sides: RDM's own, "
                           "exported by structurizrx, equals the model in the record"):
        own = provider.draw(sources_of(ROOT / "dhf"))
        again = tmp_path / "own" / "dhf"
        (again / "c4").mkdir(parents=True)
        shutil.copy(ROOT / "dhf" / "c4" / "workspace.dsl", again / "c4" / "workspace.dsl")
        (again / "c4" / "workspace.json").write_text(json.dumps(own.model))
        theirs, ours = read_model(again, root=tmp_path / "own"), read_model(ROOT / "dhf", root=ROOT)
        attach("RDM's own model, both sides", {"elements": len(ours.elements), "relationships": len(ours.relationships),
                                              "views": ours.views})
        assert theirs.elements == ours.elements and theirs.views == ours.views
        def key(r):
            return (r.source, r.target, r.label, r.technology)

        assert sorted(map(key, theirs.relationships)) == sorted(map(key, ours.relationships))

    with verification_step("a view the provider cannot draw is refused by name, and nothing is written"):
        monkeypatch.setattr(drawing, "_providers", lambda: (provider, None))
        with pytest.raises(DrawError, match=r"rdm-c4 cannot draw view\(s\) D_alarm"):
            draw(dhf)
        assert not (dhf / "c4" / "views").exists()

    with verification_step("a second provider draws the views the first cannot, and the record holds every view"):
        class Legacy:
            name = "Structurizr"

            def draw(self, sources, only=None):
                return Drawing(model={}, views={k: "<?xml?>\n<svg>legacy</svg>" for k in only})

        monkeypatch.setattr(drawing, "_providers", lambda: (provider, Legacy()))
        assert draw(dhf) == ["C3_ui", "D_alarm", "C1"]
        assert {p.stem for p in (dhf / "c4" / "views").glob("*.svg")} == {"C1", "C3_ui", "D_alarm"}
        assert "legacy" in (dhf / "c4" / "views" / "D_alarm.svg").read_text()
        assert "legacy" not in (dhf / "c4" / "views" / "C1.svg").read_text()

    with verification_step("a workspace the provider rejects is refused, saying why"):
        (dhf / "c4" / "views.dsl").write_text("views {\n  BROKEN\n}\n")
        with pytest.raises(DrawError, match="rdm-c4 could not export the workspace"):
            draw(dhf)
