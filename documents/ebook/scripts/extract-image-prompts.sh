#!/bin/bash

# Extract all image prompts from OJP e-book markdown files
# This script creates a structured JSON file with all image prompts for batch processing

OUTPUT_DIR="documents/ebook/image-prompts"
OUTPUT_FILE="$OUTPUT_DIR/all-prompts.json"

# Create output directory
mkdir -p "$OUTPUT_DIR"

echo "Extracting image prompts from e-book..."
echo "{"
echo '  "metadata": {'
echo '    "extraction_date": "'$(date -u +"%Y-%m-%dT%H:%M:%SZ")'",'
echo '    "total_prompts": 0,'
echo '    "source": "OJP E-Book"'
echo "  },"
echo '  "prompts": ['

# Process all markdown files
first=true
prompt_count=0

for file in documents/ebook/*.md; do
    # Skip README
    if [[ "$file" == *"README.md"* ]]; then
        continue
    fi
    
    # Extract chapter/appendix name
    chapter=$(basename "$file" .md)
    
    # Find all image prompts in the file
    while IFS= read -r line_num; do
        # Extract the prompt number and content
        prompt_line=$(sed -n "${line_num}p" "$file")
        
        # Get the prompt number from the pattern **[IMAGE PROMPT N]**
        if [[ $prompt_line =~ \*\*\[IMAGE\ PROMPT\ ([0-9]+)\]\*\*:?\ (.+) ]]; then
            prompt_num="${BASH_REMATCH[1]}"
            first_line="${BASH_REMATCH[2]}"
            
            # Read the full prompt (may span multiple lines)
            full_prompt="$first_line"
            next_line=$((line_num + 1))
            
            # Continue reading until we hit an empty line or new section
            while true; do
                next_content=$(sed -n "${next_line}p" "$file")
                
                # Stop if empty line, markdown header, or code block
                if [[ -z "$next_content" ]] || [[ "$next_content" =~ ^#+ ]] || [[ "$next_content" =~ ^\`\`\` ]] || [[ "$next_content" =~ ^\*\*\[ ]]; then
                    break
                fi
                
                # Append to full prompt
                full_prompt="$full_prompt $next_content"
                next_line=$((next_line + 1))
            done
            
            # Clean up the prompt
            full_prompt=$(echo "$full_prompt" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
            
            # Create JSON entry
            if [ "$first" = false ]; then
                echo ","
            fi
            first=false
            prompt_count=$((prompt_count + 1))
            
            echo "    {"
            echo "      \"id\": \"${chapter}-${prompt_num}\","
            echo "      \"prompt_number\": $prompt_num,"
            echo "      \"chapter\": \"$chapter\","
            echo "      \"source_file\": \"$(basename $file)\","
            echo "      \"line_number\": $line_num,"
            echo "      \"prompt\": $(echo "$full_prompt" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().strip()))'),"
            echo "      \"image_file\": \"images/${chapter}-${prompt_num}.png\","
            echo "      \"generated\": false"
            echo -n "    }"
        fi
    done < <(grep -n "\*\*\[IMAGE PROMPT" "$file" | cut -d: -f1)
done

echo ""
echo "  ],"
echo '  "stats": {'
echo "    \"total_prompts\": $prompt_count"
echo "  }"
echo "}"

echo "" >&2
echo "Extracted $prompt_count image prompts" >&2
echo "Output: $OUTPUT_FILE" >&2
