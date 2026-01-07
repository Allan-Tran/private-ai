package com.privateai.vault.features.sessionanalyst

/**
 * SESSION ANALYST FEATURE SLICE
 *
 * This demonstrates Vertical Slice Architecture where all layers
 * (Data, Domain, UI) for a single feature live together.
 *
 * Use Case: Boxing Coach analyzes training sessions using private AI.
 *
 * Workflow:
 * 1. Coach uploads session videos/notes to Active Desk
 * 2. Content is chunked and embedded locally
 * 3. Coach asks questions about fighter performance
 * 4. AI retrieves relevant context via RAG and generates insights
 * 5. All data stays on device - complete sovereignty
 */

// This file serves as the entry point and documentation for the slice
