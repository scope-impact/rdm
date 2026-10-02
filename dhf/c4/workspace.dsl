# RDM's architecture: the C4 model and its views (DI-66, DI-70).
#
# This file is the source. `rdm c4 draw` exports the model to workspace.json
# and draws each view to views/<view>.svg; both are stamped with this file's
# hash, and the design gate fails when they are stale. Each component names its
# code with a "code" property; each bounded context is a group of components.
workspace "RDM" "The design record of regulated software." {
  !identifiers flat
  model {
    author = person "Regulatory author" "Writes the record; renders and checks the regulatory documents"
    engineer = person "Engineer" "Changes the product and its tagged acceptance tests"
    reviewer = person "Reviewer" "Approves each change by reviewing its pull request"
    rdm_system = softwareSystem "RDM" "Keeps the design record of regulated software, gates it, renders it and builds it into a read-only graph" {
      rdm_cli = container "rdm" "The command line: gates, rendering, graph build, query and validate, and the agent server" "Python" {
        group "gap_analysis" {
          gap_analysis = component "Gap analysis" "Documents against checklists" "Python" {
            properties {
              "code" "rdm/gaps.py"
            }
            tags "context:gap_analysis"
          }
          checklists = component "Checklists" "The built-in checklists" "text" {
            properties {
              "code" "rdm/checklists/"
            }
            tags "context:gap_analysis"
          }
        }
        group "gating" {
          design_gate = component "Design and release gates" "Design gate, release gate, duplicate ids, trace" "Python" {
            properties {
              "code" "rdm/gates/design_gate.py"
            }
            tags "context:gating"
          }
          hooks = component "Hooks installer" "rdm hooks" "Python" {
            properties {
              "code" "rdm/hooks.py"
            }
            tags "context:gating"
          }
          precommit_hook = component "Pre-commit hook" "Runs the design gate before a commit" "shell" {
            properties {
              "code" "rdm/hook_files/"
            }
            tags "context:gating"
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
        group "ingestion" {
          snippets = component "Code snippets" "Collects tagged code snippets" "Python" {
            properties {
              "code" "rdm/collect.py"
            }
            tags "context:ingestion"
          }
          test_translation = component "Test result translation" "Translates foreign test results" "Python" {
            properties {
              "code" "rdm/translate.py"
            }
            tags "context:ingestion"
          }
          result_formatters = component "Result formatters" "JUnit and other result formats" "Python" {
            properties {
              "code" "rdm/test_formatters/"
            }
            tags "context:ingestion"
          }
        }
        group "record" {
          record_readers = component "Record readers" "Design, V&V and risk frontmatter, Allure results, the C4 model, git" "Python" {
            properties {
              "code" "rdm/record/"
            }
            tags "context:record"
          }
          dmr_index = component "DMR index" "The device-master-record index from frontmatter" "Python" {
            properties {
              "code" "rdm/record/dmr.py"
            }
            tags "context:record"
          }
        }
        group "rendering" {
          utilities = component "Utilities" "Shared YAML and file helpers" "Python" {
            properties {
              "code" "rdm/util.py"
            }
            tags "context:rendering"
          }
          renderer = component "Renderer" "Templates and data to Markdown" "Python, Jinja2" {
            properties {
              "code" "rdm/render.py"
            }
            tags "context:rendering"
          }
          markdown_extensions = component "Markdown extensions" "Section numbers, vocabulary, audit notes" "Python" {
            properties {
              "code" "rdm/md_extensions/"
            }
            tags "context:rendering"
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
        group "scaffolding" {
          project_scaffold = component "Project scaffold" "rdm init" "Python" {
            properties {
              "code" "rdm/init.py"
            }
            tags "context:scaffolding"
          }
          project_templates = component "Project templates" "What rdm init lays down" "Markdown, YAML, Typst" {
            properties {
              "code" "rdm/init_files/"
            }
            tags "context:scaffolding"
          }
          adoption = component "Adoption" "rdm adopt" "Python" {
            properties {
              "code" "rdm/adopt.py"
            }
            tags "context:scaffolding"
          }
          adoption_templates = component "Adoption templates" "What rdm adopt lays down" "Markdown, YAML" {
            properties {
              "code" "rdm/adopt_files/"
            }
            tags "context:scaffolding"
          }
          new_input = component "New design input" "rdm story new-input" "Python" {
            properties {
              "code" "rdm/gates/new_input.py"
            }
            tags "context:scaffolding"
          }
        }
        group "validation" {
          validation_records = component "Validation records" "Approved validation records per user need" "Python" {
            properties {
              "code" "rdm/record/validation.py"
            }
            tags "context:validation"
          }
          formative_usability = component "Formative usability" "Persona runs as formative evidence" "Python" {
            properties {
              "code" "rdm/record/persona.py"
            }
            tags "context:validation"
          }
          persona_command = component "Persona command" "rdm story persona" "Python" {
            properties {
              "code" "rdm/record/persona_cmd.py"
            }
            tags "context:validation"
          }
        }
        group "verification" {
          verification_data = component "Verification data" "Design inputs against results: verification.yml" "Python" {
            properties {
              "code" "rdm/record/verify.py"
            }
            tags "context:verification"
          }
          mutation_probe = component "Mutation probe" "Breaks a line, runs one test, restores" "Python" {
            properties {
              "code" "rdm/gates/mutation.py"
            }
            tags "context:verification"
          }
          evidence_bundle = component "Evidence bundle" "The retained release evidence" "Python" {
            properties {
              "code" "rdm/record/bundle.py"
            }
            tags "context:verification"
          }
          verification_report = component "Verification report" "The PDF of every run behind each design input" "Python" {
            properties {
              "code" "rdm/record/report.py"
            }
            tags "context:verification"
          }
          report_layout = component "Report layout" "The report's page layout" "Typst" {
            properties {
              "code" "rdm/record/verification_report.typ"
            }
            tags "context:verification"
          }
        }
      }
      test_run = container "Acceptance test run" "Runs the tagged tests; RDM's plugin labels each run from the record" "pytest, allure-pytest" {
        group "verification" {
          pytest_plugin = component "pytest plugin" "Labels each run from the record; the run's executor and environment" "Python, pytest" {
            properties {
              "code" "rdm/pytest_plugin.py"
            }
            tags "context:verification"
          }
        }
      }
      gates_ci = container "Reusable gates" "The workflow and actions other repositories call" "GitHub Actions" {
        group "scaffolding" {
          reusable_workflow = component "Reusable workflow" "Tests, then the gates, for any repository" "GitHub Actions" {
            properties {
              "code" ".github/workflows/gates.yml"
            }
            tags "context:scaffolding"
          }
          gates_action = component "Gates action" "The gates as steps" "GitHub Actions" {
            properties {
              "code" "actions/gates/"
            }
            tags "context:scaffolding"
          }
          pdf_action = component "PDF action" "Renders the documents in the image" "GitHub Actions" {
            properties {
              "code" "action.yml"
            }
            tags "context:scaffolding"
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
  }
  views {
    systemContext rdm_system "C1" {
      include *
      include reviewer
      autolayout tb
    }
    container rdm_system "C2" {
      include *
      autolayout tb
    }
    component rdm_cli "C3_gap_analysis" {
      include gap_analysis checklists
      autolayout tb
    }
    component rdm_cli "C3_gating" {
      include design_gate hooks precommit_hook record_readers risk_register utilities validation_records
      autolayout tb
    }
    component rdm_cli "C3_graph" {
      include projection vocabulary gate_shapes shacl_validation explorer_file agent_server gap_analysis record_readers risk_register
      autolayout tb
    }
    component rdm_cli "C3_ingestion" {
      include snippets test_translation result_formatters utilities
      autolayout tb
    }
    component rdm_cli "C3_record" {
      include record_readers dmr_index
      autolayout tb
    }
    component rdm_cli "C3_rendering" {
      include utilities renderer markdown_extensions
      autolayout tb
    }
    component rdm_cli "C3_risk" {
      include risk_register record_readers
      autolayout tb
    }
    component rdm_cli "C3_scaffolding" {
      include project_scaffold project_templates adoption adoption_templates new_input reusable_workflow gates_action pdf_action record_readers
      autolayout tb
    }
    component rdm_cli "C3_validation" {
      include validation_records formative_usability persona_command record_readers
      autolayout tb
    }
    component rdm_cli "C3_verification" {
      include verification_data mutation_probe evidence_bundle verification_report report_layout pytest_plugin record_readers renderer risk_register utilities
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
