#!/bin/bash

##############################################################################
# OJP Docker Build Helper
#
# This script automates the Docker image build process with open source
# JDBC drivers included ("batteries included" image).
#
# Usage:
#   ./docker-build.sh [build|push]
#
# Commands:
#   build  - Build Docker image locally (default)
#   push   - Build and push Docker image to registry (requires docker login)
#
# Examples:
#   ./docker-build.sh           # Build image locally
#   ./docker-build.sh build     # Build image locally
#   ./docker-build.sh push      # Build and push to registry
#
##############################################################################

set -e  # Exit on error

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default command
COMMAND="${1:-build}"

echo -e "${BLUE}==========================================${NC}"
echo -e "${BLUE}   OJP Docker Build Helper${NC}"
echo -e "${BLUE}==========================================${NC}"
echo ""

# Check if we're in the ojp-server directory
if [ ! -f "pom.xml" ]; then
    echo -e "${RED}Error: This script must be run from the ojp-server directory${NC}"
    echo -e "${YELLOW}Usage: cd ojp-server && ./docker-build.sh${NC}"
    exit 1
fi

# Check if download-drivers.sh exists
if [ ! -f "download-drivers.sh" ]; then
    echo -e "${RED}Error: download-drivers.sh not found${NC}"
    echo -e "${YELLOW}This script should be in the ojp-server directory${NC}"
    exit 1
fi

# Step 1: Download open source JDBC drivers
echo -e "${GREEN}Step 1: Downloading open source JDBC drivers...${NC}"
echo ""

bash download-drivers.sh ./ojp-libs

if [ $? -ne 0 ]; then
    echo ""
    echo -e "${RED}Failed to download drivers!${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}✓ Drivers downloaded successfully${NC}"
echo ""

# Step 2: Build Docker image with Jib
echo -e "${GREEN}Step 2: Building Docker image with Jib...${NC}"
echo ""

# Move to parent directory for Maven build
cd ..

if [ "$COMMAND" = "push" ]; then
    echo -e "${YELLOW}Building and pushing to registry...${NC}"
    echo -e "${YELLOW}Note: You must be logged in with 'docker login'${NC}"
    echo ""
    mvn compile jib:build -pl ojp-server
    
    if [ $? -eq 0 ]; then
        echo ""
        echo -e "${GREEN}✓ Docker image built and pushed successfully!${NC}"
        echo ""
        echo -e "${BLUE}Image: rrobetti/ojp:0.4.1-beta${NC}"
        echo -e "${BLUE}Includes: H2, PostgreSQL, MySQL, MariaDB drivers${NC}"
    else
        echo ""
        echo -e "${RED}Failed to build/push Docker image!${NC}"
        exit 1
    fi
elif [ "$COMMAND" = "build" ]; then
    echo -e "${YELLOW}Building Docker image locally...${NC}"
    echo ""
    mvn compile jib:dockerBuild -pl ojp-server
    
    if [ $? -eq 0 ]; then
        echo ""
        echo -e "${GREEN}✓ Docker image built successfully!${NC}"
        echo ""
        echo -e "${BLUE}Image: rrobetti/ojp:0.4.1-beta${NC}"
        echo -e "${BLUE}Includes: H2, PostgreSQL, MySQL, MariaDB drivers${NC}"
        echo ""
        echo -e "${GREEN}You can now run the image:${NC}"
        echo -e "  ${YELLOW}docker run -d -p 1059:1059 --name ojp rrobetti/ojp:0.4.1-beta${NC}"
    else
        echo ""
        echo -e "${RED}Failed to build Docker image!${NC}"
        exit 1
    fi
else
    echo -e "${RED}Unknown command: $COMMAND${NC}"
    echo -e "${YELLOW}Usage: ./docker-build.sh [build|push]${NC}"
    exit 1
fi

echo ""
echo -e "${BLUE}==========================================${NC}"
echo -e "${GREEN}Build complete!${NC}"
echo -e "${BLUE}==========================================${NC}"
