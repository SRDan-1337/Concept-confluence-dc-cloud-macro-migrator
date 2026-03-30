// Save in Script Manager at path: Confluence/migration/MacroMigrator.groovy
// Folder structure: Confluence > migration > MacroMigrator.groovy
// Import in calling scripts as: import Confluence.migration.MacroMigrator

package Confluence.migration

import groovy.transform.CompileStatic
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * MacroMigrator — pure transformation logic for Confluence DC→Cloud macro migration.
 *
 * Handles 5 broken DC macros:
 *   style     → Cloud info panel (migration note)
 *   div       → Cloud panel macro (preserves nested macros via end-anchor pattern)
 *   alert     → Cloud panel macro (type-coloured border/background)
 *   auibutton → Bold hyperlink paragraph
 *   lozenge   → Bold hyperlink paragraph with description
 *
 * NO HTTP calls. NO @Field annotations. All fields are static final class members.
 * Safe under @CompileStatic — uses Matcher + StringBuffer for all replacements.
 */
@CompileStatic
class MacroMigrator {

    // -------------------------------------------------------------------------
    // Macro detection patterns
    // -------------------------------------------------------------------------

    /** style macro — safe non-greedy (only has CDATA body, no nesting risk) */
    static final Pattern STYLE_PATTERN = Pattern.compile(
        '(?s)<ac:structured-macro[^>]*\\bac:name="style"[^>]*>.*?</ac:structured-macro>'
    )

    /**
     * div macro — end-anchored to </ac:rich-text-body> so nested macros
     * (e.g. recently-updated) inside the rich-text-body do NOT cause early
     * termination at their own </ac:structured-macro> closing tag.
     * Group 1 = everything from after the opening tag up to and including
     * </ac:rich-text-body>.
     */
    static final Pattern DIV_PATTERN = Pattern.compile(
        '(?s)<ac:structured-macro[^>]*\\bac:name="div"[^>]*>' +
        '(.*?</ac:rich-text-body>)\\s*</ac:structured-macro>'
    )

    /** alert macro — same end-anchor strategy as div */
    static final Pattern ALERT_PATTERN = Pattern.compile(
        '(?s)<ac:structured-macro[^>]*\\bac:name="alert"[^>]*>' +
        '(.*?</ac:rich-text-body>)\\s*</ac:structured-macro>'
    )

    /** auibutton macro — safe non-greedy (only ac:parameter children) */
    static final Pattern AUIBUTTON_PATTERN = Pattern.compile(
        '(?s)<ac:structured-macro[^>]*\\bac:name="auibutton"[^>]*>' +
        '(.*?)</ac:structured-macro>'
    )

    /** lozenge macro — end-anchored to </ac:rich-text-body> */
    static final Pattern LOZENGE_PATTERN = Pattern.compile(
        '(?s)<ac:structured-macro[^>]*\\bac:name="lozenge"[^>]*>' +
        '(.*?</ac:rich-text-body>)\\s*</ac:structured-macro>'
    )

    // -------------------------------------------------------------------------
    // Helper patterns (private use)
    // -------------------------------------------------------------------------

    /** Extracts a named ac:parameter value from macro inner content */
    private static final Pattern PARAM_PATTERN = Pattern.compile(
        '(?s)<ac:parameter[^>]*\\bac:name="([^"]+)"[^>]*>(.*?)</ac:parameter>'
    )

    /** Extracts content between <ac:rich-text-body> tags */
    private static final Pattern RTB_PATTERN = Pattern.compile(
        '(?s)<ac:rich-text-body>(.*?)</ac:rich-text-body>'
    )

    /** Extracts background-color value from inline CSS */
    private static final Pattern BG_CSS_PATTERN = Pattern.compile(
        '(?i)background(?:-color)?[\\s]*:[\\s]*([#\\w()%,.\\s]+?)(?:;|$)'
    )

    /** Extracts href from an <a href="..."> tag */
    private static final Pattern HREF_PATTERN = Pattern.compile(
        'href="([^"]+)"'
    )

    // -------------------------------------------------------------------------
    // Named colour lookup (CSS colour name → hex)
    // -------------------------------------------------------------------------

    private static final Map<String, String> NAMED_COLOURS = [
        white  : '#ffffff', black   : '#000000', red     : '#ff0000',
        blue   : '#0000ff', green   : '#008000', yellow  : '#ffff00',
        gray   : '#808080', grey    : '#808080', orange  : '#ffa500',
        purple : '#800080', pink    : '#ffc0cb', cyan    : '#00ffff',
        magenta: '#ff00ff', lime    : '#00ff00', navy    : '#000080',
        teal   : '#008080', silver  : '#c0c0c0', maroon  : '#800000',
        aqua   : '#00ffff', fuchsia : '#ff00ff', olive   : '#808000'
    ] as Map<String, String>

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Applies all macro transformations to the given Confluence storage body.
     *
     * @param originalBody  Raw storage-format XML from the Confluence v2 API
     * @return Map with keys:
     *   body       (String)       — transformed body (same as input if unchanged)
     *   changed    (boolean)      — true if at least one transform was applied
     *   transforms (List<String>) — human-readable list of applied transforms
     */
    static Map<String, Object> transformBody(String originalBody) {
        List<String> transforms = new ArrayList<String>()
        String body = originalBody
        String after

        after = applyStyle(body)
        if (after != body) {
            transforms.add('style → info panel (migration note)')
            body = after
        }

        after = applyDiv(body)
        if (after != body) {
            transforms.add('div → panel (bgColor extracted, nested macros preserved)')
            body = after
        }

        after = applyAlert(body)
        if (after != body) {
            transforms.add('alert → panel (type-coloured border/background)')
            body = after
        }

        after = applyAuiButton(body)
        if (after != body) {
            transforms.add('auibutton → bold hyperlink paragraph')
            body = after
        }

        after = applyLozenge(body)
        if (after != body) {
            transforms.add('lozenge → bold hyperlink paragraph with description')
            body = after
        }

        return [
            body      : (Object) body,
            changed   : (Object) (!transforms.isEmpty()),
            transforms: (Object) transforms
        ] as Map<String, Object>
    }

    // -------------------------------------------------------------------------
    // Transform methods (package-private for testability)
    // -------------------------------------------------------------------------

    static String applyStyle(String body) {
        String infoPanel =
            '<ac:structured-macro ac:name="info" ac:schema-version="1">\n' +
            '  <ac:rich-text-body>\n' +
            '    <p><strong>Migration note:</strong> This page previously contained' +
            ' a custom CSS style macro that is not supported in Confluence Cloud.' +
            ' The original styling has been removed. Please review and re-style' +
            ' this page using Cloud-native formatting.</p>\n' +
            '  </ac:rich-text-body>\n' +
            '</ac:structured-macro>'

        Matcher m = STYLE_PATTERN.matcher(body)
        StringBuffer sb = new StringBuffer()
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(infoPanel))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    static String applyDiv(String body) {
        Matcher m = DIV_PATTERN.matcher(body)
        StringBuffer sb = new StringBuffer()
        while (m.find()) {
            String inner   = m.group(1)
            String bgColor = extractBgColor(inner)
            String rtb     = extractRtbContent(inner)
            String repl =
                '<ac:structured-macro ac:name="panel" ac:schema-version="1">\n' +
                '  <ac:parameter ac:name="borderColor">#cccccc</ac:parameter>\n' +
                '  <ac:parameter ac:name="bgColor">' + bgColor + '</ac:parameter>\n' +
                '  <ac:rich-text-body>' + rtb + '</ac:rich-text-body>\n' +
                '</ac:structured-macro>'
            m.appendReplacement(sb, Matcher.quoteReplacement(repl))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    static String applyAlert(String body) {
        Matcher m = ALERT_PATTERN.matcher(body)
        StringBuffer sb = new StringBuffer()
        while (m.find()) {
            String inner = m.group(1)
            String title = extractParam(inner, 'title')
            String type  = extractParam(inner, 'type').toLowerCase().trim()
            String rtb   = extractRtbContent(inner)

            String borderColor
            String bgColor
            switch (type) {
                case 'info':
                    borderColor = '#0052CC'; bgColor = '#DEEBFF'; break
                case 'warning':
                    borderColor = '#FF8B00'; bgColor = '#FFFAE6'; break
                case 'error':
                    borderColor = '#BF2600'; bgColor = '#FFEBE6'; break
                case 'success':
                    borderColor = '#006644'; bgColor = '#E3FCEF'; break
                default:
                    borderColor = '#97a0af'; bgColor = '#f4f5f7'
            }

            String titleParam = title ?
                '\n  <ac:parameter ac:name="title">' + title + '</ac:parameter>' : ''

            String repl =
                '<ac:structured-macro ac:name="panel" ac:schema-version="1">' +
                titleParam + '\n' +
                '  <ac:parameter ac:name="borderColor">' + borderColor +
                '</ac:parameter>\n' +
                '  <ac:parameter ac:name="bgColor">' + bgColor +
                '</ac:parameter>\n' +
                '  <ac:rich-text-body>' + rtb + '</ac:rich-text-body>\n' +
                '</ac:structured-macro>'
            m.appendReplacement(sb, Matcher.quoteReplacement(repl))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    static String applyAuiButton(String body) {
        Matcher m = AUIBUTTON_PATTERN.matcher(body)
        StringBuffer sb = new StringBuffer()
        while (m.find()) {
            String inner = m.group(1)
            String title = extractParam(inner, 'title')
            String url   = extractParam(inner, 'url')
            String repl  = '<p><strong><a href="' + url + '">' + title +
                           '</a></strong></p>'
            m.appendReplacement(sb, Matcher.quoteReplacement(repl))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    static String applyLozenge(String body) {
        Matcher m = LOZENGE_PATTERN.matcher(body)
        StringBuffer sb = new StringBuffer()
        while (m.find()) {
            String inner    = m.group(1)
            String title    = extractParam(inner, 'title')
            String linkParam = extractParam(inner, 'link')
            String rtb      = extractRtbContent(inner)

            // Extract href from the link parameter's <a href="..."> tag
            String href = ''
            if (linkParam) {
                Matcher hm = HREF_PATTERN.matcher(linkParam)
                if (hm.find()) {
                    href = hm.group(1)
                }
            }

            // Strip HTML tags from rich-text-body to get plain description
            String description = rtb.replaceAll('<[^>]+>', '').trim()

            String repl = '<p><strong><a href="' + href + '">' + title +
                          '</a></strong> \u2014 ' + description + '</p>'
            m.appendReplacement(sb, Matcher.quoteReplacement(repl))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts the value of a named ac:parameter from macro inner content.
     * Returns empty string if the parameter is not found.
     */
    private static String extractParam(String inner, String paramName) {
        Matcher m = PARAM_PATTERN.matcher(inner)
        while (m.find()) {
            if (m.group(1) == paramName) {
                String val = m.group(2)
                return val != null ? val.trim() : ''
            }
        }
        return ''
    }

    /**
     * Extracts the content between <ac:rich-text-body> and </ac:rich-text-body>.
     * Returns empty string if not found.
     */
    private static String extractRtbContent(String inner) {
        Matcher m = RTB_PATTERN.matcher(inner)
        if (m.find()) {
            String val = m.group(1)
            return val != null ? val : ''
        }
        return ''
    }

    /**
     * Extracts a CSS background-color value from the style parameter and
     * normalises it to a hex colour string.
     *
     * Handles:
     *   - Named colours (white, black, etc.)
     *   - Bare hex without # (e.g. "f0f0f0" → "#f0f0f0")
     *   - Hex with # (e.g. "#f0f0f0")
     *   - rgb() / rgba() — falls back to #ffffff
     *
     * @param inner  The macro inner content (group 1 from the div/alert pattern)
     * @return       Normalised hex colour string, e.g. "#f0f0f0"
     */
    static String extractBgColor(String inner) {
        String styleParam = extractParam(inner, 'style')
        if (!styleParam) {
            return '#ffffff'
        }

        Matcher m = BG_CSS_PATTERN.matcher(styleParam)
        if (!m.find()) {
            return '#ffffff'
        }

        String color = m.group(1).trim()
        String lower = color.toLowerCase()

        // Named colour lookup
        if (NAMED_COLOURS.containsKey(lower)) {
            return NAMED_COLOURS.get(lower) as String
        }

        // rgb() / rgba() — not easily convertible, fall back
        if (lower.startsWith('rgb')) {
            return '#ffffff'
        }

        // Bare hex (3 or 6 hex digits, no leading #)
        if (color.matches('[0-9a-fA-F]{3,6}')) {
            return '#' + color
        }

        // Already has # prefix
        if (color.startsWith('#')) {
            return color
        }

        return '#ffffff'
    }
}
