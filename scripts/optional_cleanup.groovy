// 05_cleanup.groovy
// Run in: ScriptRunner > Script Console (Confluence Cloud)
// Purpose: Deletes all [MIGRATION TEST] pages from the test space.
//          Run after verification is complete to tidy up.
// WARNING: Deletion is permanent. Ensure Script 04 has passed before running.

// =============================================================================
// CONFIGURATION — edit before running
// =============================================================================
String TEST_SPACE_KEY = 'DEMO'
// =============================================================================

// ---------------------------------------------------------------------------
// Step 1: Resolve space key → numeric space ID
// ---------------------------------------------------------------------------
def spaceResp = get('/wiki/api/v2/spaces')
    .header('Content-Type', 'application/json')
    .queryString('keys', TEST_SPACE_KEY)
    .asObject(Map)

if (spaceResp.status != 200) {
    logger.error("Failed to look up space '${TEST_SPACE_KEY}': HTTP ${spaceResp.status}")
    return
}

Map spaceData  = spaceResp.body as Map
List spaceList = spaceData.results as List
if (!spaceList) {
    logger.error("Space '${TEST_SPACE_KEY}' not found.")
    return
}
String spaceId = ((spaceList[0] as Map).id as String)
logger.info("Resolved space '${TEST_SPACE_KEY}' → ID ${spaceId}")

// ---------------------------------------------------------------------------
// Step 2: Collect all [MIGRATION TEST] pages
// ---------------------------------------------------------------------------
List<Map> testPages = new ArrayList<Map>()
String cursor = null

while (true) {
    def req = get("/wiki/api/v2/spaces/${spaceId}/pages")
        .header('Content-Type', 'application/json')
        .queryString('limit', '50')

    if (cursor) {
        req = req.queryString('cursor', cursor)
    }

    def listResp = req.asObject(Map)
    if (listResp.status != 200) {
        logger.error("Failed to list pages: HTTP ${listResp.status}")
        break
    }

    Map listData = listResp.body as Map
    List pages   = listData.results as List

    for (Object p : pages) {
        Map pm = p as Map
        String t = pm.title as String
        if (t && t.contains('[MIGRATION TEST]')) {
            testPages.add(pm)
        }
    }

    Map links   = listData['_links'] as Map
    String next = links ? (links.next as String) ?: '' : ''
    if (!next) { break }

    java.util.regex.Matcher cm = (next =~ /[?&]cursor=([^&]+)/)
    cursor = cm.find() ? cm.group(1) : null
    if (!cursor) { break }
}

logger.info("Found ${testPages.size()} [MIGRATION TEST] page(s) to delete.")

if (!testPages) {
    logger.info("Nothing to delete — all test pages already removed.")
    return
}

// ---------------------------------------------------------------------------
// Step 3: Delete each test page
// ---------------------------------------------------------------------------
int deleted = 0
int errors  = 0

for (Map page : testPages) {
    String pid    = page.id as String
    String ptitle = page.title as String

    def delResp = delete("/wiki/api/v2/pages/${pid}")
        .asObject(Map)

    int delStatus = delResp.status
    if (delStatus == 204) {
        logger.info("[DELETED] '${ptitle}' (ID: ${pid})")
        deleted++
    } else {
        logger.error("[ERROR] Failed to delete '${ptitle}' (ID: ${pid}): HTTP ${delStatus}")
        errors++
    }
}

// ---------------------------------------------------------------------------
// Step 4: Summary
// ---------------------------------------------------------------------------
logger.info("=".multiply(60))
logger.info("=== Clean Up Complete ===")
logger.info("Deleted : ${deleted}")
logger.info("Errors  : ${errors}")
logger.info("=".multiply(60))
