# The architecture of git/GitHub document control: the C4 model and its views.
#
# This file is the source. `rdm c4 draw` exports the model to workspace.json
# and draws each view to views/<view>.svg, stamped with this file's hash. Each
# component names what implements it with a "code" property; each bounded
# context is a group of components.
workspace "Document control" "git keeps the record; GitHub provides the service." {
  !identifiers flat
  model {
    author = person "Author" "Writes and revises the controlled documents"
    reviewer = person "Quality reviewer" "A code owner: approves each change to a controlled document"
    releaser = person "Releaser" "Tags a release of the approved document set"
    dc_system = softwareSystem "Document control system" "The repository and its GitHub configuration: the record and what a change must pass" {
      repo = container "Controlled record" "The git repository: the documents, their data and the repository's own configuration" "git" "Database" {
        group "approval" {
          ruleset = component "Branch ruleset" "What a change must pass to reach the default branch: reviews, signed commits, required checks, linear history" "GitHub ruleset (JSON)" {
            properties {
              "code" ".github/rulesets/controlled-documents.json"
            }
            tags "context:approval"
          }
          code_owners = component "Code owners" "Routes every controlled path to the quality team for review" "CODEOWNERS" {
            properties {
              "code" ".github/CODEOWNERS"
            }
            tags "context:approval"
          }
          merge_settings = component "Merge settings" "Only merge commits; branches deleted on merge" "GitHub settings (JSON)" {
            properties {
              "code" ".github/settings.json"
            }
            tags "context:approval"
          }
          settings_applier = component "Settings applier" "Applies the ruleset and merge settings, and checks them for drift" "shell, gh" {
            properties {
              "code" "setup.sh"
            }
            tags "context:approval"
          }
        }
        group "records" {
          sop = component "Document control procedure" "SOP-DC-001: identity, revision history, the Part 11 controls it cites" "Markdown, Jinja2" {
            properties {
              "code" "dhf/documents/procedures/document_control_procedure.md"
            }
            tags "context:records"
          }
          dmr_index = component "Device master record index" "The current approved specification set, from generated index data" "Markdown, Jinja2" {
            properties {
              "code" "dhf/documents/procedures/device_master_record_index.md"
            }
            tags "context:records"
          }
          record_data = component "Record data" "The revision history and the index data the documents embed" "YAML" {
            properties {
              "code" "dhf/data/"
            }
            tags "context:records"
          }
          part11 = component "Part 11 checklist" "The Part 11 controls a controlled document must reference" "text" {
            properties {
              "code" "checklists/part11_document_control.txt"
            }
            tags "context:records"
          }
          rendering = component "Rendering" "Renders the documents from their templates and data to PDF" "make, Pandoc, Typst" {
            properties {
              "code" "dhf/Makefile"
            }
            tags "context:records"
          }
        }
      }
      workflows = container "Workflows" "What GitHub runs for the repository" "GitHub Actions" {
        group "approval" {
          design_controls = component "Design-controls workflow" "Runs RDM's gates and the record checks on every pull request: the required checks" "GitHub Actions" {
            properties {
              "code" ".github/workflows/design-controls.yml"
            }
            tags "context:approval"
          }
          drift_audit = component "Drift audit" "Checks the live repository settings against the declared ones, daily" "GitHub Actions" {
            properties {
              "code" ".github/workflows/drift-audit.yml"
            }
            tags "context:approval"
          }
        }
        group "release" {
          release_workflow = component "Release workflow" "On a tag: verifies the release, renders the copies, writes the device history record, publishes the release" "GitHub Actions" {
            properties {
              "code" ".github/workflows/release-documents.yml"
            }
            tags "context:release"
          }
        }
      }
    }
    forge = softwareSystem "GitHub" "Pull requests, rulesets, Actions and releases" "External"
    rdm = softwareSystem "RDM" "The design-control gates, gap analysis and rendering, pinned to a release" "External"
    author -> sop "writes and revises"
    author -> forge "opens pull requests on"
    reviewer -> forge "approves pull requests on"
    releaser -> forge "tags a release on"
    forge -> ruleset "enforces"
    forge -> design_controls "runs on every pull request"
    forge -> drift_audit "runs daily"
    forge -> release_workflow "runs on a release tag"
    ruleset -> code_owners "requires review from"
    ruleset -> design_controls "requires the checks of"
    settings_applier -> ruleset "applies and drift-checks"
    settings_applier -> merge_settings "applies and drift-checks"
    settings_applier -> forge "configures the repository on" "gh api"
    drift_audit -> settings_applier "checks for drift with"
    design_controls -> rdm "runs the design gate, verify and the release gate of"
    design_controls -> part11 "holds the documents to"
    sop -> record_data "embeds the revision history from"
    dmr_index -> record_data "lists the documents from"
    rendering -> sop "renders"
    rendering -> dmr_index "renders"
    rendering -> rdm "renders the templates with"
    release_workflow -> design_controls "verifies the release with the same gates as"
    release_workflow -> rendering "renders the copies with"
    release_workflow -> rdm "writes the evidence bundle with"
    release_workflow -> forge "publishes the release, copies and device history record to"
  }
  views {
    systemContext dc_system "C1" {
      title "Document control: system context (C1)"
      include *
      include reviewer releaser
      autolayout tb
    }
    container dc_system "C2" {
      title "Document control: containers (C2)"
      include *
      autolayout tb
    }
    component repo "C3_approval" {
      title "Approval: components (C3)"
      include ruleset code_owners merge_settings settings_applier design_controls drift_audit forge rdm part11
      autolayout tb
    }
    component repo "C3_records" {
      title "Records: components (C3)"
      include sop dmr_index record_data part11 rendering author rdm design_controls release_workflow
      autolayout tb
    }
    component repo "C3_release" {
      title "Release: components (C3)"
      include release_workflow rendering design_controls rdm forge
      autolayout tb
    }
    styles {
      element "Person" {
        shape Person
        background #08427b
        color #ffffff
      }
      element "Software System" {
        background #1168bd
        color #ffffff
      }
      element "Container" {
        background #438dd5
        color #ffffff
      }
      element "Component" {
        background #85bbf0
        color #000000
      }
      element "External" {
        background #999999
        color #ffffff
      }
      element "Database" {
        shape Cylinder
      }
    }
  }
}
