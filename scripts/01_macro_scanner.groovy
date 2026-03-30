// 01_macro_scanner.groovy
// Run in: ScriptRunner > Script Console (Confluence Cloud)
// Purpose: Scans every page in a space, extracts all ac:structured-macro names,
//          counts occurrences, and flags macros known to be unsupported in Cloud.
//          Run this FIRST to get a complete picture before running any migration.
// No library import needed — this script is self-contained.

import java.util.regex.Matcher
import java.util.regex.Pattern

// =============================================================================
// CONFIGURATION — edit before running
// =============================================================================
String SPACE_KEY  = 'DEMO'
int    PAGE_LIMIT = 50      // Pages per API request (max 250)
// =============================================================================

// ---------------------------------------------------------------------------
// Known unsupported DC macros — no direct Cloud equivalent
// ---------------------------------------------------------------------------
Set<String> UNSUPPORTED = new HashSet<String>([
    // Handled by MacroMigrator (scripts 02/03)
    'style', 'div', 'alert', 'auibutton', 'lozenge',
    // Layout macros — Cloud uses native page layouts, not macros
    'section', 'column',
    // Raw content injection — blocked in Cloud
    'html', 'widget',
    // Explicit migration failure marker inserted by Atlassian's migrator
    'unmigrated-wiki-markup',
    // Third-party / DC-only macros
    'content-reporter', 'navmap', 'im-presence',
    'pagetreesearch', 'spaces', 'profile',
    // Adaptavist / Bob Swift DC macros
    'sql', 'sql-query', 'table-filter', 'table-transformer',
    'hide', 'show-if', 'hide-if',
    // Scroll Viewport / Scroll Exporter (K15t)
    'scroll-ignore', 'scroll-only', 'scroll-title',
    // Brikit / Refined theme macros
    'brikit-theme-press', 'refined-theme'
])

// ---------------------------------------------------------------------------
// Cloud-native macros — safe, no action needed
// ---------------------------------------------------------------------------
Set<String> CLOUD_NATIVE = new HashSet<String>([
    'panel', 'info', 'warning', 'note', 'tip', 'expand',
    'recently-updated', 'recently-updated-dashboard', 'blog-posts',
    'pagetree', 'children', 'include', 'excerpt', 'excerpt-include',
    'content-by-label', 'toc', 'table-of-contents', 'anchor',
    'code', 'noformat', 'attachments', 'gallery', 'jira',
    'status', 'roadmap', 'details', 'details-summary',
    'contributors', 'contributors-summary', 'change-history',
    'livesearch', 'search', 'labels-list', 'popular-labels',
    'related-labels', 'recently-used-labels', 'space-details',
    'space-attachments', 'space-jump', 'create-space-button',
    'create-from-template', 'tasklist', 'loremipsum',
    'viewfile', 'widget', 'iframe', 'html-include'
])

// ---------------------------------------------------------------------------
// Pattern to extract macro names from storage XML
// ---------------------------------------------------------------------------
Pattern MACRO_NAME_PAT = Pattern.compile(
    '<ac:structured-macro[^>]*\\bac:name="([^"]+)"'
)

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
logger.info("=== Macro Scanner ===")
logger.info("Space : '${spaceName}' (${SPACE_KEY}) → ID ${spaceId}")
logger.info("=".multiply(60))

// ---------------------------------------------------------------------------
// Step 2: Paginate through all pages and collect macro data
// ---------------------------------------------------------------------------

// globalCounts : macroName → total occurrences across all pages
Map<String, Integer> globalCounts = new TreeMap<String, Integer>()

// macroToPages : macroName → list of "PageTitle (ID: xxx)" strings
Map<String, List<String>> macroToPages = new TreeMap<String, List<String>>()

int pagesSeen            = 0
int pagesWithUnsupported = 0
String cursor            = null

while (true) {
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
    if (!pages) { break }

    for (Object pageObj : pages) {
        Map    page   = pageObj as Map
        String pid    = page.id as String
        String ptitle = page.title as String
        String pbody  = ((page.body as Map)?.storage as Map)?.value as String

        pagesSeen++

        if (!pbody) { continue }

        // Extract all macro names on this page
        Map<String, Integer> pageCounts = new TreeMap<String, Integer>()
        Matcher m = MACRO_NAME_PAT.matcher(pbody)
        while (m.find()) {
            String name = m.group(1)
            int current = pageCounts.containsKey(name) ?
                (pageCounts.get(name) as int) : 0
            pageCounts.put(name, current + 1)
        }

        if (pageCounts.isEmpty()) { continue }

        // Merge into global counts and page lists
        boolean hasUnsupported = false
        for (Map.Entry<String, Integer> entry : pageCounts.entrySet()) {
            String name  = entry.key
            int    count = entry.value

            int globalCurrent = globalCounts.containsKey(name) ?
                (globalCounts.get(name) as int) : 0
            globalCounts.put(name, globalCurrent + count)

            if (UNSUPPORTED.contains(name)) {
                hasUnsupported = true
                List<String> pageList = macroToPages.containsKey(name) ?
                    macroToPages.get(name) : new ArrayList<String>()
                pageList.add("'${ptitle}' (ID: ${pid}) [x${count}]".toString())
                macroToPages.put(name, pageList)
            }
        }

        if (hasUnsupported) { pagesWithUnsupported++ }
    }

    // Advance cursor
    Map links   = listData['_links'] as Map
    String next = links ? (links.next as String) ?: '' : ''
    if (!next) { break }

    Matcher cm = (next =~ /[?&]cursor=([^&]+)/)
    cursor = cm.find() ? cm.group(1) : null
    if (!cursor) { break }
}

// ---------------------------------------------------------------------------
// Step 3: Report — Unsupported macros (action required)
// ---------------------------------------------------------------------------
logger.info('')
logger.info('>>> UNSUPPORTED MACROS (action required)')
logger.info('-'.multiply(60))

int totalUnsupportedInstances = 0
List<String> unsupportedFound = new ArrayList<String>()

for (String name : UNSUPPORTED) {
    if (globalCounts.containsKey(name)) {
        int count = globalCounts.get(name) as int
        totalUnsupportedInstances += count
        unsupportedFound.add(name)
        List<String> affectedPages = macroToPages.get(name) ?: new ArrayList<String>()
        logger.warn("  [UNSUPPORTED] ac:name=\"${name}\" — ${count} instance(s) on ${affectedPages.size()} page(s)")
        for (String pg : affectedPages) {
            logger.warn("    • ${pg}")
        }
    }
}

if (unsupportedFound.isEmpty()) {
    logger.info('  None found — no unsupported macros detected!')
}

// ---------------------------------------------------------------------------
// Step 4: Report — Unknown macros (investigate)
// ---------------------------------------------------------------------------
logger.info('')
logger.info('>>> UNKNOWN MACROS (investigate — may be third-party or custom)')
logger.info('-'.multiply(60))

boolean anyUnknown = false
for (Map.Entry<String, Integer> entry : globalCounts.entrySet()) {
    String name  = entry.key
    int    count = entry.value
    if (!UNSUPPORTED.contains(name) && !CLOUD_NATIVE.contains(name)) {
        logger.warn("  [UNKNOWN] ac:name=\"${name}\" — ${count} instance(s)")
        anyUnknown = true
    }
}
if (!anyUnknown) {
    logger.info('  None found — all macros are either known-supported or known-unsupported.')
}

// ---------------------------------------------------------------------------
// Step 5: Report — Cloud-native macros (safe, no action needed)
// ---------------------------------------------------------------------------
logger.info('')
logger.info('>>> CLOUD-NATIVE MACROS (safe — no action needed)')
logger.info('-'.multiply(60))

for (Map.Entry<String, Integer> entry : globalCounts.entrySet()) {
    String name  = entry.key
    int    count = entry.value
    if (CLOUD_NATIVE.contains(name)) {
        logger.info("  [OK] ac:name=\"${name}\" — ${count} instance(s)")
    }
}

// ---------------------------------------------------------------------------
// Step 6: Full macro inventory (all macros, sorted by count desc)
// ---------------------------------------------------------------------------
logger.info('')
logger.info('>>> FULL MACRO INVENTORY (all macros found, sorted by count)')
logger.info('-'.multiply(60))

List<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(
    globalCounts.entrySet()
)
sorted.sort { Map.Entry<String, Integer> a, Map.Entry<String, Integer> b ->
    (b.value as int) <=> (a.value as int)
}

for (Map.Entry<String, Integer> entry : sorted) {
    String name  = entry.key
    int    count = entry.value
    String tag   = UNSUPPORTED.contains(name) ? '[UNSUPPORTED]' :
                   CLOUD_NATIVE.contains(name) ? '[OK]' : '[UNKNOWN]'
    logger.info("  ${tag.padRight(14)} ac:name=\"${name}\" — ${count}")
}

// ---------------------------------------------------------------------------
// Step 7: Summary
// ---------------------------------------------------------------------------
logger.info('')
logger.info('='.multiply(60))
logger.info('=== Scan Summary ===')
logger.info("Space                    : ${SPACE_KEY} (${spaceName})")
logger.info("Pages scanned            : ${pagesSeen}")
logger.info("Pages with unsupported   : ${pagesWithUnsupported}")
logger.info("Unsupported macro types  : ${unsupportedFound.size()}")
logger.info("Unsupported instances    : ${totalUnsupportedInstances}")
logger.info("Distinct macro types     : ${globalCounts.size()}")
logger.info('')
if (totalUnsupportedInstances > 0) {
    logger.warn(">>> ACTION NEEDED: Run script 03 (Full Space Migration) to fix")
    logger.warn("    the macros handled by MacroMigrator:")
    logger.warn("    style, div, alert, auibutton, lozenge")
    logger.warn("    For section/column/html/widget — see docs/dc-macro-reference.md")
} else {
    logger.info(">>> No unsupported macros found. Space is Cloud-ready!")
}
logger.info('='.multiply(60))
