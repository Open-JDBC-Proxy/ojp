# Chapter 16: Contributing Workflow and Git Strategy

Contributing to an open-source project like OJP requires more than just writing good code—it requires understanding the collaboration workflow, knowing how to navigate Git effectively, and being part of a community-driven development process. This chapter walks you through the entire contribution journey, from forking the repository to seeing your pull request merged and celebrated.

Whether you're fixing a small bug, adding a major feature, or improving documentation, the workflow remains consistent and designed to make collaboration smooth. We'll cover everything from Git basics to pull request etiquette, helping you become a confident contributor.

---

## 16.1 Understanding the Git Workflow

OJP uses a **fork-based workflow** with the **main branch** as the stable, production-ready branch. All development happens in feature branches in individual forks, which are then submitted as pull requests to the upstream repository.

### Why Fork-Based?

The fork-based workflow provides several advantages:
- **Isolation**: Your experiments don't affect the main repository
- **Safety**: Only reviewed and approved changes make it to main
- **Scalability**: Multiple contributors can work independently
- **Learning**: You can experiment freely in your fork

The main repository at `Open-J-Proxy/ojp` serves as the canonical source. Contributors fork this repository, make changes in their forks, and submit pull requests back upstream.

### Branch Strategy

OJP keeps things simple with a single long-lived branch:
- **main**: The stable branch that reflects production-ready code. All releases are tagged from main, and this branch always builds successfully.

Feature branches are short-lived and exist only in your fork:
- **feature/add-connection-pooling**
- **fix/null-pointer-in-resultset**
- **docs/improve-setup-guide**
- **refactor/simplify-query-execution**

This single-branch strategy avoids the complexity of maintaining development, staging, and release branches. The main branch is always deployable, and features are integrated through pull requests only after they're complete and tested.

---

## 16.2 Setting Up Your Development Environment

Before you can contribute, you need to set up your local development environment. This involves forking the repository, cloning your fork, and establishing a connection to the upstream repository.

### Forking the Repository

Navigate to the OJP repository at `https://github.com/Open-J-Proxy/ojp` and click the **Fork** button in the top-right corner. This creates a complete copy of the repository under your GitHub account (`https://github.com/YOUR-USERNAME/ojp`).

Your fork is your personal workspace. You can experiment, break things, and try new ideas without affecting anyone else. It's also where you'll push your feature branches before creating pull requests.

### Cloning Your Fork

Once you've forked the repository, clone it to your local machine:

```bash
git clone https://github.com/YOUR-USERNAME/ojp.git
cd ojp
```

This creates a local copy of your fork. The default remote is called `origin` and points to your fork on GitHub.

### Adding the Upstream Remote

To keep your fork synchronized with the main repository, add it as a remote called `upstream`:

```bash
git remote add upstream https://github.com/Open-J-Proxy/ojp.git
```

You can verify your remotes:

```bash
git remote -v
```

You should see:
```
origin    https://github.com/YOUR-USERNAME/ojp.git (fetch)
origin    https://github.com/YOUR-USERNAME/ojp.git (push)
upstream  https://github.com/Open-J-Proxy/ojp.git (fetch)
upstream  https://github.com/Open-J-Proxy/ojp.git (push)
```

The `origin` remote is where you push your changes, and `upstream` is where you pull updates from the main repository.

### Keeping Your Fork in Sync

Before starting new work, always sync your fork with the upstream repository:

```bash
git fetch upstream
git checkout main
git merge upstream/main
git push origin main
```

This four-step process ensures your local main branch matches the upstream main branch, and then pushes those updates to your fork on GitHub. Do this before creating any new feature branch to ensure you're working with the latest code.

---

## 16.3 Creating a Feature Branch

Never work directly on the main branch in your fork. Instead, create a feature branch for each piece of work. This keeps your changes isolated and makes it easy to work on multiple features or fixes simultaneously.

### Naming Conventions

Use descriptive branch names that clearly indicate what you're working on:

**Good branch names**:
- `feature/add-connection-timeout-config`
- `fix/null-pointer-in-resultset-getstring`
- `docs/improve-multinode-setup-guide`
- `refactor/extract-pool-provider-interface`
- `perf/optimize-query-parsing`

**Poor branch names**:
- `my-changes`
- `update`
- `branch1`
- `dev`

The prefix (`feature/`, `fix/`, `docs/`, etc.) helps categorize the work, and the rest of the name should be specific enough that someone else can understand what the branch does without reading the commits.

### Creating and Switching to a Branch

First, ensure your main branch is up to date, then create and switch to your feature branch:

```bash
git checkout main
git pull upstream main
git checkout -b feature/add-connection-timeout
```

Now you're ready to make changes. All your commits will go to this feature branch, keeping your main branch clean.

---

## 16.4 Making Changes and Committing

With your feature branch created, you can start writing code, fixing bugs, or updating documentation. Follow these practices to make your changes easy to review and integrate.

### Writing Good Commits

Each commit should be atomic and focused on a single logical change. If you find yourself using "and" in a commit message, you probably need multiple commits.

**Good commit examples**:
- `Add connectionTimeout property to OjpDataSource`
- `Fix null pointer exception when column doesn't exist`
- `Update README with PostgreSQL SSL configuration`
- `Refactor query parser to use visitor pattern`

**Poor commit examples**:
- `Fix stuff`
- `Update code`
- `WIP`
- `More changes`

### Commit Message Format

Use clear, imperative-mood commit messages:

```
Add connection timeout configuration

- Add connectionTimeout field to OjpDataSource
- Implement timeout logic in ConnectionAcquisitionManager
- Add validation for timeout values (must be positive)
- Update unit tests to cover timeout scenarios
```

The first line is a short summary (50-72 characters) that completes the sentence "This commit will...". The body provides more detail when needed, explaining what and why (not how—that's what the code shows).

### Making Incremental Commits

Commit frequently as you work:

```bash
git add src/main/java/ojp/driver/OjpDataSource.java
git commit -m "Add connectionTimeout field to OjpDataSource"

git add src/main/java/ojp/driver/ConnectionAcquisitionManager.java
git commit -m "Implement timeout logic in connection acquisition"

git add src/test/java/ojp/driver/OjpDataSourceTest.java
git commit -m "Add tests for connection timeout validation"
```

Incremental commits make it easier to review your changes and understand your thought process. They also make it simpler to revert specific changes if needed.

### Viewing Your Changes

Before committing, review what you've changed:

```bash
# See what files changed
git status

# See the actual changes
git diff

# See changes for a specific file
git diff src/main/java/ojp/driver/OjpDataSource.java
```

After committing, you can review your commit history:

```bash
# See recent commits
git log --oneline -10

# See detailed commit history
git log --graph --oneline --all
```

---

## 16.5 Testing Your Changes Locally

Before pushing your changes, test them thoroughly. OJP has a comprehensive test suite, but tests are disabled by default to avoid requiring database setup for casual contributors.

### Starting the OJP Server

Most integration tests require a running OJP server:

```bash
# In the project root
mvn verify -pl ojp-server -Prun-ojp-server
```

This starts the server with embedded H2 database support. Leave this terminal open—the server needs to run while you execute tests.

### Running Tests

In a separate terminal, navigate to the driver module and run tests:

```bash
cd ojp-jdbc-driver
mvn test -DenableH2Tests=true
```

The H2 tests run quickly and catch most issues. For database-specific testing, use the appropriate enable flags:

```bash
# PostgreSQL tests
mvn test -DenablePostgresTests=true

# MySQL tests
mvn test -DenableMySQLTests=true

# Multiple databases
mvn test -DenableH2Tests=true -DenablePostgresTests=true
```

See Chapter 15 for detailed setup instructions for each database.

### Running Specific Tests

If you're working on a specific area, run only the relevant tests:

```bash
# Run a single test class
mvn test -Dtest=OjpDataSourceTest -DenableH2Tests=true

# Run a single test method
mvn test -Dtest=OjpDataSourceTest#shouldValidateConnectionTimeout -DenableH2Tests=true
```

This speeds up your development cycle by avoiding unnecessary test execution.

### Verifying the Full Build

Before submitting a pull request, ensure the entire project builds:

```bash
cd ..  # Return to project root
mvn clean install
```

This compiles all modules, runs unit tests, and creates the JAR files. If this succeeds, your changes are likely ready for review.

---

## 16.6 Pushing to Your Fork

Once you're satisfied with your changes and all tests pass, push your feature branch to your fork on GitHub:

```bash
git push origin feature/add-connection-timeout
```

If this is the first time you're pushing this branch, you'll see output like:

```
* [new branch]      feature/add-connection-timeout -> feature/add-connection-timeout
```

If you've pushed before and made new commits, you'll see:

```
To https://github.com/YOUR-USERNAME/ojp.git
   abc1234..def5678  feature/add-connection-timeout -> feature/add-connection-timeout
```

### Updating Your Branch

If you make additional changes after pushing:

```bash
# Make changes, commit them
git add .
git commit -m "Add additional validation for timeout values"

# Push the new commits
git push origin feature/add-connection-timeout
```

If your pull request is already open, GitHub automatically updates it with your new commits.

---

## 16.7 Creating a Pull Request

With your changes pushed to your fork, you're ready to create a pull request (PR) to submit your work for review.

### Opening the PR

Navigate to the OJP repository on GitHub (`https://github.com/Open-J-Proxy/ojp`). GitHub typically detects your recently pushed branch and displays a banner with a **Compare & pull request** button. Click it to start creating your PR.

Alternatively, go to the **Pull requests** tab and click **New pull request**, then click **compare across forks** and select your fork and branch.

### PR Title and Description

Write a clear, descriptive title that summarizes your changes:

**Good titles**:
- `Add connection timeout configuration to JDBC driver`
- `Fix null pointer exception in ResultSet.getString()`
- `Update multinode setup documentation with troubleshooting guide`

**Poor titles**:
- `Fix bug`
- `Update`
- `Changes`

In the description, provide context and detail:

```markdown
## Description
This PR adds connection timeout configuration to prevent indefinite blocking when acquiring connections from the pool.

## Changes
- Added `connectionTimeout` property to `OjpDataSource`
- Implemented timeout logic in `ConnectionAcquisitionManager`
- Added validation for timeout values (must be positive)
- Updated configuration documentation
- Added comprehensive tests for timeout behavior

## Testing
- Added unit tests for timeout validation
- Added integration tests for timeout enforcement with H2
- Manually tested with PostgreSQL and MySQL
- Verified backward compatibility (timeout defaults to 30 seconds)

## Migration Notes
No breaking changes. Existing configurations continue to work with default timeout.

Fixes #187
```

### Linking to Issues

**This is critical**: Always link your PR to an issue using GitHub keywords in the description:

```markdown
Fixes #187
Closes #234
Resolves #156
```

These keywords automatically close the referenced issues when your PR is merged. You can link multiple issues:

```markdown
Fixes #187, closes #234
```

If no issue exists for what you're working on, create one first to discuss the problem or feature before submitting a PR.

---

## 16.8 The Pull Request Review Process

Once you've submitted your PR, it enters the review phase. This is a collaborative process where maintainers and other contributors provide feedback to improve your code.

### What Happens Next

1. **Automated checks run**: CI pipelines execute tests across multiple databases and Java versions
2. **Code review**: Maintainers review your changes for correctness, style, and design
3. **Discussion**: Reviewers may ask questions, request changes, or suggest improvements
4. **Iteration**: You address feedback by pushing new commits to your branch
5. **Approval**: Once everything looks good, a maintainer approves the PR
6. **Merge**: The PR is merged into the main branch

### Responding to Feedback

Reviewers may request changes or ask questions. This is normal and part of the collaborative process. Some tips for responding:

**Be respectful and grateful**: Reviewers are volunteering their time to help improve the code.

**Ask for clarification**: If feedback is unclear, ask questions rather than guessing.

**Make requested changes**: Address feedback by making new commits to your branch:

```bash
# Make the requested changes
git add .
git commit -m "Refactor timeout logic per review feedback"
git push origin feature/add-connection-timeout
```

The PR automatically updates with your new commits.

**Mark conversations as resolved**: After addressing each comment, use GitHub's "Resolve conversation" button to indicate you've handled it.

### Handling Merge Conflicts

If the main branch advances while your PR is under review, you may need to resolve conflicts:

```bash
# Sync with upstream
git fetch upstream
git checkout feature/add-connection-timeout
git merge upstream/main

# Resolve any conflicts in your editor
# Then commit the merge
git add .
git commit -m "Merge upstream/main to resolve conflicts"
git push origin feature/add-connection-timeout
```

Alternatively, use rebasing for a cleaner history:

```bash
git fetch upstream
git checkout feature/add-connection-timeout
git rebase upstream/main

# Resolve conflicts if any
git add .
git rebase --continue

# Force push (rebase rewrites history)
git push --force-with-lease origin feature/add-connection-timeout
```

Use `--force-with-lease` instead of `--force` to avoid accidentally overwriting others' work.

---

## 16.9 After Your PR is Merged

Congratulations! Your contribution has been accepted and merged. Here's what to do next:

### Clean Up Your Branch

Once merged, delete your feature branch from both GitHub and your local machine:

```bash
# Delete local branch
git checkout main
git branch -d feature/add-connection-timeout

# Delete remote branch (GitHub usually does this automatically)
git push origin --delete feature/add-connection-timeout
```

### Sync Your Fork

Update your fork to include your merged changes:

```bash
git checkout main
git pull upstream main
git push origin main
```

Your contribution is now part of OJP's main branch and will be included in the next release.

### Start Your Next Contribution

Ready for more? Create a new feature branch and repeat the process. Each contribution makes you more familiar with the codebase and the community.

---

## 16.10 Advanced Git Techniques

As you become more experienced with Git, these advanced techniques will help you work more efficiently.

### Interactive Rebase

Clean up your commit history before submitting a PR:

```bash
# Rebase the last 5 commits
git rebase -i HEAD~5
```

This opens an editor where you can:
- **pick**: Keep the commit as-is
- **reword**: Change the commit message
- **squash**: Combine with previous commit
- **edit**: Modify the commit's contents
- **drop**: Remove the commit

Use this to combine small "fix typo" commits into meaningful logical commits.

### Cherry-Picking Commits

Apply specific commits from one branch to another:

```bash
git checkout main
git cherry-pick abc1234
```

This is useful if you made a commit on the wrong branch or need to backport a fix.

### Stashing Changes

Save uncommitted changes temporarily:

```bash
# Stash current changes
git stash

# Do something else (switch branches, pull updates)
git checkout main
git pull upstream main

# Return and restore changes
git checkout feature/my-work
git stash pop
```

### Viewing Differences Between Branches

Compare your branch with main before creating a PR:

```bash
# See commit differences
git log main..feature/add-connection-timeout

# See actual code differences
git diff main...feature/add-connection-timeout
```

The three-dot syntax shows changes from the common ancestor, which is usually what you want.

---

## 16.11 Working on Multiple Features

You can work on multiple features simultaneously by using separate branches:

```bash
# Start feature A
git checkout main
git pull upstream main
git checkout -b feature/add-xa-support
# Work on XA support...
git push origin feature/add-xa-support

# Switch to feature B
git checkout main
git checkout -b feature/improve-error-messages
# Work on error messages...
git push origin feature/improve-error-messages

# Switch back to feature A
git checkout feature/add-xa-support
```

Each branch is independent, allowing you to submit multiple PRs simultaneously. If one gets blocked on review, you can continue working on others.

---

## 16.12 Troubleshooting Common Git Issues

### Accidentally Committed to Main

If you made commits directly to main instead of a feature branch:

```bash
# Create feature branch from current position
git branch feature/my-changes

# Reset main to match upstream
git checkout main
git reset --hard upstream/main

# Switch to your feature branch
git checkout feature/my-changes
```

### Lost Commits

If you accidentally deleted commits, Git's reflog can save you:

```bash
# See recent HEAD movements
git reflog

# Restore lost commit
git checkout <commit-hash>
git checkout -b recovery-branch
```

### Merge Conflicts During Rebase

If you encounter conflicts during a rebase:

1. Resolve conflicts in your editor
2. Stage resolved files: `git add <file>`
3. Continue rebasing: `git rebase --continue`
4. Or abort if needed: `git rebase --abort`

---

## 16.13 Etiquette and Best Practices

Contributing to open source is as much about communication as it is about code. Follow these guidelines to be a respectful and effective contributor.

### Before Starting Work

- **Check existing issues**: Someone might already be working on it
- **Discuss major changes**: Open an issue first for big features or refactorings
- **Read documentation**: Familiarize yourself with OJP's architecture and conventions

### During Development

- **Keep PRs focused**: One feature or fix per PR
- **Write tests**: All new features and bug fixes need tests
- **Follow style guidelines**: Match the existing code style
- **Document your changes**: Update README, configuration docs, or code comments as needed

### In Pull Requests

- **Be patient**: Maintainers are volunteers with other responsibilities
- **Accept feedback gracefully**: Code review makes your contribution better
- **Don't take it personally**: Feedback is about the code, not you
- **Be responsive**: Address comments and questions promptly

### After Merge

- **Celebrate**: You've contributed to open source!
- **Share your achievement**: Mention it on LinkedIn, Twitter, or your blog
- **Help others**: Review other PRs or answer questions in discussions

---

## 16.14 Common Contribution Scenarios

Let's walk through some typical contribution scenarios from start to finish.

### Scenario 1: Fixing a Bug

You notice a null pointer exception in `ResultSet.getString()` when a column doesn't exist.

```bash
# Sync and create branch
git checkout main
git pull upstream main
git checkout -b fix/null-pointer-in-resultset

# Make the fix
# Edit src/main/java/ojp/jdbc/OjpResultSet.java
# Add null check before accessing column

# Add regression test
# Edit src/test/java/ojp/jdbc/OjpResultSetTest.java
# Add test that verifies null is returned for non-existent column

# Commit
git add .
git commit -m "Fix null pointer exception in ResultSet.getString()

Add null check when column doesn't exist. Previously threw NPE,
now correctly returns null per JDBC spec.

Fixes #234"

# Test
mvn test -DenableH2Tests=true

# Push and create PR
git push origin fix/null-pointer-in-resultset
```

### Scenario 2: Adding a Feature

You want to add connection timeout configuration to the JDBC driver.

```bash
# Sync and create branch
git checkout main
git pull upstream main
git checkout -b feature/add-connection-timeout

# Implement feature incrementally
git add src/main/java/ojp/driver/OjpDataSource.java
git commit -m "Add connectionTimeout field to OjpDataSource"

git add src/main/java/ojp/driver/ConnectionAcquisitionManager.java
git commit -m "Implement timeout logic in connection acquisition"

git add src/test/java/ojp/driver/ConnectionTimeoutTest.java
git commit -m "Add integration tests for connection timeout"

git add documents/configuration/ojp-jdbc-configuration.md
git commit -m "Document connectionTimeout property"

# Test thoroughly
mvn clean install
cd ojp-jdbc-driver
mvn test -DenableH2Tests=true -DenablePostgresTests=true

# Push and create PR
git push origin feature/add-connection-timeout
```

### Scenario 3: Updating Documentation

You found unclear or outdated documentation.

```bash
# Sync and create branch
git checkout main
git pull upstream main
git checkout -b docs/improve-multinode-guide

# Update documentation
# Edit documents/multinode/README.md
# Add troubleshooting section, clarify configuration steps

# Commit
git add documents/multinode/README.md
git commit -m "Improve multinode setup documentation

- Add troubleshooting section for common issues
- Clarify server discovery configuration
- Add diagrams showing failover behavior
- Update example configurations for clarity

Fixes #298"

# No tests needed for docs-only changes
# Push and create PR
git push origin docs/improve-multinode-guide
```

---

## 16.15 Git Workflow Summary

Here's a quick reference for the complete Git workflow:

**Initial Setup** (once):
```bash
git clone https://github.com/YOUR-USERNAME/ojp.git
cd ojp
git remote add upstream https://github.com/Open-J-Proxy/ojp.git
```

**Starting New Work**:
```bash
git checkout main
git pull upstream main
git checkout -b feature/your-feature-name
```

**Making Changes**:
```bash
# Make changes, then:
git add .
git commit -m "Clear, descriptive commit message"
git push origin feature/your-feature-name
```

**Updating Your Branch**:
```bash
git fetch upstream
git merge upstream/main  # or: git rebase upstream/main
git push origin feature/your-feature-name
```

**After Merge**:
```bash
git checkout main
git pull upstream main
git push origin main
git branch -d feature/your-feature-name
```

---

## 16.16 Next Steps

Understanding the Git workflow is the foundation of contributing to OJP. In the next chapter, we'll dive deeper into testing strategies, code quality standards, and how to write tests that ensure your contributions are robust and maintainable.

Remember: contributing to open source is a journey. Your first PR might feel intimidating, but each contribution makes you more confident and skilled. The OJP community is here to help you succeed. Don't hesitate to ask questions in discussions or Discord—we were all beginners once.

---

**AI Image Prompts for Chapter 17:**

1. **Git Fork Workflow Diagram**: "Professional technical diagram showing GitHub fork-based workflow with upstream repository, contributor fork, feature branch, pull request, and merge flow. Include arrows showing sync and contribution paths. Clean, modern design with GitHub colors."

2. **Branch Strategy Visualization**: "Timeline diagram showing main branch with feature branches branching off and merging back. Show multiple contributors working on different features simultaneously. Include labels for branch names and PR merge points. Technical illustration style."

3. **PR Lifecycle Flowchart**: "Flowchart showing pull request lifecycle from creation through review, CI checks, feedback iteration, approval, and merge. Include decision points and feedback loops. Professional diagram style with clear process flow."

4. **Git Command Reference Poster**: "Infographic poster with commonly used Git commands organized by category (branching, syncing, committing, reviewing). Include command syntax and brief descriptions. Designer-friendly layout with monospace fonts."

5. **Merge Conflict Resolution Guide**: "Step-by-step visual guide showing how to resolve merge conflicts. Include editor screenshots with conflict markers, resolution process, and final result. Technical documentation style."

6. **Contribution Checklist Infographic**: "Checklist-style infographic showing steps for successful contribution: sync fork, create branch, make changes, test, push, create PR, respond to review. Include checkboxes and icons. Modern, clean design."
