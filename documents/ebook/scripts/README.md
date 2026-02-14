# E-Book Scripts

This directory contains scripts for managing the OJP e-book images.

## Scripts

### 1. `extract-image-prompts.sh`
Extracts all 253 image prompts from the e-book markdown files into a structured JSON file.

**Usage:**
```bash
bash documents/ebook/scripts/extract-image-prompts.sh > documents/ebook/image-prompts/all-prompts.json
```

**Output:** `documents/ebook/image-prompts/all-prompts.json` with all prompts ready for batch processing.

### 2. `prepare-images-directory.sh`
Creates the complete directory structure for storing generated images.

**Usage:**
```bash
bash documents/ebook/scripts/prepare-images-directory.sh
```

**Creates:**
- `documents/ebook/images/` with 8 subdirectories
- `.gitkeep` files to preserve structure
- `README.md` with image specifications

### 3. `update-image-references.py`
Updates all markdown files to replace image prompts with actual image references.

**Usage:**
```bash
# Dry run (preview changes)
python3 documents/ebook/scripts/update-image-references.py --dry-run

# Apply changes
python3 documents/ebook/scripts/update-image-references.py
```

**This script:**
- Finds all `**[IMAGE PROMPT N]**:` markers
- Replaces with proper markdown image syntax
- Adds figure captions
- Uses correct subdirectory paths

## Workflow

1. **Extract** → Run `extract-image-prompts.sh` to create JSON with all prompts
2. **Prepare** → Run `prepare-images-directory.sh` to create folder structure
3. **Generate** → Use external AI service (DALL-E, Midjourney, Stable Diffusion) to create images
4. **Integrate** → Run `update-image-references.py` to update markdown files

## Documentation

See `IMAGE-GENERATION-GUIDE.md` for complete documentation including:
- Corporate template specifications
- AI service integration examples
- Cost estimates
- Troubleshooting tips

## Status

- ✅ Scripts created
- ✅ Directory structure prepared
- ⏳ Image generation (external process)
- ⏳ Markdown integration

## Current State

**Total Prompts:** 253 (actually 139 found in current scan - will be updated after extraction)
**Generated Images:** 0
**Integration Status:** Not started (pending image generation)
