// 05_verify_results.groovy
// Run in: ScriptRunner > Script Console (Confluence Cloud)
// Purpose: Fetches all [MIGRATION TEST] pages and runs 41 explicit pass/fail
//          checks to confirm the migration produced correct output.
// Run AFTER 04_full_space_migration.groovy in LIVE mode.
// No library import needed — this script is read-only.

// =============================================================================
// CONFIGURATION — edit before running
// =============================================================================
String TEST_SPACE_KEY = 'YOUR_SPACE_KEY'
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

// ---------------------------------------------------------------------------
// Step 2: Fetch all pages in the space with storage body
// ---------------------------------------------------------------------------
List<Map> testPages = new ArrayList<Map>()
String cursor = null

while (true) {
    def req = get("/wiki/api/v2/spaces/${spaceId}/pages")
        .header('Content-Type', 'application/json')
        .queryString('body-format', 'storage')
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

logger.info("=== Verify Migration Results ===")
logger.info("Found ${testPages.size()} [MIGRATION TEST] pages in space '${TEST_SPACE_KEY}'")
logger.info("=".multiply(60))

// ---------------------------------------------------------------------------
// Counters
// ---------------------------------------------------------------------------
int passed = 0
int failed = 0

// ---------------------------------------------------------------------------
// Step 3: Run checks for each test page
// ---------------------------------------------------------------------------
for (Map page : testPages) {
    String title   = page.title as String
    int version    = ((page.version as Map)?.number as Integer) ?: 1
    String body    = ((page.body as Map)?.storage as Map)?.value as String ?: ''

    logger.info("--- Checking: '${title}' (v${version}) ---")

    // =========================================================================
    // STYLE PAGE (4 checks)
    // =========================================================================
    if (title.contains('Style Macro Page')) {

        if (!body.contains('ac:name="style"')) {
            passed++; logger.info('  PASS [1/4] style macro removed')
        } else {
            failed++; logger.warn('  FAIL [1/4] style macro still present')
        }

        if (body.contains('ac:name="info"')) {
            passed++; logger.info('  PASS [2/4] info panel added')
        } else {
            failed++; logger.warn('  FAIL [2/4] info panel not found')
        }

        if (body.contains('Migration note:')) {
            passed++; logger.info('  PASS [3/4] migration note text present')
        } else {
            failed++; logger.warn('  FAIL [3/4] migration note text missing')
        }

        if (version >= 2) {
            passed++; logger.info('  PASS [4/4] version >= 2')
        } else {
            failed++; logger.warn("  FAIL [4/4] version is ${version}, expected >= 2")
        }
    }

    // =========================================================================
    // DIV PAGE — Nested (7 checks)
    // =========================================================================
    else if (title.contains('Div Macro Page')) {

        if (!body.contains('ac:name="div"')) {
            passed++; logger.info('  PASS [1/7] div macro removed')
        } else {
            failed++; logger.warn('  FAIL [1/7] div macro still present')
        }

        if (body.contains('ac:name="panel"')) {
            passed++; logger.info('  PASS [2/7] panel macro added')
        } else {
            failed++; logger.warn('  FAIL [2/7] panel macro not found')
        }

        if (body.contains('ac:name="borderColor"')) {
            passed++; logger.info('  PASS [3/7] borderColor parameter present')
        } else {
            failed++; logger.warn('  FAIL [3/7] borderColor parameter missing')
        }

        if (body.contains('ac:name="bgColor"')) {
            passed++; logger.info('  PASS [4/7] bgColor parameter present')
        } else {
            failed++; logger.warn('  FAIL [4/7] bgColor parameter missing')
        }

        if (body.contains('ac:name="code"')) {
            passed++; logger.info('  PASS [5/7] nested code macro preserved')
        } else {
            failed++; logger.warn('  FAIL [5/7] nested code macro lost')
        }

        if (body.contains('OPERATION UPDATES')) {
            passed++; logger.info('  PASS [6/7] OPERATION UPDATES text preserved')
        } else {
            failed++; logger.warn('  FAIL [6/7] OPERATION UPDATES text missing')
        }

        if (version >= 2) {
            passed++; logger.info('  PASS [7/7] version >= 2')
        } else {
            failed++; logger.warn("  FAIL [7/7] version is ${version}, expected >= 2")
        }
    }

    // =========================================================================
    // ALERT PAGE (5 checks)
    // =========================================================================
    else if (title.contains('Alert Macro Page')) {

        if (!body.contains('ac:name="alert"')) {
            passed++; logger.info('  PASS [1/5] alert macro removed')
        } else {
            failed++; logger.warn('  FAIL [1/5] alert macro still present')
        }

        if (body.contains('ac:name="panel"')) {
            passed++; logger.info('  PASS [2/5] panel macro added')
        } else {
            failed++; logger.warn('  FAIL [2/5] panel macro not found')
        }

        if (body.contains("TODAY'S DRIVERS")) {
            passed++; logger.info("  PASS [3/5] title \"TODAY'S DRIVERS\" preserved")
        } else {
            failed++; logger.warn("  FAIL [3/5] title \"TODAY'S DRIVERS\" missing")
        }

        if (body.contains('DO NOT MESSAGE')) {
            passed++; logger.info('  PASS [4/5] DO NOT MESSAGE content preserved')
        } else {
            failed++; logger.warn('  FAIL [4/5] DO NOT MESSAGE content missing')
        }

        if (version >= 2) {
            passed++; logger.info('  PASS [5/5] version >= 2')
        } else {
            failed++; logger.warn("  FAIL [5/5] version is ${version}, expected >= 2")
        }
    }

    // =========================================================================
    // AUI BUTTON PAGE (6 checks)
    // =========================================================================
    else if (title.contains('AUI Button Macro Page')) {

        if (!body.contains('ac:name="auibutton"')) {
            passed++; logger.info('  PASS [1/6] auibutton macro removed')
        } else {
            failed++; logger.warn('  FAIL [1/6] auibutton macro still present')
        }

        if (body.contains('<a href=')) {
            passed++; logger.info('  PASS [2/6] hyperlink added')
        } else {
            failed++; logger.warn('  FAIL [2/6] hyperlink not found')
        }

        if (body.contains('Add Post')) {
            passed++; logger.info('  PASS [3/6] "Add Post" title preserved')
        } else {
            failed++; logger.warn('  FAIL [3/6] "Add Post" title missing')
        }

        if (body.contains('confluence.example.com')) {
            passed++; logger.info('  PASS [4/6] DC URL preserved')
        } else {
            failed++; logger.warn('  FAIL [4/6] DC URL missing')
        }

        if (body.contains('View More')) {
            passed++; logger.info('  PASS [5/6] "View More" title preserved')
        } else {
            failed++; logger.warn('  FAIL [5/6] "View More" title missing')
        }

        if (version >= 2) {
            passed++; logger.info('  PASS [6/6] version >= 2')
        } else {
            failed++; logger.warn("  FAIL [6/6] version is ${version}, expected >= 2")
        }
    }

    // =========================================================================
    // LOZENGE PAGE (6 checks)
    // =========================================================================
    else if (title.contains('Lozenge Macro Page')) {

        if (!body.contains('ac:name="lozenge"')) {
            passed++; logger.info('  PASS [1/6] lozenge macro removed')
        } else {
            failed++; logger.warn('  FAIL [1/6] lozenge macro still present')
        }

        if (body.contains('<a href=')) {
            passed++; logger.info('  PASS [2/6] hyperlink added')
        } else {
            failed++; logger.warn('  FAIL [2/6] hyperlink not found')
        }

        if (body.contains('Idea Box')) {
            passed++; logger.info('  PASS [3/6] "Idea Box" title preserved')
        } else {
            failed++; logger.warn('  FAIL [3/6] "Idea Box" title missing')
        }

        if (body.contains('Break Schedule')) {
            passed++; logger.info('  PASS [4/6] "Break Schedule" title preserved')
        } else {
            failed++; logger.warn('  FAIL [4/6] "Break Schedule" title missing')
        }

        if (body.contains('Send an idea')) {
            passed++; logger.info('  PASS [5/6] "Send an idea" description preserved')
        } else {
            failed++; logger.warn('  FAIL [5/6] "Send an idea" description missing')
        }

        if (version >= 2) {
            passed++; logger.info('  PASS [6/6] version >= 2')
        } else {
            failed++; logger.warn("  FAIL [6/6] version is ${version}, expected >= 2")
        }
    }

    // =========================================================================
    // REAL-WORLD COMBINED PAGE (10 checks)
    // =========================================================================
    else if (title.contains('Real-World Combined Page')) {

        if (!body.contains('ac:name="style"')) {
            passed++; logger.info('  PASS [1/10] style macro removed')
        } else {
            failed++; logger.warn('  FAIL [1/10] style macro still present')
        }

        if (!body.contains('ac:name="div"')) {
            passed++; logger.info('  PASS [2/10] div macro removed')
        } else {
            failed++; logger.warn('  FAIL [2/10] div macro still present')
        }

        if (!body.contains('ac:name="alert"')) {
            passed++; logger.info('  PASS [3/10] alert macro removed')
        } else {
            failed++; logger.warn('  FAIL [3/10] alert macro still present')
        }

        if (!body.contains('ac:name="auibutton"')) {
            passed++; logger.info('  PASS [4/10] auibutton macro removed')
        } else {
            failed++; logger.warn('  FAIL [4/10] auibutton macro still present')
        }

        if (!body.contains('ac:name="lozenge"')) {
            passed++; logger.info('  PASS [5/10] lozenge macro removed')
        } else {
            failed++; logger.warn('  FAIL [5/10] lozenge macro still present')
        }

        if (body.contains('ac:name="panel"')) {
            passed++; logger.info('  PASS [6/10] panel macro added')
        } else {
            failed++; logger.warn('  FAIL [6/10] panel macro not found')
        }

        if (body.contains('ac:name="info"')) {
            passed++; logger.info('  PASS [7/10] info panel added')
        } else {
            failed++; logger.warn('  FAIL [7/10] info panel not found')
        }

        if (body.contains('ac:name="code"')) {
            passed++; logger.info('  PASS [8/10] nested code macro preserved')
        } else {
            failed++; logger.warn('  FAIL [8/10] nested code macro lost')
        }

        if (body.contains('confluence.example.com')) {
            passed++; logger.info('  PASS [9/10] DC URL preserved')
        } else {
            failed++; logger.warn('  FAIL [9/10] DC URL missing')
        }

        if (version >= 2) {
            passed++; logger.info('  PASS [10/10] version >= 2')
        } else {
            failed++; logger.warn("  FAIL [10/10] version is ${version}, expected >= 2")
        }
    }

    // =========================================================================
    // CLEAN PAGE (3 checks) — must NOT have been modified
    // =========================================================================
    else if (title.contains('Clean Page')) {

        if (version == 1) {
            passed++; logger.info('  PASS [1/3] page not modified (still v1)')
        } else {
            failed++; logger.warn("  FAIL [1/3] page was modified (v${version}), expected v1")
        }

        if (body.contains('normal Cloud info panel')) {
            passed++; logger.info('  PASS [2/3] "normal Cloud info panel" text present')
        } else {
            failed++; logger.warn('  FAIL [2/3] "normal Cloud info panel" text missing')
        }

        if (body.contains('Cloud Panel')) {
            passed++; logger.info('  PASS [3/3] "Cloud Panel" text present')
        } else {
            failed++; logger.warn('  FAIL [3/3] "Cloud Panel" text missing')
        }
    }

    else {
        logger.warn("  SKIP — unrecognised test page title: '${title}'")
    }
}

// ---------------------------------------------------------------------------
// Step 4: Final result
// ---------------------------------------------------------------------------
int total = passed + failed
logger.info("=".multiply(60))
logger.info("=== Verification Complete ===")
logger.info("Total checks : ${total} / 41 expected")
logger.info("Passed       : ${passed}")
logger.info("Failed       : ${failed}")
if (failed == 0) {
    logger.info(">>> ALL CHECKS PASSED ✓ Migration is verified correct.")
} else {
    logger.warn(">>> ${failed} CHECK(S) FAILED — review FAIL lines above.")
}
logger.info("=".multiply(60))
