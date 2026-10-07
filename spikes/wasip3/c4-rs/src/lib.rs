// Spike: rdm-c4, a library component exporting rdm:component/c4 (../wit/c4.wit):
// the C4 workspace parsed, exported and drawn by structurizrx, in place of the
// Structurizr CLI and Graphviz in `rdm c4 draw`. No Java, no Graphviz.
use std::collections::HashMap;

use exports::rdm::component::c4::{Drawing, Guest, View};
use structurizr_renderer::{exporter::DiagramExporter, svg::SvgExporter};

wit_bindgen::generate!({ path: "../wit", world: "rdm:component/draw" });

struct C4;

impl Guest for C4 {
    fn draw(dsl: String) -> Result<Drawing, String> {
        let (mut workspace, register) =
            structurizr_dsl::parse_file_with_identifiers(&dsl).map_err(|e| format!("{dsl}: {e}"))?;
        // Structurizr writes each element's DSL identifier as a property; RDM keys the model by it.
        let ids: HashMap<String, String> =
            register.identifiers.iter().map(|(name, (id, _))| (id.clone(), name.clone())).collect();
        let mut json = serde_json::to_value(&workspace).map_err(|e| e.to_string())?;
        if let Some(model) = json.get_mut("model") {
            name_elements(model, &ids);
        }
        structurizr_query::generate_views(&mut workspace).map_err(|e| format!("{dsl}: views: {e}"))?;
        let views = SvgExporter
            .export_workspace(&workspace)
            .into_iter()
            .map(|d| View { key: d.key, svg: d.content })
            .collect();
        Ok(Drawing { workspace_json: serde_json::to_string_pretty(&json).map_err(|e| e.to_string())?, views })
    }
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

export!(C4);
