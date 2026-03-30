> **⚠️ Proof of Concept** — This repository demonstrates a potential approach to transforming Data Center macros for use in Cloud. Due to differences in platform parity, not all functionality may be replicable on Cloud today — though this may evolve as Atlassian continues to develop the platform. This is provided on a best-effort basis; some things may not work exactly as they did on DC, and you may need to accept certain limitations or invest further effort to refine the results for your specific use case.

# How to Run the Macro Migration

## Before You Start

You need **ScriptRunner for Confluence Cloud** installed. If you're not sure, go to **Apps → Manage apps** and search for ScriptRunner. If it's not there, contact your Confluence admin.

You'll also need permission to edit pages in the space you're migrating.

---

## Design

> **TL;DR** — one shared library, six single-purpose scripts, two variables to configure. Nothing else to touch.

All transformation logic lives in `library/MacroMigrator.groovy` — a single class saved in Script Manager with one method per macro type. The scripts in `scripts/` are thin wrappers that import the library and handle the API calls.

```
MacroMigrator.applyStyle()      →  style     → info panel
MacroMigrator.applyDiv()        →  div       → panel (bgColor extracted)
MacroMigrator.applyAlert()      →  alert     → panel (type-coloured)
MacroMigrator.applyAuiButton()  →  auibutton → bold hyperlink
MacroMigrator.applyLozenge()    →  lozenge   → bold hyperlink + description
```

Each script exposes exactly two variables at the top — everything else is handled internally:

```groovy
String  SPACE_KEY = 'YOUR_SPACE_KEY'
boolean DRY_RUN   = true
```

To add support for a new macro in the future, add one method to the library. No other files need to change.

---

## Finding Your Space Key

The space key is in the URL when you're browsing a space:

```
https://yoursite.atlassian.net/wiki/spaces/DEMO/pages/...
                                            ^^^
                                         space key
```

---

## Step 1 — Save the Library

This is the only setup step. Everything else runs from the Script Console.

1. Go to **Apps → ScriptRunner → Script Manager**
2. Click **Add Folder** → name it `Confluence` *(capital C)*
3. Inside `Confluence`, click **Add Folder** → name it `migration`
4. Inside `migration`, click **Add Script** → name it `MacroMigrator`
5. Open the file `library/MacroMigrator.groovy` from this repo, copy the entire contents, paste it in, and click **Save**

That's the only file that lives in Script Manager. Everything else runs directly in the Script Console.

---

## Step 2 — Open the Script Console

Every script from here on runs in the same place:

**Apps → ScriptRunner → Script Console**

Paste the script, change the space key, and click **Run**. That's it.

---

## Step 3 — Scan Your Space

**Script:** `scripts/01_macro_scanner.groovy`

This is read-only. It makes no changes. It just tells you what broken macros exist and where.

1. Paste the script into the Script Console
2. Change `SPACE_KEY = 'DEMO'` to your space key
3. Click **Run**
4. Read the output — anything marked `[UNSUPPORTED]` needs fixing

Note: Skip to step 4 and come back here after IF you have no pages to test against.
---

## Step 4  (OPTIONAL)— Create Test Pages

**Script:** `scripts/02_create_test_pages.groovy`

If you are not testing against real pages, this  script creates 7 test pages in your space so you can safely test the migration before touching real content. Each page contains examples of the five automated macros so you can verify the output before running against your actual pages.

1. Paste the script into the Script Console
2. Change `TEST_SPACE_KEY = 'DEMO'` to your space key
3. Click **Run**
4. You should see 7 `[CREATED]` lines in the output

---

## Step 5 — Run the Migration (Test Pages First)

**Script:** `scripts/03_full_space_migration.groovy`

Run this twice — first as a preview, then for real.

**First run — preview only:**
1. Paste the script into the Script Console
2. Change `SPACE_KEY = 'DEMO'` to your space key
3. Make sure `DRY_RUN = true` *(it is by default)*
4. Click **Run**
5. Read the `[DRY RUN]` lines — this is exactly what would change

**Second run — apply changes:**
1. Change `DRY_RUN = false`
2. Click **Run**
3. You should see `[UPDATE]` lines for each page that was fixed

---

## Step 6 — Verify It Worked

**Script:** `scripts/04_verify_results.groovy`

This checks the test pages and tells you pass or fail for each one.

1. Paste the script into the Script Console
2. Change `TEST_SPACE_KEY = 'DEMO'` to your space key
3. Click **Run**
4. You should see `PASS` for all checks and `ALL CHECKS PASSED ✓` at the end

If anything shows `FAIL`, investigate further (limitations will exist).

---

## You're Done

The migration has run. Your pages now use Cloud-native macros.

If you want to **remove **the 7 test pages that were created in Step 4, run `scripts/cleanup.groovy` (included in this repo) with your space key. This is optional — the test pages don't affect anything if you leave them.

---

## Macro Reference

### What's Automated

These five macros are fully handled by `MacroMigrator.groovy`. Tested and verified.

| DC Macro | Replaced With | Notes |
|---|---|---|
| `styleinfo` | `panel` | CSS is discarded, migration note added |
| `divpanel` | `panel` | Background colour extracted from inline CSS |
| `alertpanel` | `panel` | Border/background colour mapped from type (Info/Warning/Error/Success) |
| `auibutton` | Bold hyperlink | DC URLs preserved as-is |
| `lozenge` | Bold hyperlink + description | `href` and plain text extracted |

### What's Not Automated

These macros are identified by the scanner but not automatically fixed. They require manual intervention.

| DC Macro | What You See in Cloud | Recommended Fix |
|---|---|---|
| `section` / `column` | Layout collapses, sidebar disappears | Rebuild using the Cloud editor's native Layouts feature (Insert → Layouts) |
| `html` | *"We don't have a way to export this macro"* | Rebuild content using Cloud-native macros (`panel`, `expand`, `code`) |
| `widget` | Broken or missing embed | Check the Cloud Widget Connector — YouTube and Vimeo are supported, most others are not |
| `unmigrated-wiki-markup` | *"We don't have a way to export this macro"* | Content is still in the page storage body — open the page in the editor and reformat manually |
| `content-reporter` | Broken | No Cloud equivalent exists — replace with the native Content by Label macro |

### What's Safe to Ignore

These are Cloud-native and will not be touched by any script.

`panel` · `info` · `warning` · `note` · `tip` · `expand` · `recently-updated` · `blog-posts` · `pagetree` · `children` · `include` · `excerpt` · `content-by-label` · `toc` · `code` · `jira` · `status`

### "We don't have a way to export this macro"

This message appears in Confluence Cloud whenever a page contains a macro it doesn't recognise. It is **not** a ScriptRunner error. Run `scripts/01_macro_scanner.groovy` to get a full list of every macro in your space and which category it falls into.

---

## Something Went Wrong?

| What you see | What to do |
|---|---|
| `unable to resolve class Confluence.migration.MacroMigrator` | The library in Step 1 wasn't saved at the right path. Check the folder is named `Confluence` with a capital C. |
| `Space 'DEMO' not found` | Double-check your space key — copy it from the URL. |
| `HTTP 403` | You don't have edit permission on the space. Ask your Confluence admin. |
