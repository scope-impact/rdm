# ============================================================================
# Regulatory Documentation Build
# ============================================================================

SHELL := /bin/sh -e
.SUFFIXES:
.SECONDARY:
.DELETE_ON_ERROR:

CONFIG := config.yml
# Images resolve against the rendered file's own directory first (release/
# mirrors documents/, so a link such as ../../c4/views/C1.svg finds its file),
# then the DHF, its documents and extracted media. Each rule's $< is its input.
RESOURCE_PATH = $(<D):.:./documents:./tmp/media/
DATA_FILES := $(wildcard data/*.yml)
SRC_MDS := $(wildcard documents/**/*.md documents/*.md)
RELEASE_MDS := $(patsubst documents/%.md,release/%.md,$(SRC_MDS))
RELEASE_PDFS := $(patsubst documents/%.md,release/%.pdf,$(SRC_MDS))
RELEASE_DOCS := $(patsubst documents/%.md,release/%.docx,$(SRC_MDS))

all: $(RELEASE_MDS)

pdfs: $(RELEASE_PDFS)

docs: $(RELEASE_DOCS)

release/%.md: documents/%.md $(CONFIG) $(DATA_FILES)
	@mkdir -p $(@D)
	rdm render $< $(CONFIG) $(DATA_FILES) > $@

release/%.pdf: release/%.md pandoc_pdf.yml template.typ
	@mkdir -p $(@D)
	pandoc --defaults=./pandoc_pdf.yml --resource-path=$(RESOURCE_PATH) $< -o $@

release/%.docx: release/%.md pandoc_docx.yml $(wildcard reference.docx)  # the Word template, if any
	@mkdir -p $(@D)
	pandoc --defaults=./pandoc_docx.yml --resource-path=$(RESOURCE_PATH) $< -o $@

# useful for debugging
release/%.typ: release/%.md pandoc_pdf.yml template.typ
	@mkdir -p $(@D)
	pandoc --defaults=./pandoc_pdf.yml --resource-path=$(RESOURCE_PATH) -t typst $< -o $@

.PHONY: all pdfs docs clean

clean:
	rm -rf tmp release
