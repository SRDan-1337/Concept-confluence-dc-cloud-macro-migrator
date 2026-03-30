// Script  — Create Test Pages (OPTIONAL)
// Run in: ScriptRunner > Script Console (Confluence Cloud)
// Purpose: Creates 7 test pages in the configured space to validate the migration.
// Run BEFORE Script Full Space Migration, if you want to test

import groovy.json.JsonOutput

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

Map spaceData   = spaceResp.body as Map
List spaceList  = spaceData.results as List
if (!spaceList) {
    logger.error("Space '${TEST_SPACE_KEY}' not found. Check the space key.")
    return
}

String spaceId = ((spaceList[0] as Map).id as String)
logger.info("Resolved space '${TEST_SPACE_KEY}' → ID ${spaceId}")

// ---------------------------------------------------------------------------
// Helper: create a single page, log the result, and pause briefly
// ---------------------------------------------------------------------------
Closure<String> createPage = { String title, String bodyValue ->
    Map payload = [
        spaceId: spaceId,
        status : 'current',
        title  : title,
        body   : [representation: 'storage', value: bodyValue]
    ]
    def resp = post('/wiki/api/v2/pages')
        .header('Content-Type', 'application/json')
        .body(JsonOutput.toJson(payload))
        .asObject(Map)

    if (resp.status == 200) {
        String pageId = ((resp.body as Map).id as String)
        logger.info("[CREATED] '${title}' → page ID ${pageId}")
        Thread.sleep(600L)   // polite pause — avoids 429 rate-limit errors
        return pageId
    } else {
        logger.error("[ERROR] Failed to create '${title}': HTTP ${resp.status} — ${resp.body}")
        Thread.sleep(1500L)  // longer back-off after an error
        return ''
    }
}

// ---------------------------------------------------------------------------
// Page 1 — Style Macro Page
// ---------------------------------------------------------------------------
String styleMacroBody = '''\
<p>This page contains a legacy DC style macro that must be migrated.</p>
<ac:structured-macro ac:name="style" ac:schema-version="1">
  <ac:plain-text-body><![CDATA[
    .custom-header { color: #003366; font-size: 18px; font-weight: bold; }
    .highlight-row { background-color: #fffae6; padding: 4px 8px; }
    .status-badge  { border-radius: 3px; padding: 2px 6px; font-size: 11px; }
  ]]></ac:plain-text-body>
</ac:structured-macro>
<p>Content below the style macro continues here.</p>'''

createPage('[MIGRATION TEST] Style Macro Page', styleMacroBody)

// ---------------------------------------------------------------------------
// Page 2 — Div Macro Page (Nested macro inside rich-text-body)
//
// NOTE: recently-updated is a page-level macro — Confluence Cloud's API
// rejects it inside rich-text-body (HTTP 500). We use a 'code' macro instead:
//   • It has its own </ac:structured-macro> closing tag  → tests the nesting fix
//   • It does NOT have </ac:rich-text-body>              → end-anchor pattern works
//   • It is fully allowed inside rich-text-body          → API accepts it
// ---------------------------------------------------------------------------
String divMacroBody = '''\
<p>This page tests the nested macro fix. The div contains a nested code macro.</p>
<ac:structured-macro ac:name="div" ac:schema-version="1">
  <ac:parameter ac:name="style">background-color: #f0f4f8; padding: 12px; border-left: 4px solid #0052cc;</ac:parameter>
  <ac:rich-text-body>
    <p><strong>OPERATION UPDATES</strong></p>
    <ac:structured-macro ac:name="code" ac:schema-version="1">
      <ac:parameter ac:name="language">text</ac:parameter>
      <ac:plain-text-body><![CDATA[NESTED MACRO TEST — this code block is inside the div rich-text-body and must be preserved after migration]]></ac:plain-text-body>
    </ac:structured-macro>
  </ac:rich-text-body>
</ac:structured-macro>
<p>Content after the div macro.</p>'''

createPage('[MIGRATION TEST] Div Macro Page (Nested)', divMacroBody)

// ---------------------------------------------------------------------------
// Page 3 — Alert Macro Page
// ---------------------------------------------------------------------------
String alertMacroBody = '''\
<p>This page contains an alert macro used for driver briefings.</p>
<ac:structured-macro ac:name="alert" ac:schema-version="1">
  <ac:parameter ac:name="title">TODAY\'S DRIVERS</ac:parameter>
  <ac:parameter ac:name="type">Info</ac:parameter>
  <ac:rich-text-body>
    <p><strong>DO NOT MESSAGE THE DRIVERS DIRECTLY</strong></p>
    <p>All communication must go through the operations team. Contact ops@example.com for urgent matters.</p>
  </ac:rich-text-body>
</ac:structured-macro>
<p>See the schedule below for today\'s assignments.</p>'''

createPage('[MIGRATION TEST] Alert Macro Page', alertMacroBody)

// ---------------------------------------------------------------------------
// Page 4 — AUI Button Macro Page (includes a DC URL)
// ---------------------------------------------------------------------------
String auiButtonBody = '''\
<p>Quick action buttons for the DEMO space:</p>
<ac:structured-macro ac:name="auibutton" ac:schema-version="1">
  <ac:parameter ac:name="title">Add Post</ac:parameter>
  <ac:parameter ac:name="url">https://confluence.example.com/pages/createpage.action?spaceKey=DEMO&amp;title=New+Post</ac:parameter>
</ac:structured-macro>
<ac:structured-macro ac:name="auibutton" ac:schema-version="1">
  <ac:parameter ac:name="title">View More</ac:parameter>
  <ac:parameter ac:name="url">https://yoursite.atlassian.net/wiki/spaces/DEMO/pages</ac:parameter>
</ac:structured-macro>
<p>Use the buttons above to navigate quickly.</p>'''

createPage('[MIGRATION TEST] AUI Button Macro Page', auiButtonBody)

// ---------------------------------------------------------------------------
// Page 5 — Lozenge Macro Page
// ---------------------------------------------------------------------------
String lozengeMacroBody = '''\
<p>Quick Links section using lozenge macros:</p>
<ac:structured-macro ac:name="lozenge" ac:schema-version="1">
  <ac:parameter ac:name="title">Idea Box</ac:parameter>
  <ac:parameter ac:name="link"><a href="https://confluence.example.com/display/DEMO/Idea+Box">Idea Box</a></ac:parameter>
  <ac:rich-text-body>
    <p>Send an idea to the team for review and discussion</p>
  </ac:rich-text-body>
</ac:structured-macro>
<ac:structured-macro ac:name="lozenge" ac:schema-version="1">
  <ac:parameter ac:name="title">Break Schedule</ac:parameter>
  <ac:parameter ac:name="link"><a href="https://confluence.example.com/display/DEMO/Break+Schedule">Break Schedule</a></ac:parameter>
  <ac:rich-text-body>
    <p>View the current break schedule for all shifts</p>
  </ac:rich-text-body>
</ac:structured-macro>
<p>More quick links available in the sidebar.</p>'''

createPage('[MIGRATION TEST] Lozenge Macro Page', lozengeMacroBody)

// ---------------------------------------------------------------------------
// Page 6 — Real-World Combined Page (all 5 broken macro types + nesting)
// ---------------------------------------------------------------------------
String combinedBody = '''\
<p>This page simulates a real-world DC page with all broken macro types.</p>

<ac:structured-macro ac:name="alert" ac:schema-version="1">
  <ac:parameter ac:name="title">TODAY\'S DRIVERS</ac:parameter>
  <ac:parameter ac:name="type">Info</ac:parameter>
  <ac:rich-text-body>
    <p><strong>DO NOT MESSAGE THE DRIVERS DIRECTLY</strong></p>
  </ac:rich-text-body>
</ac:structured-macro>

<ac:structured-macro ac:name="div" ac:schema-version="1">
  <ac:parameter ac:name="style">background-color: #e6f0ff; padding: 10px;</ac:parameter>
  <ac:rich-text-body>
    <p><strong>OPERATION UPDATES</strong></p>
    <ac:structured-macro ac:name="code" ac:schema-version="1">
      <ac:parameter ac:name="language">text</ac:parameter>
      <ac:plain-text-body><![CDATA[NESTED MACRO TEST — code block inside div rich-text-body]]></ac:plain-text-body>
    </ac:structured-macro>
  </ac:rich-text-body>
</ac:structured-macro>

<p>Quick actions:</p>
<ac:structured-macro ac:name="auibutton" ac:schema-version="1">
  <ac:parameter ac:name="title">Add Post</ac:parameter>
  <ac:parameter ac:name="url">https://confluence.example.com/pages/createpage.action?spaceKey=DEMO</ac:parameter>
</ac:structured-macro>

<p>Quick links:</p>
<ac:structured-macro ac:name="lozenge" ac:schema-version="1">
  <ac:parameter ac:name="title">Idea Box</ac:parameter>
  <ac:parameter ac:name="link"><a href="https://confluence.example.com/display/DEMO/Idea+Box">Idea Box</a></ac:parameter>
  <ac:rich-text-body>
    <p>Send an idea to the team</p>
  </ac:rich-text-body>
</ac:structured-macro>

<ac:structured-macro ac:name="style" ac:schema-version="1">
  <ac:plain-text-body><![CDATA[
    .page-header { font-size: 24px; color: #003366; }
  ]]></ac:plain-text-body>
</ac:structured-macro>'''

createPage('[MIGRATION TEST] Real-World Combined Page', combinedBody)

// ---------------------------------------------------------------------------
// Page 7 — Clean Page (Cloud-native macros only — must NOT be modified)
// ---------------------------------------------------------------------------
String cleanBody = '''\
<p>This page contains only Cloud-native macros and must not be touched by the migration.</p>
<ac:structured-macro ac:name="info" ac:schema-version="1">
  <ac:rich-text-body>
    <p>This is a normal Cloud info panel that should not be modified by the migration script.</p>
  </ac:rich-text-body>
</ac:structured-macro>
<ac:structured-macro ac:name="panel" ac:schema-version="1">
  <ac:parameter ac:name="title">Cloud Panel</ac:parameter>
  <ac:rich-text-body>
    <p>This is a Cloud-native panel macro. It must remain untouched.</p>
  </ac:rich-text-body>
</ac:structured-macro>
<p>End of clean page content.</p>'''

createPage('[MIGRATION TEST] Clean Page (No Macros)', cleanBody)

logger.info('=== All 7 test pages created. Run Script 3 or Script 4 next. ===')
