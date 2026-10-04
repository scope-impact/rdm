// Spike: `rdm c4 draw` without Java, through structurizrx (a Rust Structurizr):
// workspace.dsl -> workspace.json (with the DSL identifiers RDM keys the model by)
// and one DOT and SVG per view.
//   c4x <workspace.dsl> <out-dir>
use anyhow::{anyhow, Context, Result};
use std::path::PathBuf;
use structurizr_renderer::{dot::DotExporter, exporter::DiagramExporter, svg::SvgExporter};

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (dsl, out) = (PathBuf::from(&args[0]), PathBuf::from(&args[1]));
    let (mut workspace, register) =
        structurizr_dsl::parse_file_with_identifiers(&dsl).with_context(|| format!("parse {}", dsl.display()))?;
    std::fs::create_dir_all(out.join("views"))?;
    // Structurizr writes each element's DSL identifier as a property; RDM keys the model by it.
    let ids: std::collections::HashMap<String, String> =
        register.identifiers.iter().map(|(name, (id, _))| (id.clone(), name.clone())).collect();
    let mut json = serde_json::to_value(&workspace)?;
    if let Some(model) = json.get_mut("model") {
        name_elements(model, &ids);
    }
    std::fs::write(out.join("workspace.json"), serde_json::to_string_pretty(&json)?)?;
    structurizr_query::generate_views(&mut workspace).map_err(|e| anyhow!("views: {e}"))?;
    for exporter in [&DotExporter as &dyn DiagramExporter, &SvgExporter] {
        for d in exporter.export_workspace(&workspace) {
            std::fs::write(out.join("views").join(format!("{}.{}", d.key, d.extension())), &d.content)?;
        }
    }
    println!("{} views", std::fs::read_dir(out.join("views"))?.count());
    Ok(())
}

fn name_elements(value: &mut serde_json::Value, ids: &std::collections::HashMap<String, String>) {
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
