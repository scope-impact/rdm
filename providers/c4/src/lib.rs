//! RDM's c4 provider (DI-84): one implementation, built as the native program
//! `rdm-c4` for the command line and, with the `component` feature, as the WASI
//! component that exports `rdm:component/c4`. The workspace is parsed, exported
//! and drawn by structurizrx: no Java, no Graphviz.
use std::collections::HashMap;

pub struct View {
    pub key: String,
    pub svg: String,
}

pub struct Drawing {
    /// The exported model, as JSON text, each element carrying its DSL identifier.
    pub workspace_json: String,
    /// Every view the renderer draws; a view it cannot draw (a dynamic view) is left out.
    pub views: Vec<View>,
}

/// Export and draw the workspace from its DSL text (every include inlined).
pub fn draw(dsl: &str) -> Result<Drawing, String> {
    let (mut workspace, register) =
        structurizr_dsl::parse_str_with_identifiers(dsl).map_err(|e| e.to_string())?;
    // Structurizr writes each element's DSL identifier as a property; RDM keys the model by it.
    let ids: HashMap<String, String> =
        register.identifiers.iter().map(|(name, (id, _))| (id.clone(), name.clone())).collect();
    let mut json = serde_json::to_value(&workspace).map_err(|e| e.to_string())?;
    if let Some(model) = json.get_mut("model") {
        name_elements(model, &ids);
    }
    structurizr_query::generate_views(&mut workspace).map_err(|e| format!("views: {e}"))?;
    use structurizr_renderer::exporter::DiagramExporter;
    let views = structurizr_renderer::svg::SvgExporter
        .export_workspace(&workspace)
        .into_iter()
        .map(|d| View { key: d.key, svg: d.content })
        .collect();
    Ok(Drawing { workspace_json: serde_json::to_string_pretty(&json).map_err(|e| e.to_string())?, views })
}

fn name_elements(value: &mut serde_json::Value, ids: &HashMap<String, String>) {
    match value {
        serde_json::Value::Object(map) => {
            let is_element = map.contains_key("name") && map.contains_key("tags");
            if let (true, Some(name)) = (is_element, map.get("id").and_then(|i| i.as_str()).and_then(|i| ids.get(i))) {
                let props = map.entry("properties").or_insert_with(|| serde_json::json!({}));
                props["structurizr.dsl.identifier"] = serde_json::Value::String(name.clone());
            }
            map.values_mut().for_each(|v| name_elements(v, ids));
        }
        serde_json::Value::Array(items) => items.iter_mut().for_each(|v| name_elements(v, ids)),
        _ => {}
    }
}

#[cfg(feature = "component")]
mod component {
    wit_bindgen::generate!({ path: "../../wit", world: "rdm:component/draw" });
    use exports::rdm::component::c4::{Drawing, Guest, View};

    struct C4;

    impl Guest for C4 {
        fn draw(dsl: String) -> Result<Drawing, String> {
            let drawn = crate::draw(&dsl)?;
            Ok(Drawing {
                workspace_json: drawn.workspace_json,
                views: drawn.views.into_iter().map(|v| View { key: v.key, svg: v.svg }).collect(),
            })
        }
    }

    export!(C4);
}
