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
          utilities = component "Utilities" "Shared YAML and file helpers (the shared kernel)" "Python" {
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
          allure_reader = component "Allure reader" "Allure results, and the test tags in test sources" "Python" {
            properties {
              "code" "rdm/record/allure.py"
            }
            tags "context:test_evidence"
          }
          mutation_probe = component "Mutation probe" "Breaks a line, runs one test, restores" "Python" {
            properties {
              "code" "rdm/gates/mutation.py"
            }
            tags "context:test_evidence"
          }
          test_translation = component "Test result translation" "Translates foreign test results" "Python" {
            properties {
              "code" "rdm/translate.py"
            }
            tags "context:test_evidence"
          }
          result_formatters = component "Result formatters" "JUnit and other result formats" "Python" {
            properties {
              "code" "rdm/test_formatters/"
            }
            tags "context:test_evidence"
          }
        }
        group "risk" {
          risk_register = component "Risk register" "Risks scored from the policy; the release rules" "Python" {
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
          gap_analysis = component "Gap analysis" "Documents against checklists" "Python" {
            properties {
              "code" "rdm/gaps.py"
            }
            tags "context:compliance"
          }
          checklists = component "Checklists" "The built-in checklists" "text" {
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
          snippets = component "Code snippets" "Collects tagged code snippets" "Python" {
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
          verification_report = component "Verification report" "The PDF of every run behind each design input" "Python" {
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
          projection = component "Projection" "The record into RDF: named graphs, rules, the graph commands" "Python, pyoxigraph" {
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
          gate_shapes = component "Gate shapes" "The gate rules as shapes" "SHACL" {
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
          explorer_file = component "Explorer file" "A file for Graph Explorer" "Python" {
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
    gap_analysis -> checklists "reads"
    design_gate -> record_readers "reads the record with"
    design_gate -> risk_register "applies the risk rules of"
    design_gate -> validation_records "reads validation records with"
    hooks -> precommit_hook "installs"
    hooks -> utilities "uses"
    precommit_hook -> design_gate "runs"
    projection -> record_readers "reads the record, results and git with"
    projection -> risk_register "reads risks and findings with"
    projection -> vocabulary "declares terms and rules in"
    projection -> explorer_file "writes the explorer file with"
    explorer_file -> projection "reads the projected graph from"
    shacl_validation -> projection "validates the graph of"
    shacl_validation -> gate_shapes "checks with"
    agent_server -> projection "queries"
    agent_server -> shacl_validation "validates with"
    agent_server -> record_readers "checks ids with"
    projection -> gap_analysis "matches checklist references with"
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
    evidence_bundle -> verification_data "writes"
    evidence_bundle -> verification_report "writes"
    evidence_bundle -> record_readers "finds the matrix with"
    evidence_bundle -> renderer "renders the matrix with"
    evidence_bundle -> utilities "uses"
    verification_report -> verification_data "builds on"
    verification_report -> record_readers "reads results and git with"
    verification_report -> risk_register "reads risks with"
    verification_report -> report_layout "lays out with"
    pytest_plugin -> record_readers "reads the record with"
    pytest_plugin -> risk_register "reads risks with"
    allure_reader -> record_readers "finds the repository and checks ids with"
    architecture_drawing -> architecture_model "stamps views with the workspace digest of"
    design_gate -> allure_reader "finds tagged tests and reconciles results with"
    design_gate -> architecture_model "checks the views are fresh with"
    new_input -> allure_reader "finds the test suite with"
    projection -> allure_reader "reads results and test tags with"
    projection -> architecture_model "reads the C4 model and code dependencies with"
    pytest_plugin -> allure_reader "writes run labels and facts with"
    verification_data -> allure_reader "reconciles results with"
    verification_report -> allure_reader "reads results with"
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
      include record_readers utilities design_gate hooks precommit_hook new_input project_scaffold project_templates adoption adoption_templates validation_records formative_usability persona_command allure_reader architecture_model risk_register
      autolayout tb
    }
    component rdm_cli "C3_release" {
      title "Release: components (C3)"
      include verification_data evidence_bundle reusable_workflow gates_action allure_reader record_readers renderer utilities verification_report
      autolayout tb
    }
    component rdm_cli "C3_test_evidence" {
      title "Test evidence: components (C3)"
      include allure_reader mutation_probe test_translation result_formatters pytest_plugin design_gate new_input projection record_readers risk_register utilities verification_data verification_report
      autolayout tb
    }
    component rdm_cli "C3_risk" {
      title "Risk: components (C3)"
      include risk_register design_gate projection pytest_plugin record_readers verification_report
      autolayout tb
    }
    component rdm_cli "C3_architecture" {
      title "Architecture: components (C3)"
      include architecture_model architecture_drawing design_gate projection
      autolayout tb
    }
    component rdm_cli "C3_compliance" {
      title "Compliance: components (C3)"
      include gap_analysis checklists projection
      autolayout tb
    }
    component rdm_cli "C3_publishing" {
      title "Publishing: components (C3)"
      include renderer markdown_extensions snippets dmr_index verification_report report_layout pdf_action allure_reader evidence_bundle record_readers risk_register utilities verification_data
      autolayout tb
    }
    component rdm_cli "C3_graph" {
      title "Knowledge graph: components (C3)"
      include projection vocabulary gate_shapes shacl_validation explorer_file agent_server allure_reader architecture_model gap_analysis record_readers risk_register
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
