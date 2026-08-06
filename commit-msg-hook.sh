#!/bin/bash

# =============================================================================
# COMMIT MESSAGE HOOK - CONVENTIONAL COMMITS VALIDATOR
# =============================================================================
# This hook enforces strict Conventional Commits format for all commits.
# Install: cp commit-msg .git/hooks/commit-msg && chmod +x .git/hooks/commit-msg
# =============================================================================

COMMIT_MSG_FILE=$1
COMMIT_MSG=$(cat "$COMMIT_MSG_FILE")

# Allowed types according to Conventional Commits specification
ALLOWED_TYPES="feat|fix|docs|style|refactor|perf|test|chore|revert"

# Regex pattern for Conventional Commits
# Format: <type>(<scope>): <description> OR <type>: <description>
PATTERN="^($ALLOWED_TYPES)(\([a-zA-Z0-9_-]+\))?: [A-Z].*$"

# Check if commit message matches the pattern
if ! echo "$COMMIT_MSG" | grep -Eq "$PATTERN"; then
    echo ""
    echo "❌ ERROR: Invalid commit message format!"
    echo ""
    echo "📋 Conventional Commits format required:"
    echo "   <type>(<scope>): <description>"
    echo ""
    echo "✅ Allowed types:"
    echo "   feat     - New feature"
    echo "   fix      - Bug fix"
    echo "   docs     - Documentation changes"
    echo "   style    - Code style changes (formatting, semicolons, etc.)"
    echo "   refactor - Code refactoring (no feature changes or bug fixes)"
    echo "   perf     - Performance improvements"
    echo "   test     - Adding or updating tests"
    echo "   chore    - Maintenance tasks, dependencies"
    echo "   revert   - Reverting previous commits"
    echo ""
    echo "📝 Examples:"
    echo "   feat(auth): Add user login functionality"
    echo "   fix: Resolve null pointer exception in UserService"
    echo "   docs: Update API documentation"
    echo "   refactor(user): Simplify validation logic"
    echo "   perf: Optimize database queries"
    echo "   test: Add unit tests for PaymentService"
    echo "   chore: Update dependencies"
    echo "   revert: Revert \"feat: Add experimental feature\""
    echo ""
    echo "⚠️  Rules:"
    echo "   - Type must be lowercase"
    echo "   - Description must start with uppercase letter"
    echo "   - Use imperative mood in description (e.g., 'Add' not 'Added')"
    echo "   - No period at the end of the description"
    echo ""
    exit 1
fi

# Additional checks
# Check for period at the end
if echo "$COMMIT_MSG" | head -1 | grep -q '\.$'; then
    echo ""
    echo "❌ ERROR: Commit message should not end with a period"
    echo ""
    exit 1
fi

# Check if description starts with uppercase
FIRST_CHAR=$(echo "$COMMIT_MSG" | head -1 | sed -E "s/^($ALLOWED_TYPES)(\([a-zA-Z0-9_-]+\))?: ([A-Za-z]).*/\3/")
if [[ ! $FIRST_CHAR =~ [A-Z] ]]; then
    echo ""
    echo "❌ ERROR: Description must start with an uppercase letter"
    echo ""
    exit 1
fi

echo "✅ Commit message format validated successfully"
exit 0
