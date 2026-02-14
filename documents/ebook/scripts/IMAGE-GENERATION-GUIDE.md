# OJP E-Book Image Generation Guide

This guide explains how to generate images for the OJP e-book using the extracted prompts.

## Overview

The e-book contains **253 AI-ready image prompts** that need to be converted into professional corporate-style images. This directory provides scripts and structure to facilitate batch image generation and integration.

## Quick Start

### Step 1: Extract Image Prompts

```bash
cd /home/runner/work/ojp/ojp
bash documents/ebook/scripts/extract-image-prompts.sh > documents/ebook/image-prompts/all-prompts.json
```

This creates a JSON file with all 253 prompts structured for batch processing.

### Step 2: Prepare Images Directory

```bash
bash documents/ebook/scripts/prepare-images-directory.sh
```

This creates the complete directory structure:
```
documents/ebook/images/
├── part1-foundation/
├── part2-configuration/
├── part3-advanced/
├── part4-operations/
├── part5-development/
├── part6-analysis/
├── part7-vision/
└── appendices/
```

### Step 3: Generate Images

Use the `all-prompts.json` file with your preferred image generation service:

#### Option A: DALL-E 3 (OpenAI)
```python
import openai
import json

with open('documents/ebook/image-prompts/all-prompts.json') as f:
    data = json.load(f)

for prompt in data['prompts']:
    response = openai.Image.create(
        model="dall-e-3",
        prompt=f"Corporate technical diagram: {prompt['prompt']}",
        size="1792x1024",
        quality="hd"
    )
    # Save image to prompt['image_file']
```

#### Option B: Midjourney
Use the prompts file to batch generate images with Midjourney's `/imagine` command, appending the corporate template parameters:

```
/imagine prompt: [prompt text] --ar 16:9 --v 6 --style raw --quality 2
```

#### Option C: Stable Diffusion
```python
from diffusers import StableDiffusionXLPipeline

pipe = StableDiffusionXLPipeline.from_pretrained("stabilityai/stable-diffusion-xl-base-1.0")

for prompt in prompts:
    image = pipe(
        f"Professional technical diagram: {prompt['prompt']}. Corporate style, clean lines, modern colors."
    ).images[0]
    image.save(prompt['image_file'])
```

### Step 4: Integrate Images into E-Book

After generating images, update the markdown files:

```bash
# Dry run to see what would change
python3 documents/ebook/scripts/update-image-references.py --dry-run

# Apply changes
python3 documents/ebook/scripts/update-image-references.py
```

This replaces all `**[IMAGE PROMPT N]**:` markers with proper image references:

```markdown
![Description](images/part1-foundation/part1-chapter1-introduction-1.png)

*Figure 1: Description...*
```

## Corporate Template Specifications

All images should follow these guidelines:

### Visual Style
- **Clean, Modern Design**: Minimalist approach with clear visual hierarchy
- **Professional Color Palette**:
  - Primary: Blues (#0277BD, #0288D1, #03A9F4)
  - Secondary: Greens (#388E3C, #4CAF50)  
  - Accent: Orange (#F57C00, #FF9800)
  - Neutral: Grays (#424242, #616161, #9E9E9E)
- **Typography**: Clear sans-serif fonts (Inter, Roboto, or similar)
- **OJP Branding**: Consistent logo placement (top-left or as watermark)

### Technical Accuracy
- **Accurate Diagrams**: All technical representations must be correct
- **Clear Labels**: All components clearly labeled
- **Proper Arrows**: Directional flow indicated properly
- **Legend/Key**: When needed for complex diagrams

### Image Specifications
- **Format**: PNG for diagrams, SVG acceptable for vector graphics
- **Resolution**: 
  - Standard: 1920x1080 (16:9 aspect ratio)
  - Charts: 1200x800 (3:2 aspect ratio)
- **File Size**: Target <500KB per image (optimize with tools like `pngquant`)
- **Background**: White or subtle gradient (light gray to white)

### Diagram Types

#### Architecture Diagrams
- Show component relationships clearly
- Use consistent icons for similar components
- Include data flow arrows
- Label all connections

#### Comparison Charts
- Side-by-side "Before/After" or "With/Without OJP"
- Use color to highlight differences
- Include metrics/numbers when relevant

#### Process Flows
- Clear step-by-step progression
- Numbered steps
- Decision points clearly marked
- Outcome states visible

#### Performance Graphs
- Clear axis labels
- Grid lines for readability
- Legend for multiple data series
- Highlight key data points

## JSON Structure

The `all-prompts.json` file has this structure:

```json
{
  "metadata": {
    "extraction_date": "2026-01-13T14:07:00Z",
    "total_prompts": 253,
    "source": "OJP E-Book"
  },
  "prompts": [
    {
      "id": "part1-chapter1-introduction-1",
      "prompt_number": 1,
      "chapter": "part1-chapter1-introduction",
      "source_file": "part1-chapter1-introduction.md",
      "line_number": 13,
      "prompt": "Create a diagram showing the three types of JDBC drivers...",
      "image_file": "images/part1-foundation/part1-chapter1-introduction-1.png",
      "generated": false
    }
  ],
  "stats": {
    "total_prompts": 253
  }
}
```

## Batch Processing Tips

### Parallel Generation
Split the prompts file into chunks for parallel processing:

```python
import json
from multiprocessing import Pool

def generate_image(prompt_data):
    # Your image generation logic here
    pass

with open('all-prompts.json') as f:
    data = json.load(f)

with Pool(4) as pool:
    pool.map(generate_image, data['prompts'])
```

### Progress Tracking
Update the `generated` flag as images are created:

```python
prompt_data['generated'] = True
with open('all-prompts.json', 'w') as f:
    json.dump(data, f, indent=2)
```

### Quality Control
Review generated images before integration:

```bash
# Create a HTML preview
python3 documents/ebook/scripts/preview-images.py > preview.html
```

## Cost Estimates

### DALL-E 3 (OpenAI)
- HD Quality: ~$0.08 per image
- Standard: ~$0.04 per image
- **Total**: ~$20 (HD) or ~$10 (Standard) for 253 images

### Midjourney
- Standard Plan: $30/month (unlimited relaxed generations)
- **Total**: $30 (one month subscription)

### Stable Diffusion
- Free (local GPU required)
- Or Cloud GPU: ~$0.50-2.00 per hour
- **Total**: ~$5-10 (estimated 5 hours of GPU time)

## Workflow Summary

1. ✓ **Extract prompts** → Creates `all-prompts.json`
2. ✓ **Prepare directories** → Creates folder structure
3. ⏳ **Generate images** → Use AI service of choice
4. ⏳ **Review/optimize** → Check quality, compress files
5. ⏳ **Update markdown** → Run integration script
6. ⏳ **Verify** → Build e-book to PDF/HTML and review

## Troubleshooting

### Images Not Appearing
- Check file paths in markdown references
- Verify images are in correct subdirectories
- Ensure PNG format (not JPEG)

### Poor Quality
- Regenerate with higher quality settings
- Use HD/high-res options
- Consider manual touch-ups for critical diagrams

### Size Issues
- Optimize PNGs: `pngquant --quality=65-80 image.png`
- Use SVG for simple diagrams
- Target <500KB per image

## Support

For questions or issues:
- Check the e-book README: `documents/ebook/README.md`
- Review image directory README: `documents/ebook/images/README.md`
- Open an issue in the repository

---

**Status**: Ready for batch image generation
**Total Prompts**: 253
**Estimated Time**: 2-4 hours (depending on service and automation)
