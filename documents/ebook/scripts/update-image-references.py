#!/usr/bin/env python3

"""
Update image references in OJP e-book markdown files.
Replaces image prompts with actual image references after generation.
"""

import re
import json
import sys
from pathlib import Path

def update_markdown_file(file_path, dry_run=False):
    """Update a single markdown file to replace prompts with image references."""
    
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Pattern to match image prompts
    # **[IMAGE PROMPT N]**: Description...
    pattern = r'\*\*\[IMAGE PROMPT (\d+)\]\*\*:([^\n]+(?:\n(?!\n|##|\*\*\[)[^\n]+)*)'
    
    def replace_prompt(match):
        prompt_num = match.group(1)
        prompt_text = match.group(2).strip()
        
        # Generate image filename
        chapter_name = file_path.stem
        image_filename = f"{chapter_name}-{prompt_num}.png"
        
        # Determine subdirectory based on chapter
        if 'part1' in chapter_name or 'chapter1' in chapter_name or 'chapter2' in chapter_name or 'chapter3' in chapter_name:
            subdir = 'part1-foundation'
        elif 'part2' in chapter_name or 'chapter4' in chapter_name or 'chapter5' in chapter_name or 'chapter6' in chapter_name or 'chapter7' in chapter_name:
            subdir = 'part2-configuration'
        elif 'part3' in chapter_name or 'chapter8' in chapter_name or 'chapter9' in chapter_name or 'chapter10' in chapter_name or 'chapter11' in chapter_name or 'chapter12' in chapter_name:
            subdir = 'part3-advanced'
        elif 'part4' in chapter_name or 'chapter13' in chapter_name or 'chapter14' in chapter_name:
            subdir = 'part4-operations'
        elif 'part5' in chapter_name or 'chapter15' in chapter_name or 'chapter16' in chapter_name or 'chapter17' in chapter_name or 'chapter18' in chapter_name:
            subdir = 'part5-development'
        elif 'part6' in chapter_name or 'chapter19' in chapter_name or 'chapter20' in chapter_name:
            subdir = 'part6-analysis'
        elif 'part7' in chapter_name or 'chapter21' in chapter_name or 'chapter22' in chapter_name:
            subdir = 'part7-vision'
        elif 'appendix' in chapter_name:
            subdir = 'appendices'
        else:
            subdir = 'images'
        
        image_path = f"images/{subdir}/{image_filename}"
        
        # Create the replacement markdown
        # Keep the original prompt as alt text (truncated)
        alt_text = prompt_text[:100].replace('\n', ' ').replace('"', "'")
        
        replacement = f'![{alt_text}]({image_path})\n\n*Figure {prompt_num}: {alt_text}...*'
        
        return replacement
    
    # Perform replacement
    updated_content, count = re.subn(pattern, replace_prompt, content)
    
    if count > 0:
        if not dry_run:
            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(updated_content)
            print(f"✓ Updated {file_path.name}: {count} image(s) replaced")
        else:
            print(f"[DRY RUN] Would update {file_path.name}: {count} image(s)")
        return count
    else:
        return 0

def main():
    """Main function to process all markdown files."""
    
    # Check if dry-run mode
    dry_run = '--dry-run' in sys.argv or '-n' in sys.argv
    
    if dry_run:
        print("=== DRY RUN MODE ===")
        print("No files will be modified.\n")
    
    # Get the ebook directory
    script_dir = Path(__file__).parent
    ebook_dir = script_dir.parent
    
    # Process all markdown files
    total_replaced = 0
    files_updated = 0
    
    for md_file in sorted(ebook_dir.glob('*.md')):
        if md_file.name == 'README.md':
            continue
        
        count = update_markdown_file(md_file, dry_run)
        if count > 0:
            total_replaced += count
            files_updated += 1
    
    print(f"\n{'[DRY RUN] ' if dry_run else ''}Summary:")
    print(f"  Files processed: {files_updated}")
    print(f"  Total images replaced: {total_replaced}")
    
    if dry_run:
        print(f"\nRun without --dry-run to apply changes.")
    else:
        print(f"\nImage references updated successfully!")

if __name__ == '__main__':
    main()
