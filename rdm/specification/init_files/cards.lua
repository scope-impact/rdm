-- Pandoc Lua filter: the record's Markdown decorated for template.typ.
--
-- The Markdown stays the source: render filters mark its structure with
-- Pandoc's own attributes, and this filter turns each mark into a call of a
-- function template.typ defines. A Markdown reader, the docs site and any
-- other output see the same text without the decoration.
--
--   ::: {.risk-card level="High"} ... :::      #risk-card(level: "High")[...]
--   ::: {.scenario-card status="verified"}     #scenario-card(status: "verified")[...]
--   ::: {.rule-line title="..."} :::           #rule-line(title: "...")[]
--   ::: {.steps} [Given]{.kw} text ... :::     #steps(("Given", [text]), ...)
--   [High]{.level}  [proposed]{.tag}  [item-01-001]{.id}
--   [✓ verified]{.verified}  [✗ failed]{.failed}  [untested]{.untested}
--   [Covers: ...]{.covers}                     #chip("level", "High") ...

local CALLS = { ["risk-card"] = true, ["scenario-card"] = true, ["rule-line"] = true }
local CHIPS = { level = true, tag = true, id = true, verified = true, failed = true, untested = true, covers = true }

local function typst_string(s)
  return '"' .. s:gsub('\\', '\\\\'):gsub('"', '\\"') .. '"'
end

local function typst_of(inlines)
  return (pandoc.write(pandoc.Pandoc({ pandoc.Plain(inlines) }), "typst"):gsub("%s+$", ""))
end

local function arguments(attr)
  local parts = {}
  for key, value in pairs(attr.attributes) do
    table.insert(parts, key .. ": " .. typst_string(value))
  end
  table.sort(parts)
  return table.concat(parts, ", ")
end

local function steps(div)
  local rows, line = {}, {}
  local function flush()
    if #line > 0 then
      local keyword = ""
      if line[1].t == "Span" and line[1].classes:includes("kw") then
        keyword = pandoc.utils.stringify(line[1])
        table.remove(line, 1)
      end
      while #line > 0 and line[1].t == "Space" do table.remove(line, 1) end
      table.insert(rows, "(" .. typst_string(keyword) .. ", [" .. typst_of(line) .. "])")
    end
    line = {}
  end
  for _, block in ipairs(div.content) do
    if block.t == "Para" or block.t == "Plain" then
      for _, inline in ipairs(block.content) do
        if inline.t == "LineBreak" or inline.t == "SoftBreak" then flush() else table.insert(line, inline) end
      end
      flush()
    end
  end
  return pandoc.RawBlock("typst", "#steps(" .. table.concat(rows, ", ") .. ")")
end

function Div(div)
  if div.classes:includes("steps") then
    return steps(div)
  end
  for _, class in ipairs(div.classes) do
    if CALLS[class] then
      local blocks = { pandoc.RawBlock("typst", "#" .. class .. "(" .. arguments(div.attr) .. ")[") }
      for _, block in ipairs(div.content) do table.insert(blocks, block) end
      table.insert(blocks, pandoc.RawBlock("typst", "]"))
      return blocks
    end
  end
end

function Span(span)
  for _, class in ipairs(span.classes) do
    if CHIPS[class] then
      return pandoc.RawInline("typst", "#chip(" .. typst_string(class) .. ", " ..
        typst_string(pandoc.utils.stringify(span.content)) .. ")")
    end
  end
end
