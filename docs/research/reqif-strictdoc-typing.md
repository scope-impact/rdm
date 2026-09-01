# How ReqIF and StrictDoc express types as data

Research note for **RDM-004.05**. Answers three questions about the two
reference implementations of "the item type is a row, not a class":

1. ReqIF — how `SpecType` / `AttributeDefinition` / `DatatypeDefinition` work,
   and who (if anyone) validates an instance against its type.
2. StrictDoc — how a per-document *grammar* is declared, what it enforces, what
   happens when it changes, and how it maps onto ReqIF.
3. Both — what a query for "all risks with severity Critical" actually costs
   when the type is data.

**No rdm design is proposed here.** That is RDM-004.06. This note only
establishes what the two systems do and what it costs them.

## Where this file lives

`docs/` in this repo is the published MkDocs site: every `*.md` under it is
listed in `mkdocs.yml`'s `nav` (checked — there are currently no exceptions), and
there is no existing convention for research notes, working notes or ADR-style
scratch material. `docs/adr-0001-…md` is a decision record, not a research note,
and it is in the nav.

So there was no convention to match. I put this at
`docs/research/reqif-strictdoc-typing.md`, the path requested when the work was handed over,
**deliberately not added to `mkdocs.yml`'s nav** — it is input to a decision, not
a page for users of rdm. MkDocs reports not-in-nav pages at INFO level, so
`mkdocs build --strict` is unaffected. If this should become a published page,
it needs a `nav` entry (probably under *Concepts*).

## Sources and method

Primary sources only, and each claim below is tagged with the source that owns
it.

| Tag | Source | Why it is primary |
| --- | --- | --- |
| `[XSD]` | `reqif.xsd`, `targetNamespace="http://www.omg.org/spec/ReqIF/20110401/reqif.xsd"` | The normative XML Schema published with the OMG ReqIF specification, redistributed verbatim in the `reqif` PyPI package at `reqif/reqif_schema/reqif.xsd` (892 lines). ReqIF 1.0.1/1.1/1.2 share one schema and model; only the spec prose changed between them. |
| `[ECORE]` | `org.eclipse.rmf.reqif10/model/reqif10.ecore` in `github.com/eclipse-rmf/org.eclipse.rmf` | The ReqIF metamodel in Eclipse RMF, the ReqIF reference implementation. |
| `[RMF]` | Other files in the same repo, notably `org.eclipse.rmf.reqif10.constraints/` and `org.eclipse.rmf.reqif10.common/src/org/eclipse/rmf/reqif10/common/util/ReqIF10Util.java` | RMF's own validation and attribute-access code. |
| `[REQIF-LIB]` | `reqif` 0.1.0 (PyPI), the ReqIF library by the StrictDoc project | The implementation behind `reqif validate`. |
| `[SD]` | `strictdoc` 0.28.3 (PyPI sdist) | StrictDoc's own source. |
| `[SD-DOC]` | `docs/*.sdoc` in `github.com/strictdoc-project/strictdoc` @ `main` | StrictDoc's own user guide and its own requirements, in its own format. |
| `[EXP]` | Experiments run locally against `strictdoc` 0.28.3 and `reqif` 0.1.0 | Commands and outputs are reproduced in [Reproducing the experiments](#reproducing-the-experiments). |

### One gap, stated up front

**`www.omg.org` is blocked by this environment's egress proxy**, as are the two
other hosts that carry copies of the specification document
(`docs.omg.org`, and a mirror of the ReqIF 1.2 short version). I therefore could
**not** read the ReqIF specification *prose* — only its normative machine-readable
schema `[XSD]`, the reference implementation's metamodel `[ECORE]` and the
implementations `[RMF]`, `[REQIF-LIB]`.

Consequences, honestly:

- Claims about **what the ReqIF model is** and **what the schema constrains** are
  solid: they come from the normative XSD and the reference metamodel.
- The claim "the spec does not *mandate* instance/type validation" is **argued,
  not quoted**: the normative schema cannot express the constraint (shown below),
  the reference metamodel declares no invariant that would (shown below), and the
  reference implementation implements no such check (shown below). If a MUST
  exists in the spec prose, every artefact that would carry it is silent. Treat
  this as high-confidence inference, and re-check §§ of `formal/2016-07-01` when
  omg.org is reachable.
- Constraint IDs in RMF (`C1.1`…`C1.9`) suggest a numbered constraint catalogue
  in the ReqIF *Implementation Guide* (a separate document, published by the
  ProSTEP iViP / OMG ReqIF group) that I could not read either. **This is the
  most likely place where cross-checking rules are written down**, and it is the
  first thing to read next. See [Open questions](#open-questions).

## 1. ReqIF

### 1.1 The model is four layers, and only two of them are types

`[XSD]` `[ECORE]`

```
DatatypeDefinition*        ← value domain, owned by REQ-IF-CONTENT/DATATYPES, shared
      ↑ .type (reference)
AttributeDefinition*       ← "this type has a field", CONTAINED in exactly one SpecType
      ↑ (containment)
SpecType                   ← abstract; SpecObjectType | SpecificationType |
                             SpecRelationType | RelationGroupType
      ↑ .type (reference)
SpecObject / Specification / SpecRelation / RelationGroup
  = SpecElementWithAttributes, holding .values : AttributeValue[*]
```

Facts worth pinning down, because they constrain any imitation of this design:

- **`SpecType` is abstract with four concrete subclasses** `[ECORE]`. The one
  that types a requirement-like item is `SpecObjectType`; it adds *nothing* to
  `SpecType` — it is an empty subclass whose only content is the inherited
  `specAttributes` `[ECORE]`, `[XSD]` (`SPEC-OBJECT-TYPE` carries only
  `ALTERNATIVE-ID` and `SPEC-ATTRIBUTES`).
- **Attribute definitions are *contained* by their SpecType**
  (`specAttributes … containment="true"` `[ECORE]`). Two types therefore
  **cannot share an attribute definition**. If `RISK` and `TASK` both have a
  `STATUS` field, that is two `AttributeDefinition` objects with two identifiers.
- **Datatype definitions are shared** — contained by `REQ-IF-CONTENT/DATATYPES`
  `[XSD]` and merely *referenced* by `AttributeDefinition.type` `[ECORE]`. So the
  reuse unit is the value *domain*, never the field.
- **There are exactly seven datatype kinds**, and the kind is encoded in the
  class name, not in an attribute: `DATATYPE-DEFINITION-{BOOLEAN, DATE,
  ENUMERATION, INTEGER, REAL, STRING, XHTML}` `[XSD]`. Each has a matching
  `ATTRIBUTE-DEFINITION-*` and `ATTRIBUTE-VALUE-*`, i.e. **21 classes to express
  7 types**. RMF's own comment on the resulting reflection code is
  `"(Would be so much easier with inheritance)"` `[RMF]`
  (`ReqIF10Util.getDatatypeDefinition`).
- **`AttributeDefinition` has no "required" flag.** Its full attribute list in
  the schema is `DESC`, `IDENTIFIER`, `IS-EDITABLE`, `LAST-CHANGE`, `LONG-NAME`,
  plus `MULTI-VALUED` on the enumeration variant only, plus an optional
  `DEFAULT-VALUE` element `[XSD]`. `[ECORE]` agrees. **Mandatory-ness is not
  expressible in ReqIF at all.** This is the single largest gap versus a Pydantic
  class or a StrictDoc grammar.

### 1.2 Declaring datatypes and enumerations

`[XSD]`

Scalar datatypes carry their constraints as **required** XML attributes:

- `DATATYPE-DEFINITION-STRING` — `MAX-LENGTH` (`xsd:integer`, `use="required"`).
- `DATATYPE-DEFINITION-INTEGER` — `MIN`, `MAX` (both required).
- `DATATYPE-DEFINITION-REAL` — `MIN`, `MAX`, `ACCURACY` (all required).
- `BOOLEAN`, `DATE`, `XHTML` — no constraint attributes.

So every string field in every ReqIF file **must** declare a maximum length, and
nothing ever checks it. StrictDoc's exporter simply invents one:
`REQIF_SINGLELINE_MAX_LENGTH = "10000"` for both its single- and multi-line string
datatypes `[SD]` (`backend/reqif/p01_sdoc/sdoc_to_reqif_converter.py`).

Enumerations are a **first-class object with per-value identity**:

```xml
<DATATYPE-DEFINITION-ENUMERATION IDENTIFIER="…" LAST-CHANGE="…">
  <SPECIFIED-VALUES>
    <ENUM-VALUE IDENTIFIER="ENUM-VALUE-a451cbdb-…" LAST-CHANGE="…" LONG-NAME="Critical">
      <PROPERTIES><EMBEDDED-VALUE KEY="3" OTHER-CONTENT=""/></PROPERTIES>
    </ENUM-VALUE>
  </SPECIFIED-VALUES>
</DATATYPE-DEFINITION-ENUMERATION>
```

(real output from `[EXP]`.) Note:

- The **human label is `LONG-NAME`; the identity is the `IDENTIFIER`** — an opaque
  id. Nothing in the file constrains `LONG-NAME` to be unique within the
  enumeration.
- `ENUM-VALUE/PROPERTIES/EMBEDDED-VALUE` is **required** by the schema
  (`minOccurs="1"` twice over), and `EMBEDDED-VALUE` requires both `KEY`
  (`xsd:integer`) and `OTHER-CONTENT` (`xsd:string`) `[XSD]`. `[ECORE]` agrees
  (`properties … lowerBound="1"`, `key`/`otherContent` both `lowerBound="1"`).
  StrictDoc's exporter emits `OTHER-CONTENT=""` with the comment *"ReqIF XML
  validator wants OTHER-CONTENT to be present, even if empty"* `[SD]` and uses
  the option's ordinal as `KEY`. This corner is where the schema is stricter than
  practice, and everyone works around it with an empty string.

### 1.3 How an instance's values bind to their definitions

`[XSD]` An `ATTRIBUTE-VALUE-*` carries a **mandatory** `DEFINITION` element
holding one `ATTRIBUTE-DEFINITION-<KIND>-REF`, and the payload:

- scalars: `THE-VALUE` XML attribute, typed (`xsd:boolean`, `xsd:dateTime`,
  `xsd:integer`, `xsd:double`, `xsd:string`);
- XHTML: `THE-VALUE` *element*, plus optional `THE-ORIGINAL-VALUE`;
- enumeration: `VALUES` containing zero or more `ENUM-VALUE-REF`.

Three structural consequences:

1. **The binding is by identifier, never by name.** `[REQIF-LIB]` models this
   exactly: `ReqIFSpecObject.attribute_map: Dict[str, SpecObjectAttribute]` keyed
   by `attribute.definition_ref`, and `ReqIFSpecObjectType.attribute_map` keyed
   by definition identifier. There is no name-keyed access anywhere in the
   library.
2. **Absence is the normal encoding of "no value".** `SPEC-OBJECT/VALUES` is
   `minOccurs="0"`, and the values are an unbounded `xsd:choice` `[XSD]`. So a
   spec object may legally carry no values at all, or two values for the same
   definition. The schema cannot say otherwise. `[REQIF-LIB]`'s parser also notes
   that real tools omit `<VALUES>` entirely on an enumeration value to mean
   "nothing selected".
3. **Multi-valued is a property of the definition, not the value**
   (`MULTI-VALUED` on `ATTRIBUTE-DEFINITION-ENUMERATION`), and only enumerations
   can be multi-valued.

### 1.4 Does anyone validate an instance against its type?

This is the question the ticket most needs answered, so it is answered
experimentally.

**What the schema can reach.** `IDENTIFIER` is `xsd:ID`; every `*-REF` is
`LOCAL-REF`, a restriction of **`xsd:IDREF`**; `GLOBAL-REF` is a plain
`xsd:string` `[XSD]`. There are **no `xsd:key` / `xsd:keyref` / `xsd:unique`
declarations in the entire schema**. Therefore an XML validator gives you exactly
two things: document-wide uniqueness of identifiers, and *existence* of every
local reference. It cannot tell you **what kind of thing** a reference points at,
because `ID`/`IDREF` is untyped.

`GLOBAL-REF` appears in exactly four places, and they are worth naming: the two
`SPECIFICATION-REF`s of a `RELATION-GROUP`, and — more importantly — the
`SPEC-OBJECT-REF` of a `SPEC-RELATION`'s `SOURCE` and `TARGET` `[XSD]`. So
**traceability links get no schema-level integrity check at all**: a relation may
name a source or target that does not exist and remain schema-valid. That is
precisely why `[REQIF-LIB]`'s validator hand-codes those two checks (§1.4) —
they are the only integrity rules it could not get from the schema.

**What the metamodel adds, and where it is lost.** `[ECORE]` types the reference
properly: `AttributeValueEnumeration.definition` has
`eType="#//AttributeDefinitionEnumeration"`, `lowerBound="1"`. So *in memory*,
under EMF, you cannot point an enumeration value at a string definition. On the
wire that guarantee evaporates into an untyped `IDREF`. This is the crux: **ReqIF
is kind-safe as an object model and kind-unsafe as an interchange file.**

**What the metamodel does not add at all.** `reqif10.ecore` declares **zero**
constraints or invariants — no `constraints=` annotation and no `eOperations`
anywhere in the file `[ECORE]`. Consequently EMF generated no validator for it
(`org.eclipse.rmf.reqif10/src/…/util/` contains only `ReqIF10AdapterFactory` and
`ReqIF10Switch` — there is no `ReqIF10Validator`) `[RMF]`. Nothing in the
normative model says a value's definition must belong to the spec object's type.

**What the reference implementation checks.** `org.eclipse.rmf.reqif10.constraints`
— the plugin whose entire purpose is ReqIF constraint checking — contains
**one** constraint class, `IdentifiableLongNameExistenceConstraint`, registered
**nine** times (`C1.1`…`C1.9`) against `EnumValue`, the seven
`AttributeDefinition*` classes and `RelationGroup` `[RMF]`. Every one of them
checks the same thing: *does this element have a non-empty `LONG-NAME`*. That is
the whole of ReqIF constraint validation in the reference implementation.

**What `reqif validate` checks.** `[REQIF-LIB]`
(`reqif/commands/validate/validate.py`):

- XSD validation runs **only** with `--use-reqif-schema`, and the CLI prints a
  note saying so by default;
- its own "semantic" checks are: XML declaration present, encoding is UTF-8,
  `SpecRelation.SOURCE`/`TARGET` resolve to existing spec objects, and
  `SpecHierarchy` spec objects exist.

There is **no** check that a spec object's values match its spec object type.

**Experiment `[EXP]`.** Starting from a valid StrictDoc-generated file, I made
four one-line mutations and ran `reqif validate --use-reqif-schema` on each:

| Mutation | Result |
| --- | --- |
| (a) `SEVERITY`'s value `DEFINITION` re-pointed at an `AttributeDefinitionString` **belonging to a different SpecObjectType** (`TEXT_…_ReqIF.ForeignID`), still inside an `ATTRIBUTE-DEFINITION-ENUMERATION-REF` element | **0 errors, 0 schema issues, 0 semantic issues** |
| (b) the whole value for `SEVERITY` deleted, though the source grammar had `REQUIRED: True` | **0 / 0 / 0** |
| (c) `ENUM-VALUE-REF` replaced with an enum value belonging to a **different enumeration datatype** (a `MITIGATIONS` option used as a `SEVERITY` value) | **0 / 0 / 0** |
| (d) a definition reference pointed at an identifier that **does not exist** | **1 schema issue** — but only with `--use-reqif-schema`; the default run reports 0 |

So: the wrong type, the wrong enumeration, and a missing mandatory field all pass
full normative-schema validation plus the tooling's semantic checks. Only a
dangling reference is caught, and only in strict mode.

**What a consumer does instead.** Feeding mutations (a) and (c) to StrictDoc's
ReqIF importer `[EXP]`:

```
error: 'TEXT_cd696348e31b49a5b35ffc3af3a8b6da_ReqIF.ForeignID'
error source: …/backend/reqif/p01_sdoc/reqif_to_sdoc_converter.py:416, function: create_requirement_from_spec_object()
error: 'ENUM-VALUE-603fe8da-…'
error source: …/reqif_to_sdoc_converter.py:455, function: create_requirement_from_spec_object()
```

Both violations *are* detected — as a bare Python `KeyError` surfaced with a
file and line number. The type violation is discovered by the consumer, at the
moment it dereferences the id, as a crash rather than a diagnostic.

### 1.5 What ReqIF pays

- No mandatory-ness, so "this type requires a severity" cannot be stated, let
  alone enforced. Whatever a project means by a required field lives outside the
  file.
- No type-conformance checking anywhere in the normative artefacts or the
  reference implementation; conformance is a convention between producer and
  consumer, discovered at dereference time.
- 21 classes for 7 datatypes, forcing reflective field access in the reference
  implementation, by its own admission.
- Every reference is an opaque id, so every human-meaningful question needs a
  name→id resolution step first (see §3).

## 2. StrictDoc

### 2.1 The grammar is declared inside the document

`[SD]` The grammar of the grammar is a textX grammar,
`backend/sdoc/grammar/grammar_grammar.py`. Reduced to its shape:

```
[GRAMMAR]
ELEMENTS:                              (or:  IMPORT_FROM_FILE: <path>)
- TAG: <RequirementType>
  PROPERTIES:                          (optional)
    IS_COMPOSITE: True|False
    PREFIX: …
    VIEW_STYLE: Plain|Simple|Inline|Narrative|Table|Zebra
  FIELDS:
  - TITLE: <FieldName>
    HUMAN_TITLE: …                     (optional)
    TYPE: String | SingleChoice(a, b, c) | MultipleChoice(a, b, c) | Tag
    REQUIRED: True|False
  RELATIONS:                           (optional)
  - TYPE: Parent|Child|File
    ROLE: …
    REVERSE_ROLE: …
```

So the type system is:

- **Four field types only** — `String`, `SingleChoice`, `MultipleChoice`, `Tag`
  `[SD]` (`RequirementFieldType` in `backend/sdoc/models/grammar_element.py`),
  documented as such `[SD-DOC]` (*Supported field types*). **No integer, real,
  date or boolean.** Everything is text at the bottom.
- **Enumerations are inline in the field declaration**:
  `TYPE: SingleChoice(A, B, C, D)`. There is no separate, shareable, identified
  datatype object and no per-option identity — an option is a bare string
  `[SD]`. Renaming an option is therefore a value-level rewrite, not an
  identifier-preserving edit.
- **`REQUIRED: True|False` per field** — the thing ReqIF cannot express.
- **Relations are part of the type**, with an optional role and reverse role.
- **Field order is part of the type** (see below).

Grammar reuse, all per-document rather than per-project `[SD-DOC]`:

- `IMPORT_FROM_FILE: grammar.sgra` — a `.sgra` file containing an ordinary
  `[GRAMMAR]` block, which "becomes the document grammar as if it was declared
  directly in the document";
- `IMPORT_FROM_FILE: @my_grammar` — an alias registered in
  `strictdoc_config.py`'s `grammars={…}` map, for sharing across directories;
- the newer Markdown surface uses `**Grammar**: requirements.gra.md`, with the
  same four field types `[SD]` (`backend/markdown/grammar_reader.py`).

The user guide's own advice: *"For anything beyond a small project, it's best to
define a document grammar early… Starting with your own grammar saves time
later."* `[SD-DOC]`

### 2.2 What StrictDoc actually enforces

`[SD]` All of it lives in `backend/sdoc/validations/sdoc_validator.py` (401
lines) and runs on **every parse** of a document. Verified empirically `[EXP]` —
error text quoted from real runs:

| Rule | Enforced? | Evidence |
| --- | --- | --- |
| Node's `TAG` is a registered grammar element | yes | `unknown_node_type` |
| Every field on the node is declared in the grammar | yes | `unregistered_field` |
| Every `REQUIRED: True` field is present | yes `[EXP]` | `Semantic error: Node is missing a field that is required by grammar: SEVERITY.` / `Hint: Node fields: [UID, TITLE, STATEMENT], grammar fields: [UID, SEVERITY, MITIGATIONS, TITLE, STATEMENT].` |
| **Field order matches the grammar's declaration order** | yes `[EXP]` | `Semantic error: Wrong field order for requirement: [SEVERITY, UID, MITIGATIONS, TITLE, STATEMENT].` Documented: *"The order of fields in each document node must match the order of their declaration in the grammar."* `[SD-DOC]` |
| `SingleChoice` value ∈ options | yes `[EXP]` | `Semantic error: Requirement field has an invalid SingleChoice value: Catastrophic.` |
| `MultipleChoice` — comma-separated, each ∈ options | yes | regex `^[a-zA-Z0-9/\-|_ ]+(, …)*$` then per-component membership |
| `Tag` — comma-separated shape only, **no option list** | shape only | `not_comma_separated_tag_field` |
| Choice/tag fields must be single-line | yes | `choice_field_cannot_be_multiline` |
| Relations: `(type, role)` declared on the element | yes | `invalid_reference_type_item` |
| `MID` field present when `ENABLE_MID` | yes | `grammar_element_has_no_mid_field` |
| `String` fields | **nothing at all** | no length, pattern, or format check exists |

Two things stand out.

**Field order is type-level.** The validator walks the grammar's field iterator
and the node's field iterator in lockstep, so the grammar fixes the *sequence*
of fields, not just the set. That buys canonical, diffable documents; it costs
every reordering being a breaking change to every node.

**`TBD` / `TBC` bypass every enumeration.** In `validate_requirement_field`, both
the `SingleChoice` and `MultipleChoice` membership checks are
`value not in grammar_field.options and value not in ("TBD", "TBC")` — two
hardcoded magic strings that are legal members of every enumeration in every
project `[SD]`, verified `[EXP]` (`SEVERITY: TBD` exports cleanly). Pragmatic for
authoring in-progress documents; it means a choice field is not a closed domain.

### 2.3 What happens when a grammar changes

There are two paths and they behave completely differently `[SD]`.

**Path 1 — the file is edited by hand.** Nothing migrates. The document simply
fails to parse with the semantic errors above, e.g. removing a field from the
grammar makes every node carrying it fail with `unregistered_field`. There is no
grammar version, no migration hook, no compatibility window. The blast radius is
one document (or every document sharing a `.sgra`).

**Path 2 — the grammar is edited in the web UI.** `UpdateGrammarElementCommand`
(`core/transforms/update_grammar_element.py`) performs a **destructive in-memory
migration over every node of that type in the document**, in this order:

1. renamed fields are matched **by field `MID`** (a stable per-field id), so
   values follow a rename;
2. the node's `ordered_fields_lookup` is rebuilt **from the new grammar's field
   list**, so any field no longer in the grammar is **silently dropped** — the
   loop is `if previous_field_name not in requirement_field_names: continue`, and
   nothing is logged or reported;
3. fields are reordered into grammar order;
4. relations whose `(ref_type, role)` is no longer registered are **silently
   dropped** too.

And `UpdateGrammarCommand` (whole-grammar edit) replaces the element list, with
any element name not previously present created via
`GrammarElement.create_default(...)`.

So StrictDoc's answer to "the grammar changed" is: *eagerly rewrite every
instance to fit, discarding what no longer fits* — when the edit goes through the
UI — or *refuse to load the document* when it goes through the file. Neither is a
migration story; both are consistent with the grammar being scoped to one
document.

Also relevant: `.sgra` grammars can only be edited in a text editor — the web
editor cannot edit them `[SD-DOC]`. So the shared-grammar case is exactly the
case where the eager migration does not run.

### 2.4 Mapping a grammar onto ReqIF

`[SD]` `backend/reqif/p01_sdoc/sdoc_to_reqif_converter.py`, profile `p01_sdoc`.
The mapping, and what it loses, verified by exporting a real `RISK` grammar
`[EXP]`:

| StrictDoc | ReqIF |
| --- | --- |
| grammar element (`TAG: RISK`) | one `SPEC-OBJECT-TYPE`, `LONG-NAME` = the tag |
| `String`, single-line | `DATATYPE-DEFINITION-STRING` `SDOC_DATATYPE_SINGLE_LINE_STRING`, `MAX-LENGTH="10000"` |
| `String`, multi-line | `DATATYPE-DEFINITION-XHTML` or `-STRING` (`SDOC_DATATYPE_MULTI_LINE_STRING`), depending on the `multiline_is_xhtml` flag |
| `SingleChoice(...)` | `DATATYPE-DEFINITION-ENUMERATION` + `ATTRIBUTE-DEFINITION-ENUMERATION MULTI-VALUED="false"`; options become `ENUM-VALUE`s with `LONG-NAME` = option and `KEY` = ordinal |
| `MultipleChoice(...)` | same, `MULTI-VALUED="true"` |
| `Tag` | **unmapped** — falls through to `raise NotImplementedError(field)`; there is no `GrammarElementFieldTag` reference anywhere under `backend/reqif/` |
| `REQUIRED: True` | **nothing.** ReqIF has no such concept (§1.1); the exporter sets no flag and the information is gone |
| field order | preserved incidentally, as the order of `SPEC-ATTRIBUTES` |
| relation `(type, role)` | one `SPEC-RELATION-TYPE` per distinct pair, named after the role |
| reserved fields | renamed: `UID`→`ReqIF.ForeignID`, `STATEMENT`→`ReqIF.Text`, `TITLE`→`ReqIF.Name` (or `ReqIF.ChapterName` for composite elements), `COMMENT`→`NOTES` (`sdoc_reqif_fields.py`) |
| the document | one `SPECIFICATION`, typed by a single hardcoded `SDOC_SPECIFICATION_TYPE_SINGLETON` carrying no attributes |

Three costs visible in the artefact:

- **Type identifiers are regenerated on every export.**
  `spec_object_type_identifier = element.tag + "_" + uuid.uuid4().hex`, and
  attribute definition ids are derived from it
  (`…_SEVERITY`), as are enum value ids
  (`ENUM-VALUE-<uuid4>`) and spec object ids. Two exports of *identical* input
  differ on **94 of 201 lines** `[EXP]`. Nothing outside the file can hold a
  reference to a StrictDoc-exported type.
- **The datatype registry is keyed by field title, globally across the document
  tree**: `context.data_types_lookup[field.title] = data_type.identifier`, read
  back by `field.title` both when building attribute definitions and when
  resolving a node's value. Two documents each declaring, say, a `SEVERITY`
  `SingleChoice` with different options both write to that one key, and the last
  one wins for every lookup. I did not construct a failing case for this, so
  treat it as a code-reading observation, not a demonstrated bug — but the shape
  is "field names are globally unique", which is not true of per-document
  grammars.
- **Export-time enum resolution is a scan.** For each single-choice field of each
  node, the converter loops over *all* datatypes, then over all of that
  datatype's values, comparing `LONG-NAME` to the node's text, to recover the
  enum value id (`_convert_requirement_to_spec_object`). Same for
  multiple-choice. This is the name→id resolution tax of §3, paid per node per
  field.

## 3. Querying a typed model when the type is data

This is the practical question, and the honest headline is: **neither system has
a good answer, and StrictDoc's is worse than the ticket assumes.**

### 3.1 ReqIF: three joins by opaque id, no query surface at all

ReqIF is an interchange format; it defines no query language, no index and no API
— only the file. So "all risks with severity Critical" against a `.reqif` is a
program you write. Against the real export in `[EXP]` it is:

1. find the `SPEC-OBJECT-TYPE` whose `LONG-NAME` is `RISK` → its `IDENTIFIER`
   (`RISK_ddf78a95b124477993d556783460ede9`);
2. inside it, find the `ATTRIBUTE-DEFINITION-ENUMERATION` whose `LONG-NAME` is
   `SEVERITY` → its `IDENTIFIER`;
3. follow its `DATATYPE-DEFINITION-ENUMERATION-REF` → find the `ENUM-VALUE` whose
   `LONG-NAME` is `Critical` → its `IDENTIFIER`
   (`ENUM-VALUE-a451cbdb-3a02-4934-80d3-691cab479026`);
4. scan `SPEC-OBJECTS` for objects whose `TYPE/SPEC-OBJECT-TYPE-REF` is (1) and
   which contain an `ATTRIBUTE-VALUE-ENUMERATION` whose `DEFINITION` is (2) and
   whose `VALUES` contain (3).

Every human-meaningful term in the question — `RISK`, `SEVERITY`, `Critical` —
is a `LONG-NAME` lookup that must be resolved to an id before any matching can
happen, and none of those names is guaranteed unique by the schema.

What implementations do to make this tractable:

- **`[REQIF-LIB]`** builds four hash maps at parse time and nothing else:
  `ReqIFObjectLookup{data_types_lookup, spec_types_lookup, spec_objects_lookup,
  spec_relations_parent_lookup}`, plus a per-object
  `attribute_map: {definition_ref → value}` and a per-type
  `attribute_map: {definition_id → definition}`. All keyed by **identifier**.
  There is no index by type, by attribute name, or by value; step (4) above is a
  full scan.
- **`[RMF]`**, the reference implementation, offers exactly one convenience for
  the whole problem — `ReqIF10Util.getAttributeValueForLabel(element, label)` —
  and it is two nested linear scans: over `type.getSpecAttributes()` comparing
  `getLongName()` to the label, then over `specElement.getValues()` comparing
  definition identifiers. Per field access. There is no index and no cache.

So the "keep it tractable" answer for ReqIF is: **nobody does.** No views, no
indexes, no denormalised projections — id-keyed hash maps for dereferencing, and
scans for everything else. (RMF does carry a `469356_QueryServices` branch,
which suggests query services were contemplated and never landed on `master`; I
did not read it.)

### 3.2 StrictDoc: a query language that cannot name a type

`[SD]` `[SD-DOC]` StrictDoc *does* have a query language —
`core/query_engine/grammar.py`, another textX grammar, feature `SDOC-SRS-155`
(*"StrictDoc shall provide searching documentation content with queries via a
search bar"*) `[SD-DOC]`. It is used by the web Search screen and by
`strictdoc export --filter-nodes '<query>'`.

The query for our example, verified working `[EXP]`:

```
strictdoc export . --filter-nodes 'node["SEVERITY"] == "Critical"'
```

and it does the right thing: with two `RISK` nodes, only `RISK-1` survives into
the document pages. But the shape of the language matters:

- **Fields are addressed by name as untyped text**: `node["FIELD"]`. Available
  operators are `==`, `!=`, `in` / `not in` (substring), and
  `any/all/none([...]) in …`. Every comparison is a string comparison — there is
  no numeric or date comparison, which follows from there being no numeric or
  date field types.
- **There is no way to name a node type.** The only type predicates are
  `node.is_requirement`, `node.is_section`, `node.is_root`,
  `node.is_source_file*`, and `node.is_requirement` is implemented as
  `isinstance(node, SDocNode) and node.node_type == "REQUIREMENT"` — a
  **hardcoded string** `[SD]` (`query_engine/query_object.py`). Verified `[EXP]`:
  filtering a document of custom `RISK` nodes by `node.is_requirement` removes
  **all** of them. So "all *risks* with severity Critical" is not expressible;
  the best available is "all nodes, of any type, whose `SEVERITY` field equals
  Critical". The per-project type exists in the grammar, in validation, and in
  the ReqIF export — but **not in the query language**.
- **Field names in queries are `/[A-Za-z0-9]+/`** — no underscore. `[EXP]`:
  `node["RISK_LEVEL"] == "x"` fails with `error: Cannot parse filter query.`
  A perfectly legal grammar field can be unqueryable because of its name.
- **The whole feature is behind a flag and disclaimed**: *"this feature has not
  been extensively tested and is hidden behind a feature flag"* `[SD-DOC]`
  (`project_features=["SEARCH"]`).

**How it executes.** A full scan, evaluated per node in Python. The Search screen
loops `for document in document_tree.document_list: for node, _ in
document_iterator.all_content(): …` and calls `QueryObject.evaluate(node)`, which
walks the expression tree with `isinstance` dispatch `[SD]`
(`server/routers/main_router.py`, `query_engine/query_object.py`). `--filter-nodes`
does the same during traceability-index construction
(`core/traceability_index_builder.py`).

**What indexes exist.** StrictDoc keeps an in-memory graph database whose entire
set of buckets is six `[SD]` (`core/constants.py`):

```
MID_TO_NODE, UID_TO_NODE, NODE_TO_PARENT_NODES,
NODE_TO_CHILD_NODES, NODE_TO_INCOMING_LINKS, DOCUMENT_TO_TAGS
```

**No type→nodes bucket. No field→value bucket.** Identity lookup and relation
traversal are O(1); anything type- or attribute-shaped is O(n).
Per-grammar dictionaries do exist for the *type* side —
`DocumentGrammar.registered_elements`, `.elements_by_type`,
`GrammarElement.fields_map` — which is what makes *validation* cheap; they do
nothing for querying instances.

**What is materialised instead.** Two things, neither of them a query index over
attributes:

- `PickleCache` — parsed documents and grammars are pickled to disk so that
  re-runs skip parsing (`backend/sdoc/pickle_cache.py`, used by
  `SDocGrammarReader.read_from_file`). This removes parse cost, not query cost.
- `_static/static_html_search_index.js` in the HTML export — a denormalised
  **substring → node-index inverted index** for client-side full-text search.
  Inspected `[EXP]`: it maps every substring of every field value to node
  indices, e.g. `{"critical":[1],"criti":[1],…}`, with **no field attribution and
  no type attribution**, and it is not filtered by `--filter-nodes`. It answers
  "which nodes mention this text", never "which nodes have this field equal to
  this value".

### 3.3 Summary of the query answer

| Mechanism | ReqIF / RMF | StrictDoc |
| --- | --- | --- |
| Query language | none (file format only) | yes, textX; string-valued, feature-flagged |
| Restrict by item type | write it yourself, via `LONG-NAME`→id | **not expressible** for custom types |
| Attribute access | linear scan by `LONG-NAME`, then by definition id | `node["NAME"]`, `[A-Za-z0-9]+` only |
| Enumeration match | resolve label→enum-value id, then match refs | plain string compare against the option text |
| Index by type | none | none |
| Index by attribute value | none | none |
| Views / denormalised projections | none | full-text substring index (not field-aware) |
| Caching | none (id-keyed hash maps at parse time) | pickled parse results |

The generic item/attribute-value table does not get rescued by clever indexing in
either system. It gets scanned. Both are able to live with that because both are
sized for a document tree that fits in memory: StrictDoc parses the whole project
and holds it as an object graph, and ReqIF files are exchanged, not queried.

## Things that surprised me, or that cut against the ticket's framing

1. **ReqIF has no concept of a required attribute.** The ticket's framing —
   `ITEM_TYPE` + `ATTRIBUTE_DEF` as rows, validated against instances — is not
   what ReqIF is. A ReqIF `SpecObjectType` is a *labelling and presentation*
   contract (these fields exist, with these names, these domains, this order and
   this editability), not a validation contract. Mandatory-ness, uniqueness and
   cross-field rules are all outside the format.
2. **The ticket asks "how tools validate instances against a type"; the answer is
   essentially "they don't".** Verified: wrong-type definition reference, wrong
   enumeration, and missing mandatory value all pass strict normative-schema
   validation. The reference implementation's entire constraint set is nine
   copies of "does this element have a `LONG-NAME`". Instance/type conformance is
   discovered by consumers as a `KeyError`.
3. **StrictDoc validates *more* than ReqIF, not less** — required fields,
   closed enumerations, declared relations, and even field *order* — and it does
   so with a hand-written 400-line validator, not a schema. All of it is lost the
   moment the document is exported to ReqIF.
4. **StrictDoc's type is invisible to its own query language.** A grammar can
   declare a `RISK` element, validation will enforce it, the ReqIF export will
   emit a `SPEC-OBJECT-TYPE` for it — and then no query can say "nodes of type
   RISK". `node.is_requirement` is `node_type == "REQUIREMENT"`, hardcoded. If
   the plan is to model types as data and then query by type, **there is no prior
   art to copy here — this is the part both reference implementations left
   unbuilt.**
5. **`TBD`/`TBC` are members of every StrictDoc enumeration**, hardcoded in the
   validator. A closed domain that is never quite closed is apparently what
   authoring in-progress documents requires.
6. **Exported type identity is not stable.** Two StrictDoc→ReqIF exports of
   unchanged input differ on 47% of lines, because every type, attribute
   definition, enum value and object id is a fresh UUID. Data-modelled types make
   identifier stability a first-class problem that class-modelled types get for
   free from the class name.
7. **The normative XSD is stricter than reality in one corner** —
   `ENUM-VALUE/PROPERTIES/EMBEDDED-VALUE` with required `KEY` and `OTHER-CONTENT`
   — and everyone emits `OTHER-CONTENT=""` to satisfy it.
8. **StrictDoc's `Tag` field type has no ReqIF mapping at all** and reaches
   `raise NotImplementedError`. Four field types, three of them exportable.

## Reproducing the experiments

Everything under `[EXP]` was produced with:

```bash
uv venv sdvenv
VIRTUAL_ENV=$PWD/sdvenv uv pip install strictdoc reqif   # strictdoc 0.28.3, reqif 0.1.0

# a document declaring a custom RISK element with SEVERITY: SingleChoice(Low, Medium, High, Critical)
# REQUIRED: True, MITIGATIONS: MultipleChoice(...), and two RISK nodes.
strictdoc export .                                        # validation
strictdoc export . --filter-nodes 'node["SEVERITY"] == "Critical"'
strictdoc export . --filter-nodes 'node.is_requirement'
strictdoc export . --filter-nodes 'node["RISK_LEVEL"] == "x"'   # → Cannot parse filter query
strictdoc export . --formats=reqif-sdoc --output-dir r1   # run twice into r1/ and r2/ and diff
reqif validate --use-reqif-schema r1/reqif/output.reqif
```

The validation-failure cases were produced by mutating the input document (bad
enum value, deleted required field, swapped field order, `SEVERITY: TBD`); the
ReqIF type-violation cases by mutating `r1/reqif/output.reqif` (re-pointed
`DEFINITION`, deleted value, foreign `ENUM-VALUE-REF`, dangling reference) and
re-running `reqif validate --use-reqif-schema`, then feeding the mutants back to
`strictdoc export`.

Source read directly from: the `reqif` 0.1.0 and `strictdoc` 0.28.3 sdists from
PyPI; `github.com/strictdoc-project/strictdoc` @ `main` (`docs/`); and
`github.com/eclipse-rmf/org.eclipse.rmf` @ `master`
(`org.eclipse.rmf.reqif10/model/`, `org.eclipse.rmf.reqif10.constraints/`,
`org.eclipse.rmf.reqif10.common/`).

## Open questions

1. **The ReqIF specification prose (`formal/2016-07-01`) was unreachable**
   (`www.omg.org` blocked by egress policy). Re-read §§ on `SpecType`,
   `AttributeDefinition` and validation to confirm the inference in §1.4 that no
   MUST for instance/type validation exists.
2. **The ReqIF Implementation Guide** is the likely home of the numbered
   constraint catalogue that RMF's `C1.1`…`C1.9` ids point into. If a
   cross-checking rule ("a value's definition shall be an attribute of the
   element's type") is written down anywhere, it is there. Unread.
3. **RMF's `469356_QueryServices` branch** — what query services were designed for
   ReqIF, and why they are not on `master`. Unread.
4. The **field-title-keyed datatype registry** in StrictDoc's ReqIF exporter
   (§2.4) looks like a cross-document collision, but I did not build a failing
   case. Worth ten minutes if this pattern is going to be imitated.
