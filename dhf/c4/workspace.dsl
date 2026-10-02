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
          record_readers = component "Record kernel" "Design and V&V frontmatter, ids, git, the shared reconcile helpers" "Python" {
            properties {
              "code" "rdm/record/"
            }
            tags "context:specification"
          }
          utilities = component "Utilities" "Shared YAML, message and repository-root helpers (the shared kernel)" "Python" {
            properties {
              "code" "rdm/util.py"
            }
            tags "context:specification"
          }
          design_gate = component "Design and release gates" "Design gate and duplicate ids; the release gate and trace until they move to release" "Python" {
            properties {
              "code" "rdm/gates/design_gate.py"
            }
            tags "context:specification"
          }
          hooks = component "Hooks installer" "rdm hooks" "Python" {
            properties {
              "code" "rdm/hooks.py"
            }
            tags "context:specification"
          }
          precommit_hook = component "Pre-commit hook" "Runs the design gate before a commit" "shell" {
            properties {
              "code" "rdm/hook_files/"
            }
            tags "context:specification"
          }
          new_input = component "New design input" "rdm story new-input" "Python" {
            properties {
              "code" "rdm/gates/new_input.py"
            }
            tags "context:specification"
          }
          project_scaffold = component "Project scaffold" "rdm init" "Python" {
            properties {
              "code" "rdm/init.py"
            }
            tags "context:specification"
          }
          project_templates = component "Project templates" "What rdm init lays down" "Markdown, YAML, Typst" {
            properties {
              "code" "rdm/init_files/"
            }
            tags "context:specification"
          }
          adoption = component "Adoption" "rdm adopt" "Python" {
            properties {
              "code" "rdm/adopt.py"
            }
            tags "context:specification"
          }
          adoption_templates = component "Adoption templates" "What rdm adopt lays down" "Markdown, YAML" {
            properties {
              "code" "rdm/adopt_files/"
            }
            tags "context:specification"
          }
          validation_records = component "Validation records" "Approved validation records per user need" "Python" {
            properties {
              "code" "rdm/record/validation.py"
            }
            tags "context:specification"
          }
          formative_usability = component "Formative usability" "Persona runs as formative evidence" "Python" {
            properties {
              "code" "rdm/record/persona.py"
            }
            tags "context:specification"
          }
          persona_command = component "Persona command" "rdm story persona" "Python" {
            properties {
              "code" "rdm/record/persona_cmd.py"
            }
            tags "context:specification"
          }
        }
        group "release" {
          verification_data = component "Verification data" "Design inputs against results: verification.yml" "Python" {
            properties {
              "code" "rdm/record/verify.py"
            }
            tags "context:release"
          }
          evidence_bundle = component "Evidence bundle" "The retained release evidence" "Python" {
            properties {
              "code" "rdm/record/bundle.py"
            }
            tags "context:release"
          }
        }
        group "test_evidence" {
          allure_reader = component "Allure reader" "Allure results into test runs and a status per design input; run labels and facts; the test tags in test sources until the split" "Python" {
            properties {
              "code" "rdm/record/allure.py"
            }
            tags "context:test_evidence"
          }
          mutation_probe = component "Mutation probe" "rdm story mutation-probe: runs one test unmutated, then mutated, and always restores the file" "Python" {
            properties {
              "code" "rdm/gates/mutation.py"
            }
            tags "context:test_evidence"
          }
          test_translation = component "Test result translation" "rdm translate: foreign XML results to a YAML data file; rejects an unknown format" "Python" {
            properties {
              "code" "rdm/translate.py"
            }
            tags "context:test_evidence"
          }
          result_formatters = component "Result formatters" "Flattens gtest, xunit and qttest XML into result data" "Python" {
            properties {
              "code" "rdm/test_formatters/"
            }
            tags "context:test_evidence"
          }
        }
        group "risk" {
          risk_register = component "Risk register" "The risk register evaluated against the declared risk policy; the risk rules as findings" "Python" {
            properties {
              "code" "rdm/record/risk.py"
            }
            tags "context:risk"
          }
        }
        group "architecture" {
          architecture_model = component "Architecture model" "The C4 model, read from the workspace's export; view freshness" "Python" {
            properties {
              "code" "rdm/record/c4.py"
            }
            tags "context:architecture"
          }
          architecture_drawing = component "Architecture drawing" "rdm c4 draw: Structurizr export, Graphviz views, stamps" "Python, Structurizr, Graphviz" {
            properties {
              "code" "rdm/c4.py"
            }
            tags "context:architecture"
          }
        }
        group "compliance" {
          gap_analysis = component "Gap analysis" "Finds the checklist clauses no document references, and reports coverage" "Python" {
            properties {
              "code" "rdm/gaps.py"
            }
            tags "context:compliance"
          }
          checklists = component "Checklists" "The built-in checklists: IEC 62304, ISO 14971, FDA-SW/CYBER/HFE, 21 CFR Part 11" "text" {
            properties {
              "code" "rdm/checklists/"
            }
            tags "context:compliance"
          }
        }
        group "publishing" {
          renderer = component "Renderer" "Templates and data to Markdown" "Python, Jinja2" {
            properties {
              "code" "rdm/render.py"
            }
            tags "context:publishing"
          }
          markdown_extensions = component "Markdown extensions" "Section numbers, vocabulary, audit notes" "Python" {
            properties {
              "code" "rdm/md_extensions/"
            }
            tags "context:publishing"
          }
          first_pass_output = component "First-pass output" "The words a first render produced, for the second pass to test against" "Python" {
            properties {
              "code" "rdm/first_pass_output.py"
            }
            tags "context:publishing"
          }
          snippets = component "Code snippets" "RDOC/ENDRDOC snippets from source files, as YAML data" "Python" {
            properties {
              "code" "rdm/collect.py"
            }
            tags "context:publishing"
          }
          dmr_index = component "DMR index" "The device-master-record index from frontmatter" "Python" {
            properties {
              "code" "rdm/record/dmr.py"
            }
            tags "context:publishing"
          }
          verification_report = component "Verification report" "rdm story evidence-report: the PDF of every run behind each design input" "Python" {
            properties {
              "code" "rdm/record/report.py"
            }
            tags "context:publishing"
          }
          report_layout = component "Report layout" "The report's page layout" "Typst" {
            properties {
              "code" "rdm/record/verification_report.typ"
            }
            tags "context:publishing"
          }
        }
        group "graph" {
          projection = component "Projection" "The record into RDF, one named graph per source; the store; rdm graph build, query, serve and explorer-file" "Python, pyoxigraph" {
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
          explorer_file = component "Explorer file" "rdm graph explorer-file: the record as an AWS Graph Explorer file" "Python" {
            properties {
              "code" "rdm/graph/explorer.py"
            }
            tags "context:graph"
          }
          agent_server = component "Agent server" "Read-only schema, query, trace and validate for agents" "Python, MCP" {
            properties {
              "code" "rdm/graph/agent.py"
            }
            tags "context:graph"
          }
        }
      }
      test_run = container "Acceptance test run" "Runs the tagged tests; RDM's plugin labels each run from the record" "pytest, allure-pytest" {
        group "test_evidence" {
          pytest_plugin = component "pytest plugin" "Labels each run from the record; the run's executor and environment" "Python, pytest" {
            properties {
              "code" "rdm/pytest_plugin.py"
            }
            tags "context:test_evidence"
          }
        }
      }
      gates_ci = container "Reusable gates" "The workflow and actions other repositories call" "GitHub Actions" {
        group "release" {
          reusable_workflow = component "Reusable workflow" "Tests, then the gates, for any repository" "GitHub Actions" {
            properties {
              "code" ".github/workflows/gates.yml"
            }
            tags "context:release"
          }
          gates_action = component "Gates action" "The gates as steps" "GitHub Actions" {
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
      sparql_endpoint = container "SPARQL endpoint" "Serves the store read-only" "Oxigraph server"
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
    design_gate -> record_readers "reads the record with"
    design_gate -> risk_register "reports the risk findings of"
    design_gate -> validation_records "reads validation records with"
    hooks -> precommit_hook "installs"
    hooks -> utilities "uses"
    precommit_hook -> design_gate "runs"
    projection -> record_readers "reads the record, results and git with"
    projection -> risk_register "reads risks and findings with"
    projection -> vocabulary "declares terms and rules in"
    projection -> explorer_file "writes the explorer file with"
    explorer_file -> projection "takes the executions graph's name from"
    shacl_validation -> projection "validates the graph of"
    shacl_validation -> gate_shapes "checks with"
    agent_server -> projection "projects the record afresh on each call with"
    agent_server -> shacl_validation "validates with"
    agent_server -> record_readers "checks ids with"
    projection -> gap_analysis "reads checklists and matches checklist references with"
    test_translation -> result_formatters "parses with"
    test_translation -> utilities "uses"
    dmr_index -> record_readers "reads frontmatter with"
    renderer -> utilities "uses"
    markdown_extensions -> utilities "uses"
    risk_register -> record_readers "reads frontmatter with"
    project_scaffold -> project_templates "copies"
    adoption -> adoption_templates "copies"
    new_input -> record_readers "reads the design documents with"
    reusable_workflow -> gates_action "runs the gates with"
    formative_usability -> record_readers "loads runs with"
    persona_command -> formative_usability "classifies runs with"
    persona_command -> record_readers "reads the V&V plan with"
    validation_records -> record_readers "reads the record with"
    verification_data -> record_readers "reads the record and results with"
    evidence_bundle -> verification_data "writes verification.yml with"
    evidence_bundle -> verification_report "writes the report PDF with"
    evidence_bundle -> record_readers "finds the matrix with"
    evidence_bundle -> renderer "renders the matrix with"
    evidence_bundle -> utilities "loads the render config with"
    verification_report -> verification_data "builds on"
    verification_report -> record_readers "reads results and git with"
    verification_report -> risk_register "reads risk status and residual decisions with"
    verification_report -> report_layout "lays out with"
    pytest_plugin -> record_readers "reads the record with"
    pytest_plugin -> risk_register "finds the risks each design input controls with"
    allure_reader -> record_readers "finds the repository and checks ids with"
    architecture_drawing -> architecture_model "takes the paths, view keys, stamp format and workspace digest from"
    design_gate -> allure_reader "finds tagged tests and reconciles results with"
    design_gate -> architecture_model "checks the views are fresh with"
    new_input -> allure_reader "finds the test suite (tag scanning) with"
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
    gates_action -> design_gate "runs the design gate and the release gate of"
    gates_action -> verification_data "writes verification.yml with"
    gates_action -> shacl_validation "validates the graph with"
    gates_action -> evidence_bundle "writes the evidence bundle with"
    reusable_workflow -> test_run "runs the acceptance tests in"
    mutation_probe -> test_run "runs one test, unmutated then mutated, in" "pytest subprocess"
    project_scaffold -> adoption_templates "copies the agent workflow runbook from"
    adoption -> precommit_hook "copies"
    agent_server -> vocabulary "returns the vocabulary and rules of"
    projection -> graph_store "builds"
    agent_harness -> agent_server "calls" "MCP over stdio"
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
      include record_readers utilities design_gate hooks precommit_hook new_input project_scaffold project_templates adoption adoption_templates validation_records formative_usability persona_command allure_reader architecture_model gates_action risk_register
      autolayout tb
    }
    component rdm_cli "C3_release" {
      title "Release: components (C3)"
      include verification_data evidence_bundle reusable_workflow gates_action allure_reader design_gate record_readers renderer risk_register shacl_validation test_run utilities verification_report
      exclude "allure_reader -> record_readers"
      exclude "renderer -> utilities"
      exclude "verification_report -> allure_reader"
      exclude "verification_report -> record_readers"
      exclude "verification_report -> risk_register"
      autolayout tb
    }
    component rdm_cli "C3_test_evidence" {
      title "Test evidence: components (C3)"
      include allure_reader mutation_probe test_translation result_formatters pytest_plugin design_gate new_input projection record_readers risk_register test_run utilities verification_data verification_report
      exclude "design_gate -> record_readers"
      exclude "design_gate -> risk_register"
      exclude "new_input -> record_readers"
      exclude "projection -> record_readers"
      exclude "projection -> risk_register"
      exclude "risk_register -> record_readers"
      exclude "verification_data -> record_readers"
      exclude "verification_report -> record_readers"
      exclude "verification_report -> risk_register"
      exclude "verification_report -> verification_data"
      autolayout tb
    }
    component rdm_cli "C3_risk" {
      title "Risk: components (C3)"
      include risk_register design_gate projection pytest_plugin record_readers verification_report
      exclude "design_gate -> record_readers"
      exclude "projection -> record_readers"
      exclude "pytest_plugin -> record_readers"
      exclude "verification_report -> record_readers"
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
      include renderer markdown_extensions first_pass_output snippets dmr_index verification_report report_layout pdf_action allure_reader documents_image evidence_bundle record_readers risk_register utilities verification_data
      exclude "allure_reader -> record_readers"
      exclude "evidence_bundle -> record_readers"
      exclude "evidence_bundle -> utilities"
      exclude "evidence_bundle -> verification_data"
      exclude "risk_register -> record_readers"
      exclude "verification_data -> allure_reader"
      exclude "verification_data -> record_readers"
      autolayout tb
    }
    component rdm_cli "C3_graph" {
      title "Knowledge graph: components (C3)"
      include projection vocabulary gate_shapes shacl_validation explorer_file agent_server agent_harness allure_reader architecture_model gap_analysis gates_action graph_store record_readers risk_register
      exclude "allure_reader -> record_readers"
      exclude "risk_register -> record_readers"
      autolayout tb
    }
    dynamic rdm_cli "D_specification_commit" {
      title "Design specification: a commit meets the design gate (dynamic)"
      precommit_hook -> design_gate "runs"
      design_gate -> record_readers "reads the design documents and the review with"
      design_gate -> architecture_model "checks the views are fresh with"
      design_gate -> allure_reader "finds tagged tests with"
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
      renderer -> utilities "loads the data files as the context with"
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
