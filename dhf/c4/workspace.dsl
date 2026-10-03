# RDM's architecture: the C4 model and its views (DI-66, DI-70).
#
# This file is the source. `rdm c4 draw` exports the model to workspace.json
# and draws each view to views/<view>.svg; both are stamped with this file's
# hash, and the design gate fails when they are stale. Each component names its
# code with a "code" property; each bounded context is a group of components
# (the target contexts of Design Review 30, over today's code paths).
workspace "RDM" "The design record of regulated software." {
  !identifiers flat
  model {
    author = person "Regulatory author" "Writes the record; renders and checks the regulatory documents"
    engineer = person "Engineer" "Changes the product and its tagged acceptance tests"
    reviewer = person "Reviewer" "Approves each change by reviewing its pull request"
    rdm_system = softwareSystem "RDM" "Keeps the design record of regulated software, gates it, renders it and builds it into a read-only graph" {
      rdm_cli = container "rdm" "The command line: gates, rendering, graph build, query and validate, and the agent server" "Python" {
        group "specification" {
          kernel = component "Shared kernel" "Shared helpers every context may use: YAML and files, ids, git, frontmatter, the reconcile helpers" "Python" {
            properties {
              "code" "rdm/kernel/"
            }
            tags "context:specification"
          }
          record_reader = component "Record reader" "User needs, design inputs, realises and declarations, from the design documents' and V&V plan's frontmatter" "Python" {
            properties {
              "code" "rdm/specification/sdd.py"
            }
            tags "context:specification"
          }
          test_tags = component "Test tags" "The design-input tags in test sources, in every language RDM reads" "Python" {
            properties {
              "code" "rdm/specification/tags.py"
            }
            tags "context:specification"
          }
          design_gate = component "Design gate" "The record complete, approved and declared once; the architecture views fresh" "Python" {
            properties {
              "code" "rdm/specification/design_gate.py"
            }
            tags "context:specification"
          }
          hooks = component "Hooks installer" "rdm hooks: installs the design-gate hooks (pre-commit, and pre-merge-commit for merges); the issue-reference hooks only on request" "Python" {
            properties {
              "code" "rdm/specification/hooks.py"
            }
            tags "context:specification"
          }
          precommit_hook = component "Pre-commit hook" "Runs the design gate before a commit, or a merge, that stages implementation files; blocks when the gate cannot run" "shell" {
            properties {
              "code" "rdm/specification/hook_files/"
            }
            tags "context:specification"
          }
          new_input = component "New design input" "rdm story new-input: allocates the next id, inserts it into a context's frontmatter, writes a failing stub test" "Python" {
            properties {
              "code" "rdm/specification/new_input.py"
            }
            tags "context:specification"
          }
          project_scaffold = component "Project scaffold" "rdm init: lays down the project templates and the shared agent workflow runbook" "Python" {
            properties {
              "code" "rdm/specification/init.py"
            }
            tags "context:specification"
          }
          project_templates = component "Project templates" "What rdm init lays down" "Markdown, YAML, Typst" {
            properties {
              "code" "rdm/specification/init_files/"
            }
            tags "context:specification"
          }
          adoption = component "Adoption" "rdm adopt: lays down each missing file of the adoption templates, never overwriting one" "Python" {
            properties {
              "code" "rdm/specification/adopt.py"
            }
            tags "context:specification"
          }
          adoption_templates = component "Adoption templates" "What rdm adopt lays down" "Markdown, YAML" {
            properties {
              "code" "rdm/specification/adopt_files/"
            }
            tags "context:specification"
          }
          validation_records = component "Validation records" "Approved validation records per user need" "Python" {
            properties {
              "code" "rdm/specification/validation.py"
            }
            tags "context:specification"
          }
          formative_usability = component "Formative usability" "Persona runs as formative evidence" "Python" {
            properties {
              "code" "rdm/specification/persona.py"
            }
            tags "context:specification"
          }
          persona_command = component "Persona command" "rdm story persona: the formative status of each user need; informational, never fails a successful run" "Python" {
            properties {
              "code" "rdm/specification/persona_cmd.py"
            }
            tags "context:specification"
          }
        }
        group "release" {
          verification_data = component "Verification data" "Every declared design input reconciled against the executed results: status, runs and tests per design input, by user need; generated, never edited" "Python" {
            properties {
              "code" "rdm/release/verify.py"
            }
            tags "context:release"
          }
          release_gate = component "Release gate" "Blocks a release on design-gate failures, failed or untested design inputs, blocking risk findings or an unrefined user need; warns on unvalidated user needs and orphan tags; the design gate's results warnings; the trace slice" "Python" {
            properties {
              "code" "rdm/release/gate.py"
            }
            tags "context:release"
          }
        }
        group "test_evidence" {
          allure_reader = component "Allure reader" "Allure results into test runs and a status per design input (failed, else verified, else untested; undeclared stories as orphans); run labels and facts" "Python" {
            properties {
              "code" "rdm/evidence/allure.py"
            }
            tags "context:test_evidence"
          }
          mutation_probe = component "Mutation probe" "rdm story mutation-probe: runs one test unmutated, then mutated; killed, survived or error; journals the original beside the file and always restores it" "Python" {
            properties {
              "code" "rdm/evidence/mutation.py"
            }
            tags "context:test_evidence"
          }
          test_translation = component "Test result translation" "rdm translate: foreign XML results to a YAML data file; rejects an unknown format" "Python" {
            properties {
              "code" "rdm/evidence/translate.py"
            }
            tags "context:test_evidence"
          }
          result_formatters = component "Result formatters" "Flattens gtest, xunit and qttest XML into result data" "Python" {
            properties {
              "code" "rdm/evidence/test_formatters/"
            }
            tags "context:test_evidence"
          }
        }
        group "risk" {
          risk_register = component "Risk register" "The risk register evaluated against the declared risk policy; the risk rules as findings, written only here; handed the declared and verified design input ids, it reads nothing of the specification" "Python" {
            properties {
              "code" "rdm/risk/register.py"
            }
            tags "context:risk"
          }
        }
        group "architecture" {
          architecture_model = component "Architecture model" "The C4 model, read from the workspace's export (declared relationships only); view freshness from the stamps; the component owning a file (longest code match) and the import dependencies between components" "Python" {
            properties {
              "code" "rdm/architecture/model.py"
            }
            tags "context:architecture"
          }
          architecture_drawing = component "Architecture drawing" "rdm c4 draw: Structurizr export, Graphviz views, each stamped with the workspace digest; all or nothing, removes the image of a removed view" "Python, Structurizr, Graphviz" {
            properties {
              "code" "rdm/architecture/draw.py"
            }
            tags "context:architecture"
          }
        }
        group "compliance" {
          gap_analysis = component "Gap analysis" "Reads a checklist and its includes, finds the checklist clauses no document references, and reports the gaps or the coverage; offers its reader and key matcher to other contexts" "Python" {
            properties {
              "code" "rdm/compliance/gaps.py"
            }
            tags "context:compliance"
          }
          checklists = component "Checklists" "The built-in checklists: IEC 62304 (base, 2006, 2015; classes A-C), ISO 14971 2007/2019, FDA-SW 2005/2021, FDA-CYBER 2018, FDA-HFE 2011, 21 CFR Part 11 document control" "text" {
            properties {
              "code" "rdm/compliance/checklists/"
            }
            tags "context:compliance"
          }
        }
        group "publishing" {
          renderer = component "Renderer" "Templates and data to Markdown" "Python, Jinja2" {
            properties {
              "code" "rdm/publishing/render.py"
            }
            tags "context:publishing"
          }
          markdown_extensions = component "Markdown extensions" "Section numbers, vocabulary and audit-note exclusion over the rendered lines; loaded by name from the project's config.yml" "Python" {
            properties {
              "code" "rdm/md_extensions/"
            }
            tags "context:publishing"
          }
          first_pass_output = component "First-pass output" "The words a first render produced, for the second pass to test against" "Python" {
            properties {
              "code" "rdm/publishing/first_pass_output.py"
            }
            tags "context:publishing"
          }
          evidence_bundle = component "Evidence bundle" "The retained release evidence" "Python" {
            properties {
              "code" "rdm/publishing/bundle.py"
            }
            tags "context:publishing"
          }
          snippets = component "Code snippets" "rdm collect: RDOC/ENDRDOC snippets from source files, by key, as YAML data" "Python" {
            properties {
              "code" "rdm/publishing/collect.py"
            }
            tags "context:publishing"
          }
          dmr_index = component "DMR index" "The device-master-record index from frontmatter" "Python" {
            properties {
              "code" "rdm/publishing/dmr.py"
            }
            tags "context:publishing"
          }
          verification_report = component "Verification report" "rdm story evidence-report: the PDF of every run behind each design input" "Python" {
            properties {
              "code" "rdm/publishing/report.py"
            }
            tags "context:publishing"
          }
          report_layout = component "Report layout" "The report's page layout" "Typst" {
            properties {
              "code" "rdm/publishing/verification_report.typ"
            }
            tags "context:publishing"
          }
        }
        group "graph" {
          projection = component "Projection" "The record into RDF, one named graph per source, de-duplicated and sorted; replaces the store on each build; rdm graph build, query, serve (read-only) and explorer-file" "Python, pyoxigraph" {
            properties {
              "code" "rdm/graph/"
            }
            tags "context:graph"
          }
          vocabulary = component "Vocabulary" "Classes, properties and the rules that derive relations" "Turtle" {
            properties {
              "code" "rdm/graph/ontology.ttl"
            }
            tags "context:graph"
          }
          gate_shapes = component "Gate shapes" "The gate rules as SHACL: violations block, warnings inform" "SHACL" {
            properties {
              "code" "rdm/graph/shapes.ttl"
            }
            tags "context:graph"
          }
          shacl_validation = component "SHACL validation" "rdm graph validate" "Python, pySHACL" {
            properties {
              "code" "rdm/graph/validate.py"
            }
            tags "context:graph"
          }
          explorer_file = component "Explorer file" "rdm graph explorer-file: the record as an AWS Graph Explorer file, optionally without chosen classes and the nodes that hang only from them" "Python" {
            properties {
              "code" "rdm/graph/explorer.py"
            }
            tags "context:graph"
          }
          agent_server = component "Agent server" "rdm graph mcp: read-only, idempotent schema, query, trace and validate for agents, each projecting the record afresh; query rows capped" "Python, MCP" {
            properties {
              "code" "rdm/graph/agent.py"
            }
            tags "context:graph"
          }
        }
      }
      test_run = container "Acceptance test run" "Runs the tagged tests; RDM's plugin labels each run from the record" "pytest, allure-pytest" {
        group "test_evidence" {
          pytest_plugin = component "pytest plugin" "Labels each run from the record; the run's executor and environment, written only when the run writes Allure results" "Python, pytest" {
            properties {
              "code" "rdm/pytest_plugin.py"
            }
            tags "context:test_evidence"
          }
        }
      }
      gates_ci = container "Reusable gates" "The workflow and actions other repositories call" "GitHub Actions" {
        group "release" {
          reusable_workflow = component "Reusable workflow" "Checks out the caller and RDM at the pinned revision, installs RDM from it, runs the acceptance tests, then the gates; uploads the Allure report and verification record" "GitHub Actions" {
            properties {
              "code" ".github/workflows/gates.yml"
            }
            tags "context:release"
          }
          gates_action = component "Gates action" "The gates as steps: design gate, verification data, release gate, graph validation, evidence bundle (uploaded); each switchable; inputs passed as environment variables" "GitHub Actions" {
            properties {
              "code" "actions/gates/"
            }
            tags "context:release"
          }
        }
        group "publishing" {
          pdf_action = component "PDF action" "Renders the documents in the image" "GitHub Actions" {
            properties {
              "code" "action.yml"
            }
            tags "context:publishing"
          }
        }
      }
      graph_store = container "Graph store" "The record projected into RDF" "Oxigraph" "Database"
      sparql_endpoint = container "SPARQL endpoint" "rdm graph serve: the store, read-only, refusing updates and SERVICE" "Python, pyoxigraph"
      documents_image = container "Documents image" "Renders the documents to PDF" "Docker: Ubuntu, Pandoc, Typst"
    }
    product_repo = softwareSystem "Product repository" "git: the Markdown record, the tests and their Allure results" "External"
    forge = softwareSystem "GitHub" "Pull requests, Actions and the image registry" "External"
    agent_harness = softwareSystem "Agent harness" "An MCP client such as Claude Code, working under a person's direction" "External"
    graph_explorer = softwareSystem "AWS Graph Explorer" "A graph browser" "External"
    author -> rdm_system "writes the record and renders documents with"
    engineer -> rdm_system "runs the gates and tests with"
    reviewer -> forge "approves pull requests on"
    rdm_system -> product_repo "reads the record, tests, results and history from"
    forge -> rdm_system "runs the gates of" "GitHub Actions"
    agent_harness -> rdm_system "reads the record through" "MCP"
    graph_explorer -> rdm_system "browses the graph of" "SPARQL over HTTP"
    author -> rdm_cli "renders documents and checks the record with"
    engineer -> test_run "runs"
    rdm_cli -> product_repo "reads the record, results and git history from"
    test_run -> product_repo "writes Allure results to"
    rdm_cli -> graph_store "builds"
    sparql_endpoint -> graph_store "serves, read-only"
    graph_explorer -> sparql_endpoint "queries" "SPARQL over HTTP"
    agent_harness -> rdm_cli "calls the agent server of" "MCP over stdio"
    forge -> gates_ci "runs on every push and pull request"
    gates_ci -> test_run "runs"
    gates_ci -> rdm_cli "installs and runs"
    documents_image -> rdm_cli "renders with"
    gap_analysis -> checklists "resolves built-in names and follows includes in"
    hooks -> precommit_hook "installs"
    precommit_hook -> design_gate "runs"
    projection -> risk_register "reads risks and findings with"
    projection -> release_gate "reads the findings about the whole record with"
    projection -> vocabulary "declares terms and rules in"
    projection -> explorer_file "writes the explorer file with"
    explorer_file -> projection "takes the executions graph's name from"
    shacl_validation -> projection "validates the graph of"
    shacl_validation -> gate_shapes "checks with"
    agent_server -> projection "projects the record afresh on each call with"
    agent_server -> shacl_validation "validates with"
    projection -> gap_analysis "reads checklists and matches checklist references with"
    test_translation -> result_formatters "parses with"
    project_scaffold -> project_templates "copies"
    project_scaffold -> kernel "takes the release version from"
    adoption -> adoption_templates "copies"
    reusable_workflow -> gates_action "runs the gates with"
    persona_command -> formative_usability "classifies runs with"
    evidence_bundle -> verification_data "writes verification.yml with"
    evidence_bundle -> verification_report "writes the report PDF with"
    evidence_bundle -> renderer "renders the matrix with"
    verification_report -> verification_data "builds on"
    verification_report -> risk_register "reads risk status and residual decisions with"
    verification_report -> report_layout "lays out with"
    pytest_plugin -> risk_register "finds the risks each design input controls with"
    architecture_drawing -> architecture_model "takes the paths, view keys, stamp format and workspace digest from"
    design_gate -> architecture_model "checks the views are fresh with"
    projection -> allure_reader "reads results and test tags with"
    projection -> architecture_model "reads the C4 model and code dependencies with"
    pytest_plugin -> allure_reader "writes run labels and facts with"
    verification_data -> allure_reader "reconciles results with"
    verification_report -> allure_reader "reads results with"
    architecture_drawing -> product_repo "writes the exported model and stamped view images to"
    renderer -> markdown_extensions "post-processes the rendered Markdown with"
    renderer -> first_pass_output "keeps the first pass's words in"
    markdown_extensions -> first_pass_output "gives the template the first pass's words from"
    pdf_action -> documents_image "renders the documents in" "docker run make pdfs"
    gates_action -> verification_data "writes verification.yml with"
    gates_action -> shacl_validation "validates the graph with"
    gates_action -> evidence_bundle "writes the evidence bundle with"
    reusable_workflow -> test_run "runs the acceptance tests in"
    mutation_probe -> test_run "runs one test, unmutated then mutated, in" "pytest subprocess"
    project_scaffold -> adoption_templates "copies the agent workflow runbook from"
    adoption -> precommit_hook "copies"
    adoption -> hooks "takes the design gate's hook names from"
    agent_server -> vocabulary "returns the vocabulary and rules of"
    projection -> graph_store "builds"
    agent_harness -> agent_server "calls" "MCP over stdio"
    adoption -> kernel "takes the version from"
    agent_server -> kernel "checks ids with"
    allure_reader -> kernel "buckets observations by id with"
    design_gate -> kernel "asks git with and checks ids with"
    design_gate -> record_reader "reads the record with"
    dmr_index -> kernel "parses frontmatter with"
    evidence_bundle -> kernel "uses"
    evidence_bundle -> allure_reader "keeps the run files named by"
    evidence_bundle -> record_reader "finds the matrix template with"
    formative_usability -> kernel "buckets observations by id with"
    hooks -> kernel "uses"
    markdown_extensions -> kernel "uses"
    new_input -> record_reader "reads the design documents with"
    persona_command -> record_reader "reads the V&V plan with"
    projection -> kernel "asks git with and checks ids with and parses frontmatter with"
    projection -> record_reader "reads the record with"
    pytest_plugin -> kernel "asks git with"
    pytest_plugin -> test_tags "takes the label that names a design input from"
    pytest_plugin -> record_reader "reads the record with"
    record_reader -> kernel "parses frontmatter with"
    release_gate -> kernel "checks ids with"
    release_gate -> record_reader "reads the record with"
    renderer -> kernel "uses"
    risk_register -> kernel "parses frontmatter with"
    test_tags -> kernel "asks git with and checks ids with"
    new_input -> kernel "sorts ids with"
    verification_data -> kernel "checks ids with"
    test_translation -> kernel "uses"
    validation_records -> kernel "buckets observations by id with"
    validation_records -> record_reader "reads the user-need registry with"
    verification_data -> record_reader "reads the record with"
    verification_report -> kernel "asks git with and buckets observations by id with and checks ids with and takes the version from"
    verification_report -> record_reader "reads the record with"
    allure_reader -> test_tags "takes the label that names a design input from"
    design_gate -> test_tags "finds the tagged tests with"
    new_input -> test_tags "finds the test suite with"
    projection -> test_tags "reads the tagged tests with"
    verification_report -> test_tags "reads the tagged tests with"
    release_gate -> design_gate "runs the design gate's checks of"
    release_gate -> allure_reader "reconciles results with"
    release_gate -> risk_register "reports the risk findings of"
    release_gate -> validation_records "reads validation records with"
    gates_action -> design_gate "runs the design gate of"
    gates_action -> release_gate "runs the release gate of"
  }
  views {
    systemContext rdm_system "C1" {
      title "RDM: system context (C1)"
      include *
      include reviewer
      autolayout tb
    }
    container rdm_system "C2" {
      title "RDM: containers (C2)"
      include *
      autolayout tb
    }
    component rdm_cli "C3_specification" {
      title "Design specification: components (C3)"
      include record_reader test_tags design_gate hooks precommit_hook new_input project_scaffold project_templates adoption adoption_templates validation_records formative_usability persona_command allure_reader architecture_model evidence_bundle gates_action projection pytest_plugin release_gate verification_data verification_report
      exclude "evidence_bundle -> verification_data"
      exclude "evidence_bundle -> verification_report"
      exclude "gates_action -> evidence_bundle"
      exclude "gates_action -> release_gate"
      exclude "gates_action -> verification_data"
      exclude "projection -> allure_reader"
      exclude "projection -> architecture_model"
      exclude "pytest_plugin -> allure_reader"
      exclude "release_gate -> allure_reader"
      exclude "verification_data -> allure_reader"
      exclude "verification_report -> allure_reader"
      exclude "verification_report -> verification_data"
      autolayout tb
    }
    component rdm_cli "C3_release" {
      title "Release: components (C3)"
      include verification_data release_gate reusable_workflow gates_action allure_reader design_gate evidence_bundle record_reader risk_register shacl_validation test_run validation_records verification_report
      exclude "design_gate -> record_reader"
      exclude "evidence_bundle -> record_reader"
      exclude "evidence_bundle -> verification_report"
      exclude "validation_records -> record_reader"
      exclude "verification_report -> allure_reader"
      exclude "verification_report -> record_reader"
      exclude "verification_report -> risk_register"
      autolayout tb
    }
    component rdm_cli "C3_test_evidence" {
      title "Test evidence: components (C3)"
      include allure_reader mutation_probe test_translation result_formatters pytest_plugin projection record_reader release_gate risk_register test_run test_tags verification_data verification_report
      exclude "projection -> record_reader"
      exclude "projection -> risk_register"
      exclude "projection -> test_tags"
      exclude "release_gate -> record_reader"
      exclude "release_gate -> risk_register"
      exclude "verification_data -> record_reader"
      exclude "verification_report -> record_reader"
      exclude "verification_report -> risk_register"
      exclude "verification_report -> test_tags"
      exclude "verification_report -> verification_data"
      autolayout tb
    }
    component rdm_cli "C3_risk" {
      title "Risk: components (C3)"
      include risk_register projection pytest_plugin release_gate verification_report
      autolayout tb
    }
    component rdm_cli "C3_architecture" {
      title "Architecture: components (C3)"
      include architecture_model architecture_drawing design_gate product_repo projection
      autolayout tb
    }
    component rdm_cli "C3_compliance" {
      title "Compliance: components (C3)"
      include gap_analysis checklists projection
      autolayout tb
    }
    component rdm_cli "C3_publishing" {
      title "Publishing: components (C3)"
      include renderer markdown_extensions first_pass_output evidence_bundle snippets dmr_index verification_report report_layout pdf_action allure_reader documents_image gates_action record_reader risk_register test_tags verification_data
      exclude "allure_reader -> test_tags"
      exclude "gates_action -> verification_data"
      exclude "verification_data -> allure_reader"
      exclude "verification_data -> record_reader"
      autolayout tb
    }
    component rdm_cli "C3_graph" {
      title "Knowledge graph: components (C3)"
      include projection vocabulary gate_shapes shacl_validation explorer_file agent_server agent_harness allure_reader architecture_model gap_analysis gates_action graph_store record_reader risk_register test_tags
      exclude "allure_reader -> test_tags"
      autolayout tb
    }
    dynamic rdm_cli "D_specification_commit" {
      title "Design specification: a commit meets the design gate (dynamic)"
      precommit_hook -> design_gate "runs"
      design_gate -> record_reader "reads the design documents and the review with"
      design_gate -> architecture_model "checks the views are fresh with"
      design_gate -> test_tags "finds tagged tests with"
      autolayout lr
    }
    dynamic rdm_system "D_release_pipeline" {
      title "Release: one run of the reusable gates (dynamic)"
      forge -> gates_ci "runs on every push and pull request"
      gates_ci -> test_run "runs"
      test_run -> product_repo "writes Allure results to"
      gates_ci -> rdm_cli "installs and runs"
      rdm_cli -> product_repo "reads the record, results and git history from"
      autolayout lr
    }
    dynamic rdm_cli "D_publishing_render" {
      title "Publishing: rendering a document (dynamic)"
      renderer -> kernel "loads the data files as the context with"
      renderer -> first_pass_output "keeps the first pass's words in"
      renderer -> markdown_extensions "post-processes the rendered Markdown with"
      markdown_extensions -> first_pass_output "gives the template the first pass's words from"
      autolayout lr
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
