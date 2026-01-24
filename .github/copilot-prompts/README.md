# Copilot Prompts Directory

This directory contains detailed prompt files for GitHub Copilot to implement specific features in the OJP (Open J Proxy) project.

## Purpose

These prompt files serve as comprehensive specifications for feature implementations. Each prompt file:
- Describes the feature requirements in detail
- Provides context about the codebase and architecture
- Specifies implementation guidelines and patterns to follow
- Lists integration points in existing code
- Defines testing requirements
- Includes documentation requirements
- Maps to relevant sections of the OJP ebook

## Usage

When implementing a feature described in a prompt file:

1. **Read the entire prompt file** to understand the full scope
2. **Review the referenced sections** of the codebase and ebook
3. **Follow the implementation guidelines** to ensure consistency
4. **Implement tests** as specified in the testing requirements
5. **Update documentation** as specified in the documentation requirements
6. **Verify success criteria** before considering the implementation complete

## Available Prompts

- **audit-logging-implementation.md** - Comprehensive audit logging for security and compliance
  - Logs connections, queries, and authentication events
  - Configurable logging levels and targets
  - Supports PCI-DSS, HIPAA, and GDPR compliance requirements
  - Based on Chapter 11 - Security, Audit Logging section of the OJP ebook

## Contributing

When adding new prompt files:
- Use descriptive, kebab-case filenames (e.g., `feature-name-implementation.md`)
- Include all sections: Overview, Context, Requirements, Implementation Guidelines, Testing, Documentation, Success Criteria
- Reference specific files and line numbers in the codebase where relevant
- Link to relevant ebook chapters or documentation
- Be specific about expected behavior and edge cases
- Include examples of expected output or behavior
