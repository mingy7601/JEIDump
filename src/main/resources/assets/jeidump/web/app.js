/* JEI Dump frontend.
 *
 * Loads data/index.json once, builds:
 *   - a sidebar with a clickable list of every category,
 *   - a main pane that shows a paginated grid of recipe images for the active category,
 *   - an "ingredient focus" view (URL #ing=<id>&mode=for|use) that aggregates every recipe
 *     from every category that produces (mode=for) or consumes (mode=use) the given ingredient,
 *   - a top-bar fuzzy search that indexes items, fluids, mods and category titles.
 *
 * Recipe images are rendered server-side at IconRenderer.RECIPE_SCALE x logical size, and the
 * `scale` field on each recipe carries that multiplier. The frontend displays the image at an
 * integer multiple (1x, 2x, ...) of its *logical* size, so MC's pixel art and the MC font stay
 * crisp - no fractional CSS scaling that would smear 16-px glyphs.
 *
 * No external dependencies. The "fuzzy" matcher is a simple lowercased subsequence + substring
 * scorer; good enough for mod packs with ~50k items, and zero footprint.
 */
(function () {
    'use strict';

    const PAGE_SIZE = 60;
    // Display each recipe image at exactly this many *logical* pixels per CSS pixel.
    // The PNG itself is rendered at recipe.scale (server-side); displaying at an integer multiple
    // means the browser does pure nearest-neighbour upscaling (with image-rendering: pixelated)
    // or clean integer downsampling, never the bilinear smear of fractional CSS scaling.
    const DISPLAY_ZOOM = 2;

    /** @type {{categories: any[], items: Record<string,any>, fluids: Record<string,any>, itemRecipes: Record<string,any[]>}} */
    let DATA = null;
    /** Flat searchable index entries: {key, label, mod, type, target}. */
    let SEARCH = [];
    let activeCatId = null;
    let activePage = 0;

    // The dumper writes data/index.js which sets window.__JEI_DUMP_DATA before this script runs.
    // That path is what makes the dump usable directly from file:// (browsers block fetch() for
    // local files). We still fall through to fetch() as a fallback so older dumps that only have
    // index.json keep working when served via HTTP.
    if (window.__JEI_DUMP_DATA) {
        // Defer to next tick so DOM-ready ordering matches the fetch path below.
        setTimeout(() => init(window.__JEI_DUMP_DATA), 0);
    } else {
        fetch('data/index.json')
            .then(r => r.json())
            .then(init)
            .catch(err => {
                document.getElementById('welcome').innerHTML =
                    '<h2>Failed to load data</h2><p>Could not read <code>data/index.json</code>: ' + escapeHtml(String(err)) + '</p>' +
                    '<p>If you opened this page directly via <code>file://</code>, your browser blocks local <code>fetch()</code>. ' +
                    'Re-run <code>/dumpjei</code> with the latest version of the mod (which writes <code>data/index.js</code>), ' +
                    'or serve this folder over HTTP.</p>';
            });
    }

    function init(data) {
        DATA = data;
        buildSearchIndex();
        renderSidebar();
        renderHome();
        wireSearch();
        window.addEventListener('hashchange', applyHash);
        applyHash();
    }

    function buildSearchIndex() {
        SEARCH = [];
        for (const cat of DATA.categories) {
            SEARCH.push({
                key: (cat.title + ' ' + cat.modName + ' ' + cat.uid).toLowerCase(),
                label: cat.title,
                mod: cat.modName,
                type: 'category',
                target: { cat: cat.id }
            });
        }
        for (const id in DATA.items) {
            const it = DATA.items[id];
            SEARCH.push({
                key: (it.name + ' ' + it.mod + ' ' + id).toLowerCase(),
                label: it.name,
                mod: it.mod,
                img: it.img,
                type: 'item',
                target: { ingredient: id }
            });
        }
        for (const id in DATA.fluids) {
            const fl = DATA.fluids[id];
            SEARCH.push({
                key: (fl.name + ' ' + fl.mod + ' ' + id).toLowerCase(),
                label: fl.name,
                mod: fl.mod,
                img: fl.img,
                type: 'fluid',
                target: { ingredient: id }
            });
        }
    }

    function renderSidebar() {
        const ul = document.getElementById('category-list');
        ul.innerHTML = '';

        // Group by mod for a clearer outline (matches Sphinx-style nested toctrees).
        const byMod = new Map();
        for (const cat of DATA.categories) {
            const key = cat.modName || 'Unknown';
            if (!byMod.has(key)) byMod.set(key, []);
            byMod.get(key).push(cat);
        }
        // Sort mods alphabetically; Minecraft first if present.
        const mods = Array.from(byMod.keys()).sort((a, b) => {
            if (a === 'Minecraft') return -1;
            if (b === 'Minecraft') return 1;
            return a.localeCompare(b);
        });

        for (const modName of mods) {
            const header = document.createElement('li');
            header.innerHTML = '<div class="modname" style="margin-top:8px;font-weight:600;color:#444">' +
                escapeHtml(modName) + '</div>';
            ul.appendChild(header);
            const cats = byMod.get(modName).slice().sort((a, b) => a.title.localeCompare(b.title));
            for (const cat of cats) {
                const li = document.createElement('li');
                li.dataset.cat = cat.id;
                li.innerHTML = '<a href="#cat=' + encodeURIComponent(cat.id) + '">' +
                    escapeHtml(cat.title) +
                    '<span class="count">' + cat.recipeCount + '</span></a>';
                ul.appendChild(li);
            }
        }
    }

    function renderHome() {
        const c = document.getElementById('content');
        const totalRecipes = DATA.categories.reduce((s, c) => s + c.recipeCount, 0);
        const totalItems = Object.keys(DATA.items).length;
        const totalFluids = Object.keys(DATA.fluids).length;

        let html = '<div id="welcome">' +
            '<h2>Index</h2>' +
            '<p class="subtitle">' + DATA.categories.length + ' categories &middot; ' +
            totalRecipes + ' recipes &middot; ' + totalItems + ' items &middot; ' + totalFluids + ' fluids</p>' +
            '<div class="index-grid">';
        const sorted = DATA.categories.slice().sort((a, b) => a.title.localeCompare(b.title));
        for (const cat of sorted) {
            html += '<a href="#cat=' + encodeURIComponent(cat.id) + '">' +
                escapeHtml(cat.title) +
                '<span class="modname">' + escapeHtml(cat.modName) + ' &middot; ' + cat.recipeCount + ' recipes</span>' +
                '</a>';
        }
        html += '</div></div>';
        c.innerHTML = html;
    }

    /**
     * Build the HTML for a single recipe card. Used by both the per-category view and the
     * ingredient-focus view, so the card layout stays in sync between the two.
     *
     * @param r    recipe object from DATA.categories[*].recipes[*].
     * @param idx  0-based index inside the source category (for the corner label).
     */
    function recipeCardHtml(r, idx) {
        // The hotspot layer uses the logical canvas size (r.w x r.h). The displayed image is
        // sized in CSS pixels at r.w * DISPLAY_ZOOM x r.h * DISPLAY_ZOOM, so percentage-based
        // hotspot positions (computed from logical px) line up exactly.
        let spots = '';
        if (r.slots) {
            for (const s of r.slots) {
                const xp = (s.x / r.w * 100).toFixed(3);
                const yp = (s.y / r.h * 100).toFixed(3);
                const wp = (s.w / r.w * 100).toFixed(3);
                const hp = (s.h / r.h * 100).toFixed(3);
                spots += '<a class="hotspot" tabindex="0"' +
                    ' data-id="' + escapeAttr(s.id) + '"' +
                    ' data-kind="' + escapeAttr(s.kind) + '"' +
                    ' data-role="' + escapeAttr(s.role) + '"' +
                    ' style="left:' + xp + '%;top:' + yp + '%;width:' + wp + '%;height:' + hp + '%"' +
                    '></a>';
            }
        }
        // Pick a "first input" / "first output" id for the card-level R/U keybinds.
        const firstIn = (r.inputs && r.inputs[0]) || (r.fluidInputs && r.fluidInputs[0]) || '';
        const firstOut = (r.outputs && r.outputs[0]) || (r.fluidOutputs && r.fluidOutputs[0]) || '';
        const dispW = r.w * DISPLAY_ZOOM;
        const dispH = r.h * DISPLAY_ZOOM;
        return '<div class="recipe-card" tabindex="0" data-first-in="' + escapeAttr(firstIn) + '" data-first-out="' + escapeAttr(firstOut) + '">' +
            '<div class="recipe-img-wrap" style="width:' + dispW + 'px;height:' + dispH + 'px">' +
            '<img src="' + escapeAttr(r.img) + '" alt="recipe ' + idx + '" loading="lazy" width="' + dispW + '" height="' + dispH + '" />' +
            spots +
            '</div>' +
            '<div class="idx">#' + idx + '</div>' +
            '</div>';
    }

    function renderCategory(catId, page) {
        const cat = DATA.categories.find(c => c.id === catId);
        if (!cat) { renderHome(); return; }
        activeCatId = catId;
        activePage = page | 0;

        // Highlight active sidebar entry.
        document.querySelectorAll('#category-list li').forEach(li => li.classList.remove('active'));
        const sel = document.querySelector('#category-list li[data-cat="' + cssEscape(catId) + '"]');
        if (sel) sel.classList.add('active');

        const recipes = cat.recipes;
        const totalPages = Math.max(1, Math.ceil(recipes.length / PAGE_SIZE));
        if (activePage >= totalPages) activePage = 0;
        const slice = recipes.slice(activePage * PAGE_SIZE, (activePage + 1) * PAGE_SIZE);

        let html = '<h2>' + escapeHtml(cat.title) + '</h2>' +
            '<p class="subtitle">' + escapeHtml(cat.modName) + ' &middot; uid <code>' +
            escapeHtml(cat.uid) + '</code> &middot; ' + cat.recipeCount + ' recipes</p>';

        if (recipes.length === 0) {
            html += '<p>This category has no recipes.</p>';
        } else {
            html += '<p class="hint">Hover a slot for tooltip &middot; left-click = recipes that <em>produce</em> it &middot; right-click = recipes that <em>use</em> it &middot; <kbd>R</kbd>/<kbd>U</kbd> on a focused card jump from its first output / input.</p>';
            html += '<div class="recipe-grid">';
            for (let i = 0; i < slice.length; i++) {
                const realIdx = activePage * PAGE_SIZE + i;
                html += recipeCardHtml(slice[i], realIdx);
            }
            html += '</div>';

            if (totalPages > 1) {
                html += '<div class="pager">';
                html += '<button ' + (activePage === 0 ? 'disabled' : '') + ' data-p="' + (activePage - 1) + '">Prev</button>';
                const visible = pageButtons(activePage, totalPages);
                for (const p of visible) {
                    if (p === '...') {
                        html += '<button disabled>&hellip;</button>';
                    } else {
                        html += '<button class="' + (p === activePage ? 'active' : '') + '" data-p="' + p + '">' + (p + 1) + '</button>';
                    }
                }
                html += '<button ' + (activePage === totalPages - 1 ? 'disabled' : '') + ' data-p="' + (activePage + 1) + '">Next</button>';
                html += '</div>';
            }
        }

        const c = document.getElementById('content');
        c.innerHTML = html;
        c.querySelectorAll('.pager button[data-p]').forEach(b => {
            b.addEventListener('click', () => {
                const p = parseInt(b.dataset.p, 10);
                location.hash = 'cat=' + encodeURIComponent(catId) + '&p=' + p;
            });
        });
        wireHotspots(c);
        wireCardKeybinds(c);
    }

    /**
     * Ingredient-focus view. Aggregates every recipe (across all categories) that contains
     * {@code id} on the requested side. mode === 'for' picks recipes producing it (output side),
     * mode === 'use' picks recipes consuming it (input side).
     * That mirrors JEI's left-vs-right-click distinction.
     */
    function renderIngredient(id, mode) {
        activeCatId = null;
        document.querySelectorAll('#category-list li').forEach(li => li.classList.remove('active'));

        const meta = DATA.items[id] || DATA.fluids[id];
        const wantRole = mode === 'use' ? 'in' : 'out';
        const refs = (DATA.itemRecipes[id] || []).filter(r => r.role === wantRole);

        // Group references by category so the user sees one section per category, in the same
        // order categories appear in DATA.categories (stable across reloads).
        const byCat = new Map();
        for (const ref of refs) {
            if (!byCat.has(ref.cat)) byCat.set(ref.cat, []);
            byCat.get(ref.cat).push(ref.idx);
        }

        const oppMode = mode === 'use' ? 'for' : 'use';
        const oppLabel = mode === 'use' ? 'recipes that produce it' : 'recipes that use it';
        const heading = mode === 'use' ? 'Uses of' : 'Recipes for';
        const icon = meta && meta.img ? '<img class="hdr-icon" src="' + escapeAttr(meta.img) + '" alt="" />' : '';
        const name = meta ? meta.name : id;
        const modName = meta ? (meta.mod || '') : '';

        let html = '<h2>' + icon + escapeHtml(heading) + ' ' + escapeHtml(name) + '</h2>' +
            '<p class="subtitle">' + escapeHtml(modName) + ' &middot; <code>' + escapeHtml(id) + '</code> &middot; ' +
            refs.length + ' recipe' + (refs.length === 1 ? '' : 's') + ' in ' +
            byCat.size + ' categor' + (byCat.size === 1 ? 'y' : 'ies') +
            ' &middot; <a href="#ing=' + encodeURIComponent(id) + '&mode=' + oppMode + '">show ' + oppLabel + '</a></p>';

        if (refs.length === 0) {
            html += '<p>No ' + (mode === 'use' ? 'recipe consumes' : 'recipe produces') + ' this ingredient in the dump.</p>';
        } else {
            html += '<p class="hint">Hover a slot for tooltip &middot; left-click = recipes that <em>produce</em> it &middot; right-click = recipes that <em>use</em> it.</p>';

            for (const cat of DATA.categories) {
                const idxs = byCat.get(cat.id);
                if (!idxs) continue;
                html += '<div class="ing-section">' +
                    '<h3><a href="#cat=' + encodeURIComponent(cat.id) + '">' + escapeHtml(cat.title) + '</a> ' +
                    '<span class="ing-mod">' + escapeHtml(cat.modName) + ' &middot; ' + idxs.length + ' recipe' + (idxs.length === 1 ? '' : 's') + '</span></h3>' +
                    '<div class="recipe-grid">';
                for (const ri of idxs) {
                    const r = cat.recipes[ri];
                    if (!r) continue;
                    html += recipeCardHtml(r, ri);
                }
                html += '</div></div>';
            }
        }

        const c = document.getElementById('content');
        c.innerHTML = html;
        wireHotspots(c);
        wireCardKeybinds(c);
    }

    function pageButtons(active, total) {
        // Always show first/last; up to 2 around active; collapse the rest with '...'.
        const out = new Set([0, total - 1, active - 1, active, active + 1]);
        const sorted = Array.from(out).filter(n => n >= 0 && n < total).sort((a, b) => a - b);
        const result = [];
        for (let i = 0; i < sorted.length; i++) {
            if (i > 0 && sorted[i] !== sorted[i - 1] + 1) result.push('...');
            result.push(sorted[i]);
        }
        return result;
    }

    function wireSearch() {
        const input = document.getElementById('search');
        const results = document.getElementById('search-results');
        let activeRow = -1;

        input.addEventListener('input', () => {
            const q = input.value.trim().toLowerCase();
            if (!q) { results.hidden = true; results.innerHTML = ''; activeRow = -1; return; }
            const hits = scoreSearch(q).slice(0, 50);
            if (hits.length === 0) {
                results.innerHTML = '<div class="row" style="color:#888">No matches</div>';
                results.hidden = false;
                return;
            }
            results.innerHTML = hits.map((h, i) => rowHtml(h, i)).join('');
            results.hidden = false;
            activeRow = -1;
            results.querySelectorAll('.row[data-action]').forEach(row => {
                row.addEventListener('mousedown', (e) => {
                    e.preventDefault();
                    activate(row.dataset.action, row.dataset.target);
                    results.hidden = true;
                    input.value = '';
                });
            });
        });

        input.addEventListener('keydown', (e) => {
            const rows = results.querySelectorAll('.row[data-action]');
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                activeRow = Math.min(rows.length - 1, activeRow + 1);
                highlightRow(rows, activeRow);
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                activeRow = Math.max(0, activeRow - 1);
                highlightRow(rows, activeRow);
            } else if (e.key === 'Enter') {
                if (activeRow >= 0 && rows[activeRow]) {
                    activate(rows[activeRow].dataset.action, rows[activeRow].dataset.target);
                    results.hidden = true;
                    input.value = '';
                }
            } else if (e.key === 'Escape') {
                results.hidden = true;
            }
        });

        document.addEventListener('click', (e) => {
            if (!results.contains(e.target) && e.target !== input) results.hidden = true;
        });
    }

    function highlightRow(rows, idx) {
        rows.forEach(r => r.classList.remove('active'));
        if (idx >= 0 && rows[idx]) {
            rows[idx].classList.add('active');
            rows[idx].scrollIntoView({ block: 'nearest' });
        }
    }

    function rowHtml(h, i) {
        const action = h.entry.type === 'category' ? 'cat' : 'ingredient';
        const target = h.entry.type === 'category' ? h.entry.target.cat : h.entry.target.ingredient;
        const img = h.entry.img ? '<img src="' + escapeAttr(h.entry.img) + '" alt="" />' : '<span style="width:24px"></span>';
        return '<div class="row" data-action="' + action + '" data-target="' + escapeAttr(target) + '">' +
            img +
            '<span>' + escapeHtml(h.entry.label) + '</span>' +
            '<span class="meta">' + h.entry.type + ' &middot; ' + escapeHtml(h.entry.mod || '') + '</span>' +
            '</div>';
    }

    function scoreSearch(q) {
        const out = [];
        for (const e of SEARCH) {
            let score = 0;
            const idx = e.key.indexOf(q);
            if (idx === 0) score = 100;
            else if (idx > 0) score = 50 - Math.min(idx, 30);
            else if (subseq(q, e.key)) score = 20;
            else continue;
            if (e.type === 'category') score += 5; // tiny boost so categories surface first
            out.push({ entry: e, score });
        }
        out.sort((a, b) => b.score - a.score || a.entry.label.length - b.entry.label.length);
        return out;
    }

    function subseq(needle, haystack) {
        let j = 0;
        for (let i = 0; i < haystack.length && j < needle.length; i++) {
            if (haystack[i] === needle[j]) j++;
        }
        return j === needle.length;
    }

    function activate(kind, target) {
        if (kind === 'cat') {
            location.hash = 'cat=' + encodeURIComponent(target);
        } else if (kind === 'ingredient') {
            // From the search box, default to "recipes that produce" the ingredient (matches
            // JEI's left-click on an item in the ingredient list).
            jumpTo(target, 'for');
        }
    }

    /**
     * Navigate to the ingredient-focus view. mode is 'for' (recipes that produce it; matches
     * JEI's left-click) or 'use' (recipes that consume it; matches JEI's right-click).
     */
    function jumpTo(id, mode) {
        location.hash = 'ing=' + encodeURIComponent(id) + '&mode=' + (mode === 'use' ? 'use' : 'for');
    }

    /** Wire hotspot hover (tooltip) + left-click (recipes-for) + right-click (uses-of). */
    function wireHotspots(root) {
        const tip = ensureTooltipEl();
        root.querySelectorAll('.hotspot').forEach(spot => {
            spot.addEventListener('mouseenter', () => showTooltip(tip, spot));
            spot.addEventListener('mousemove', e => positionTooltip(tip, e));
            spot.addEventListener('mouseleave', () => { tip.hidden = true; });
            spot.addEventListener('click', e => {
                e.preventDefault();
                jumpTo(spot.dataset.id, 'for');
            });
            spot.addEventListener('contextmenu', e => {
                e.preventDefault();
                jumpTo(spot.dataset.id, 'use');
            });
            // Keyboard accessibility: Enter = recipes-for, Shift+Enter = uses-of.
            spot.addEventListener('keydown', e => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    jumpTo(spot.dataset.id, e.shiftKey ? 'use' : 'for');
                }
            });
        });
    }

    /**
     * R / U keybinds while a card is focused (Tab navigates between cards).
     * R = recipes for the first OUTPUT of the focused card (matches JEI's R-on-hover).
     * U = uses of the first INPUT.
     */
    function wireCardKeybinds(root) {
        root.querySelectorAll('.recipe-card').forEach(card => {
            card.addEventListener('keydown', e => {
                if (e.target.classList.contains('hotspot')) return; // hotspot keys take priority
                if (e.key === 'r' || e.key === 'R') {
                    const id = card.dataset.firstOut;
                    if (id) { e.preventDefault(); jumpTo(id, 'for'); }
                } else if (e.key === 'u' || e.key === 'U') {
                    const id = card.dataset.firstIn;
                    if (id) { e.preventDefault(); jumpTo(id, 'use'); }
                }
            });
        });
    }

    function ensureTooltipEl() {
        let tip = document.getElementById('jei-tooltip');
        if (tip) return tip;
        tip = document.createElement('div');
        tip.id = 'jei-tooltip';
        tip.className = 'jei-tooltip';
        tip.hidden = true;
        document.body.appendChild(tip);
        return tip;
    }

    function showTooltip(tip, spot) {
        const id = spot.dataset.id;
        const meta = DATA.items[id] || DATA.fluids[id];
        if (!meta) { tip.hidden = true; return; }
        const lines = (meta.tooltip && meta.tooltip.length ? meta.tooltip : [meta.name]);
        // First line = display name (white), rest dimmed (matches vanilla aesthetic).
        let html = '<div class="tt-name">' + escapeHtml(lines[0]) + '</div>';
        for (let i = 1; i < lines.length; i++) {
            html += '<div class="tt-line">' + escapeHtml(lines[i]) + '</div>';
        }
        html += '<div class="tt-mod">' + escapeHtml(meta.mod || '') + '</div>';
        tip.innerHTML = html;
        tip.hidden = false;
    }

    function positionTooltip(tip, e) {
        // Offset from cursor; flip to other side near the right/bottom edge so we stay on screen.
        const pad = 14;
        let x = e.clientX + pad;
        let y = e.clientY + pad;
        const r = tip.getBoundingClientRect();
        if (x + r.width > window.innerWidth - 4) x = e.clientX - r.width - pad;
        if (y + r.height > window.innerHeight - 4) y = e.clientY - r.height - pad;
        tip.style.left = Math.max(4, x) + 'px';
        tip.style.top = Math.max(4, y) + 'px';
    }

    function applyHash() {
        const params = parseHash();
        if (params.ing) {
            renderIngredient(params.ing, params.mode === 'use' ? 'use' : 'for');
            return;
        }
        if (!params.cat) {
            renderHome();
            return;
        }
        renderCategory(params.cat, params.p ? parseInt(params.p, 10) : 0);
    }

    function parseHash() {
        const h = location.hash.replace(/^#/, '');
        const out = {};
        for (const part of h.split('&')) {
            const eq = part.indexOf('=');
            if (eq < 0) continue;
            out[part.substring(0, eq)] = decodeURIComponent(part.substring(eq + 1));
        }
        return out;
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }
    function escapeAttr(s) { return escapeHtml(s); }
    function cssEscape(s) {
        // Minimal CSS escape for attribute selectors.
        return String(s).replace(/(["\\])/g, '\\$1');
    }
})();
