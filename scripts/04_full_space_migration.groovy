// Script 4 — Full Space Migration
// Run in: ScriptRunner > Script Console (Confluence Cloud)
// Purpose: Migrates ALL pages in a space, replacing broken DC macros with
//          Cloud-native equivalents. Supports dry-run mode.
// Requires: MacroMigrator saved in Script Manager at confluence/migration/MacroMigrator.groovy

import Confluence.migration.MacroMigrator
import groovy.json.JsonOutput
import java.util.regex.Matcher

// =============================================================================
// CONFIGURATION — edit before running
// =============================================================================
String  SPACE_KEY  = 'DEMO'   // Confluence space key to migrate
boolean DRY_RUN    = true    // Set to false to actually save changes
int     PAGE_LIMIT = 50      // Pages per API request (max 250)
long    DELAY_MS   = 200L    // Milliseconds to wait between page updates
// =============================================================================

// ---------------------------------------------------------------------------
// Step 1: Resolve space key → numeric space ID
// ---------------------------------------------------------------------------
def spaceResp = get('/wiki/api/v2/spaces')
    .header('Content-Type', 'application/json')
    .queryString('keys', SPACE_KEY)
    .asObject(Map)

if (spaceResp.status != 200) {
    logger.error("Failed to look up space '${SPACE_KEY}': HTTP ${spaceResp.status}")
    return
}

Map spaceData  = spaceResp.body as Map
List spaceList = spaceData.results as List
if (!spaceList) {
    logger.error("Space '${SPACE_KEY}' not found. Check the space key.")
    return
}

String spaceId   = ((spaceList[0] as Map).id as String)
String spaceName = ((spaceList[0] as Map).name as String)
logger.info("=== Full Space Migration ===")
logger.info("Space : '${spaceName}' (${SPACE_KEY}) → ID ${spaceId}")
logger.info("Mode  : ${DRY_RUN ? 'DRY RUN (no changes will be saved)' : 'LIVE — changes WILL be saved'}")
logger.info("Limit : ${PAGE_LIMIT} pages/request | Delay: ${DELAY_MS}ms between updates")
logger.info("=".multiply(60))

// ---------------------------------------------------------------------------
// Step 2: Paginate through all pages in the space
// ---------------------------------------------------------------------------
int pagesSeen    = 0
int pagesChanged = 0
int pagesSkipped = 0
int errors       = 0
String cursor    = null

while (true) {
    // Build request — add cursor param only when paginating
    def req = get("/wiki/api/v2/spaces/${spaceId}/pages")
        .header('Content-Type', 'application/json')
        .queryString('body-format', 'storage')
        .queryString('limit', PAGE_LIMIT as String)

    if (cursor) {
        req = req.queryString('cursor', cursor)
    }

    def listResp = req.asObject(Map)

    if (listResp.status != 200) {
        logger.error("Failed to list pages (cursor=${cursor}): HTTP ${listResp.status}")
        break
    }

    Map  listData = listResp.body as Map
    List pages    = listData.results as List

    if (!pages) {
        break
    }

    // -----------------------------------------------------------------------
    // Step 3: Process each page on this batch
    // -----------------------------------------------------------------------
    for (Object pageObj : pages) {
        Map page    = pageObj as Map
        String pid  = page.id as String
        String ptitle = page.title as String
        int pversion  = ((page.version as Map)?.number as Integer) ?: 1
        String pbody  = ((page.body as Map)?.storage as Map)?.value as String

        pagesSeen++

        if (!pbody) {
            pagesSkipped++
            continue
        }

        // Quick check — skip pages with no target macros
        boolean hasMacros =
            pbody.contains('ac:name="style"')     ||
            pbody.contains('ac:name="div"')       ||
            pbody.contains('ac:name="alert"')     ||
            pbody.contains('ac:name="auibutton"') ||
            pbody.contains('ac:name="lozenge"')

        if (!hasMacros) {
            pagesSkipped++
            continue
        }

        // Run transformation
        Map<String, Object> result = MacroMigrator.transformBody(pbody)
        String newBody              = result.body as String
        boolean changed             = result.changed as boolean
        List<String> transforms     = result.transforms as List<String>

        if (!changed) {
            pagesSkipped++
            continue
        }

        if (DRY_RUN) {
            logger.info("[DRY RUN] Would update: '${ptitle}' (ID: ${pid}) — ${transforms.join(', ')}")
            pagesChanged++
            continue
        }

        // Live update
        Map payload = [
            id     : pid,
            status : 'current',
            title  : ptitle,
            version: [number: pversion + 1],
            body   : [representation: 'storage', value: newBody]
        ]

        def putResp = put("/wiki/api/v2/pages/${pid}")
            .header('Content-Type', 'application/json')
            .body(JsonOutput.toJson(payload))
            .asObject(Map)

        if (putResp.status == 200) {
            logger.info("[UPDATE] '${ptitle}' (ID: ${pid}) v${pversion}→v${pversion + 1} — ${transforms.join(', ')}")
            pagesChanged++
        } else {
            logger.error("[ERROR] '${ptitle}' (ID: ${pid}): HTTP ${putResp.status} — ${putResp.body}")
            errors++
        }

        // Polite delay between writes to avoid rate-limiting
        Thread.sleep(DELAY_MS)
    }

    // -----------------------------------------------------------------------
    // Step 4: Advance cursor for next page of results
    // -----------------------------------------------------------------------
    Map links = listData['_links'] as Map
    String next = links ? (links.next as String) ?: '' : ''

    if (!next) {
        break
    }

    // Extract cursor value from the next URL
    // e.g. /wiki/api/v2/spaces/12345/pages?cursor=ABC123&limit=50
    Matcher cm = (next =~ /[?&]cursor=([^&]+)/)
    cursor = cm.find() ? cm.group(1) : null

    if (!cursor) {
        break
    }
}

// ---------------------------------------------------------------------------
// Step 5: Final summary
// ---------------------------------------------------------------------------
logger.info("=".multiply(60))
logger.info("=== Migration Summary ===")
logger.info("Space        : ${SPACE_KEY} (${spaceName})")
logger.info("Mode         : ${DRY_RUN ? 'DRY RUN' : 'LIVE'}")
logger.info("Pages seen   : ${pagesSeen}")
logger.info("${DRY_RUN ? 'Would update' : 'Updated'}    : ${pagesChanged}")
logger.info("Skipped      : ${pagesSkipped}")
logger.info("Errors       : ${errors}")
if (DRY_RUN) {
    logger.info(">>> Set DRY_RUN = false and re-run to apply ${pagesChanged} change(s).")
}
logger.info("=".multiply(60))
