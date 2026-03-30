// Script 3 — UAT Single Page Migration
// Run in: ScriptRunner > Script Console (Confluence Cloud)
// Purpose: Dry-run or live migration of a single page. Use this to validate
//          the transformation on one page before running the full space migration.
// Requires: MacroMigrator saved in Script Manager at confluence/migration/MacroMigrator.groovy

import Confluence.migration.MacroMigrator
import groovy.json.JsonOutput

// =============================================================================
// CONFIGURATION — edit before running
// =============================================================================
String  PAGE_ID = '123456789'   // Replace with the numeric Confluence page ID
boolean DRY_RUN = true          // Set to false to actually save the changes
// =============================================================================

// ---------------------------------------------------------------------------
// Step 1: Fetch the page with storage body
// ---------------------------------------------------------------------------
def pageResp = get("/wiki/api/v2/pages/${PAGE_ID}")
    .header('Content-Type', 'application/json')
    .queryString('body-format', 'storage')
    .asObject(Map)

if (pageResp.status != 200) {
    logger.error("Failed to fetch page ${PAGE_ID}: HTTP ${pageResp.status}")
    return
}

Map  pageData = pageResp.body as Map
String title  = pageData.title as String
int version   = ((pageData.version as Map)?.number as Integer) ?: 1
String body   = ((pageData.body as Map)?.storage as Map)?.value as String

if (!body) {
    logger.warn("Page ${PAGE_ID} ('${title}') has no storage body — skipping.")
    return
}

logger.info("=== UAT: '${title}' (ID: ${PAGE_ID}, version: ${version}) ===")

// ---------------------------------------------------------------------------
// Step 2: Quick check — does this page contain any target macros?
// ---------------------------------------------------------------------------
boolean hasMacros =
    body.contains('ac:name="style"')    ||
    body.contains('ac:name="div"')      ||
    body.contains('ac:name="alert"')    ||
    body.contains('ac:name="auibutton"') ||
    body.contains('ac:name="lozenge"')

if (!hasMacros) {
    logger.info("No target DC macros found on this page — nothing to migrate.")
    return
}

// ---------------------------------------------------------------------------
// Step 3: Log BEFORE body (first 3000 chars)
// ---------------------------------------------------------------------------
logger.info('--- BEFORE (first 3000 chars) ---')
logger.info(body.length() > 3000 ? body.substring(0, 3000) + '...[truncated]' : body)

// ---------------------------------------------------------------------------
// Step 4: Run transformation
// ---------------------------------------------------------------------------
Map<String, Object> result = MacroMigrator.transformBody(body)
String newBody              = result.body as String
boolean changed             = result.changed as boolean
List<String> transforms     = result.transforms as List<String>

// ---------------------------------------------------------------------------
// Step 5: Log AFTER body (first 3000 chars) and applied transforms
// ---------------------------------------------------------------------------
logger.info('--- AFTER (first 3000 chars) ---')
logger.info(newBody.length() > 3000 ? newBody.substring(0, 3000) + '...[truncated]' : newBody)

if (changed) {
    logger.info("Transforms applied (${transforms.size()}):")
    for (String t : transforms) {
        logger.info("  • ${t}")
    }
} else {
    logger.info('No changes produced by MacroMigrator (body unchanged).')
    return
}

// ---------------------------------------------------------------------------
// Step 6: DRY_RUN gate
// ---------------------------------------------------------------------------
if (DRY_RUN) {
    logger.info('[DRY RUN] Changes NOT saved. Set DRY_RUN = false to apply.')
    return
}

// ---------------------------------------------------------------------------
// Step 7: Save the updated page via PUT /wiki/api/v2/pages/{id}
// ---------------------------------------------------------------------------
Map payload = [
    id     : PAGE_ID,
    status : 'current',
    title  : title,
    version: [number: version + 1],
    body   : [representation: 'storage', value: newBody]
]

def putResp = put("/wiki/api/v2/pages/${PAGE_ID}")
    .header('Content-Type', 'application/json')
    .body(JsonOutput.toJson(payload))
    .asObject(Map)

if (putResp.status == 200) {
    logger.info("[UPDATE] '${title}' saved successfully at version ${version + 1}.")
} else {
    logger.error("[ERROR] Failed to update '${title}': HTTP ${putResp.status} — ${putResp.body}")
}
