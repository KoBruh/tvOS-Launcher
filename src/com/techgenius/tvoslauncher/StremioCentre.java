package com.techgenius.tvoslauncher;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EXTRAS ! "Stremio Centre": the home screen as a Stremio hub. Everything except the dock is
 * hidden; a panel shows Continue Watching and recent library titles as posters on one side of the
 * screen and the focused title's details on the other. OK resumes in Stremio, hold OK for more.
 *
 * Built for low-RAM TVs: one network fetch for both rows, cached for an instant panel after a
 * restart, refreshed at most every few minutes and only while the launcher is in front; posters are
 * small decodes loaded only for cards near the focus, with a bounded cache.
 */
final class StremioCentre {

    private static final int MAX_CW = 12, MAX_LIB = 20;
    private static final long REFRESH_AFTER_MS = 5 * 60_000L;
    /** Posters loaded around the focused card (and for the first cards of each row). */
    private static final int THUMB_WINDOW = 6;

    private final SceneHome home;
    private final MainActivity host;
    private final StremioCloud cloud;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(30);
    private final Map<String, ImageView> thumbViews = new HashMap<>();
    private final List<String> loadingThumbs = new ArrayList<>();

    private FrameLayout root;
    private LinearLayout rowsPane, infoPane;
    private TextView infoKind, infoTitle, infoSub, infoHint;
    private View infoBarFill;
    private FrameLayout infoBarTrack;

    private final List<List<View>> rowCards = new ArrayList<>();
    private final List<List<WatchCloud.Item>> rowItems = new ArrayList<>();
    private List<WatchCloud.Item> cw, lib;
    private boolean active, loading, loadFailed;
    private long lastLoad;
    private int lastRow, lastCol;
    private int cardW, cardH;

    StremioCentre(SceneHome home) {
        this.home = home;
        this.host = home.host;
        this.cloud = home.stremioCloud;
        if (cloud.isSignedIn()) {
            cw = cloud.loadCache(cloud.profile());
            lib = cloud.loadLibraryCache();
        }
    }

    private int dp(float v) {
        return host.dp(v);
    }

    private String en(String s) {
        return I18n.s(host, s, null);
    }

    boolean isActive() {
        return active;
    }

    /** Signed in and on: the dock's own Stremio popup and the other home widgets step aside. */
    boolean replacesWidgets() {
        return active && cloud.isSignedIn();
    }

    boolean owns(View f) {
        if (f == null || !active) return false;
        for (List<View> r : rowCards) if (r.contains(f)) return true;
        return false;
    }

    // ------------------------------------------------------------------ show / hide / layout

    /** Called after every dock rebuild: turns the panel on/off and fits it around the dock. */
    void sync() {
        boolean want = Extras.isStremioCentre(host) && Scenes.isScenesLook(host);
        if (!want) {
            if (active) {
                active = false;
                if (root != null) root.setVisibility(View.GONE);
            }
            return;
        }
        if (root == null) build();
        active = true;
        root.setVisibility(View.VISIBLE);
        if (cloud.isSignedIn() && cw == null) {
            cw = cloud.loadCache(cloud.profile());
            lib = cloud.loadLibraryCache();
        }
        layout();
        render();
        refreshIfStale();
    }

    /** The dock popup may have just finished a QR sign-in: load the rows once. */
    void onSignedInMaybe() {
        if (active && cw == null && lib == null && !loading && cloud.isSignedIn()) {
            render();
            load();
        }
    }

    void onResume() {
        if (active) refreshIfStale();
    }

    private void refreshIfStale() {
        if (!cloud.isSignedIn() || loading) return;
        if (System.currentTimeMillis() - lastLoad > REFRESH_AFTER_MS) load();
    }

    private void build() {
        root = new FrameLayout(host);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setVisibility(View.GONE);

        rowsPane = new LinearLayout(host);
        rowsPane.setOrientation(LinearLayout.VERTICAL);
        rowsPane.setClipChildren(false);
        rowsPane.setClipToPadding(false);
        rowsPane.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(rowsPane);

        infoPane = new LinearLayout(host);
        infoPane.setOrientation(LinearLayout.VERTICAL);
        infoPane.setGravity(Gravity.CENTER_VERTICAL);
        infoKind = text(9.5f, 0xB8F4F4F6, "semibold");
        infoKind.setLetterSpacing(0.08f);
        infoTitle = text(24f, 0xFFF4F4F6, "semibold");
        infoTitle.setMaxLines(3);
        infoSub = text(12f, 0xD9F4F4F6, "regular");
        infoHint = text(10f, 0x99F4F4F6, "regular");
        infoPane.addView(infoKind);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(4);
        infoPane.addView(infoTitle, tlp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        infoPane.addView(infoSub, slp);
        infoBarTrack = new FrameLayout(host);
        GradientDrawable trBg = new GradientDrawable();
        trBg.setCornerRadius(dp(2));
        trBg.setColor(0x3DFFFFFF);
        infoBarTrack.setBackground(trBg);
        infoBarFill = new View(host);
        GradientDrawable flBg = new GradientDrawable();
        flBg.setCornerRadius(dp(2));
        flBg.setColor(0xEBFFFFFF);
        infoBarFill.setBackground(flBg);
        infoBarTrack.addView(infoBarFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(180), dp(4));
        blp.topMargin = dp(10);
        infoPane.addView(infoBarTrack, blp);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(14);
        infoPane.addView(infoHint, hlp);
        root.addView(infoPane);

        // Below the dock in z-order is fine (they never overlap); above the wallpaper.
        home.overlay.addView(root, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView text(float size, int color, String weight) {
        TextView t = new TextView(host);
        Ui.textSize(t, size);
        t.setTypeface(Ui.font(host, weight));
        t.setTextColor(color);
        t.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** The screen area left free by the dock, split into the poster rows and the info side. */
    private void layout() {
        int pos = home.dockPos;
        int thick = home.dockThicknessPx > 0 ? home.dockThicknessPx : dp(74);
        int top, bottom;
        if (pos == Extras.DOCK_TOP) {
            top = dp(14) + thick + dp(40);
            bottom = host.screenH - dp(26);
        } else if (pos == Extras.DOCK_BOTTOM || pos < 0) {
            top = dp(34);
            bottom = host.screenH - dp(14) - thick - dp(40);
        } else {
            top = dp(34);
            bottom = host.screenH - dp(26);
        }
        int left = home.shelfLeftPx, right = host.screenW - home.shelfRightPx;
        int w = Math.max(dp(300), right - left), h = Math.max(dp(200), bottom - top);

        FrameLayout.LayoutParams rlp = (FrameLayout.LayoutParams) root.getLayoutParams();
        rlp.leftMargin = left;
        rlp.topMargin = top;
        rlp.width = w;
        rlp.height = h;
        root.setLayoutParams(rlp);

        boolean rowsLeft = Extras.getCentreSide(host) == Extras.SIDE_LEFT;
        int rowsW = Math.round(w * 0.64f);
        int gap = dp(24);
        rowsPane.setLayoutParams(new FrameLayout.LayoutParams(rowsW, ViewGroup.LayoutParams.MATCH_PARENT,
                rowsLeft ? Gravity.START : Gravity.END));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(w - rowsW - gap,
                ViewGroup.LayoutParams.MATCH_PARENT, rowsLeft ? Gravity.END : Gravity.START);
        infoPane.setLayoutParams(ilp);

        // Two rows of 2:3 posters filling the height (one row gets the same size, not double).
        int headerH = dp(22), pad = dp(7);
        int perRow = (h - 2 * headerH - 4 * pad - dp(14)) / 2;
        cardH = Math.max(dp(110), Math.min(dp(230), perRow));
        cardW = Math.round(cardH * 2f / 3f);
    }

    // ------------------------------------------------------------------ rows

    private void render() {
        if (rowsPane == null) return;
        View f = host.getCurrentFocus();
        boolean hadFocus = owns(f);
        rowsPane.removeAllViews();
        rowCards.clear();
        rowItems.clear();
        thumbViews.clear();

        if (!cloud.isSignedIn()) {
            message(en("Stremio Centre"), en("Sign in to Stremio to fill this screen: highlight the Stremio icon in the dock and scan the QR code with your phone."));
            showInfo(null);
            return;
        }
        if (cw == null && lib == null) {
            message(en("Stremio Centre"), loadFailed ? en("Couldn't reach Stremio. It will try again shortly.") : en("Loading your Stremio titles…"));
            showInfo(null);
            return;
        }
        if (cw != null && !cw.isEmpty()) addRow(en("Continue watching"), cw);
        if (lib != null && !lib.isEmpty()) addRow(en("Your library"), lib);
        if (rowCards.isEmpty()) {
            message(en("Stremio Centre"), en("Nothing to show yet. Titles you watch or add to your Stremio library appear here."));
            showInfo(null);
            return;
        }
        lastRow = Math.min(lastRow, rowCards.size() - 1);
        lastCol = Math.min(lastCol, rowCards.get(lastRow).size() - 1);
        showInfo(rowItems.get(lastRow).get(lastCol));
        if (hadFocus) focus(lastRow, lastCol);
    }

    private void message(String title, String body) {
        TextView h = text(11.5f, 0xC7F4F4F6, "semibold");
        h.setText(title);
        rowsPane.addView(h);
        TextView b = text(13f, 0xEBF4F4F6, "regular");
        b.setText(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        rowsPane.addView(b, lp);
    }

    private void addRow(String header, List<WatchCloud.Item> items) {
        final int r = rowCards.size();
        int pad = dp(7);
        TextView h = text(11.5f, 0xC7F4F4F6, "semibold");
        h.setText(header);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = pad + dp(2);
        if (r > 0) hlp.topMargin = dp(14);
        rowsPane.addView(h, hlp);

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(pad, pad, pad, pad);
        List<View> cards = new ArrayList<>();
        String appName = host.appLabel(Extras.STREMIO_PKG);
        for (int i = 0; i < items.size(); i++) {
            final int c = i;
            final WatchCloud.Item it = items.get(i);
            SceneHome.WatchCardData d = new SceneHome.WatchCardData();
            d.key = "centre:" + it.contentId;
            d.pkg = Extras.STREMIO_PKG;
            d.appName = appName;
            d.title = it.title == null || it.title.isEmpty() ? (it.isSeries() ? en("Series") : en("Movie")) : it.title;
            d.subtitle = subtitle(it, false);
            d.progress = (float) Math.max(0.0, Math.min(1.0, it.fraction()));
            d.thumb = thumbs.get(it.contentId);
            View card = home.posterCard(d, cardW, cardH, (v, has) -> {
                if (has) {
                    lastRow = r;
                    lastCol = c;
                    showInfo(it);
                    loadThumbsAround(r, c);
                }
            });
            card.setOnClickListener(v -> open(it, true));
            card.setOnLongClickListener(v -> {
                showMenu(v, it);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
            if (i > 0) lp.leftMargin = dp(10);
            row.addView(card, lp);
            cards.add(card);
            ImageView thumb = card.findViewWithTag(SceneHome.WATCH_THUMB);
            if (thumb != null) thumbViews.put(it.contentId, thumb);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(host);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(false);
        scroll.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rowsPane.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rowCards.add(cards);
        rowItems.add(items);
        loadThumbsAround(r, 0);
    }

    /** "S2 · E4 · 23 min left" / "Up next" for continue watching, "Series"/"Movie" otherwise. */
    private String subtitle(WatchCloud.Item it, boolean longForm) {
        StringBuilder sb = new StringBuilder();
        if (it.season >= 0 && it.episode >= 0) {
            sb.append(longForm ? "Season " + it.season + " · Episode " + it.episode : "S" + it.season + " · E" + it.episode);
        } else if (it.episode >= 0) {
            sb.append(longForm ? "Episode " + it.episode : "E" + it.episode);
        }
        long leftMin = Math.max(0, it.duration - it.position) / 60_000L;
        if (it.upNext) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(en("Up next"));
        } else if (it.position > 0 && leftMin > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(leftMin).append(en(" min left"));
        }
        if (sb.length() == 0) sb.append(it.isSeries() ? en("Series") : en("Movie"));
        return sb.toString();
    }

    private void showInfo(WatchCloud.Item it) {
        if (infoPane == null) return;
        if (it == null) {
            infoPane.setVisibility(View.INVISIBLE);
            return;
        }
        infoPane.setVisibility(View.VISIBLE);
        infoKind.setText((it.isSeries() ? en("Series") : en("Movie")).toUpperCase(I18n.locale(host)));
        infoTitle.setText(it.title == null || it.title.isEmpty() ? (it.isSeries() ? en("Series") : en("Movie")) : it.title);
        infoSub.setText(subtitle(it, true));
        float p = (float) Math.max(0.0, Math.min(1.0, it.fraction()));
        boolean showBar = it.position > 0 && p > 0f;
        infoBarTrack.setVisibility(showBar ? View.VISIBLE : View.GONE);
        if (showBar) {
            ViewGroup.LayoutParams lp = infoBarFill.getLayoutParams();
            lp.width = Math.max(dp(3), Math.round(dp(180) * p));
            infoBarFill.setLayoutParams(lp);
        }
        boolean resumable = it.position > 0 || it.upNext;
        infoHint.setText((resumable ? en("OK to resume") : en("OK to open")) + "  ·  " + en("Hold OK for more"));
    }

    // ------------------------------------------------------------------ focus & keys

    /** Moves focus into the panel (from the dock). Returns false when there is nothing to focus. */
    boolean focusEntry() {
        if (!active || rowCards.isEmpty()) return false;
        int r = Math.min(lastRow, rowCards.size() - 1);
        if (home.dockPos == Extras.DOCK_BOTTOM) r = rowCards.size() - 1;      // nearest row to the dock
        else if (home.dockPos == Extras.DOCK_TOP) r = 0;
        focus(r, r == lastRow ? lastCol : 0);
        return true;
    }

    private void focus(int r, int c) {
        if (r < 0 || r >= rowCards.size()) return;
        List<View> cards = rowCards.get(r);
        if (cards.isEmpty()) return;
        cards.get(Math.max(0, Math.min(c, cards.size() - 1))).requestFocus();
    }

    /** D-pad inside the panel; the edge that faces the dock goes back to it. */
    boolean onKey(int k, boolean down, View f) {
        int r = -1, c = -1;
        for (int i = 0; i < rowCards.size(); i++) {
            int idx = rowCards.get(i).indexOf(f);
            if (idx >= 0) {
                r = i;
                c = idx;
                break;
            }
        }
        if (r < 0) return false;
        int n = rowCards.get(r).size();
        int pos = home.dockPos;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) {
                    if (c > 0) focus(r, c - 1);
                    else if (pos == Extras.DOCK_LEFT) home.focusDock(home.focusedDockIdx);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    if (c + 1 < n) focus(r, c + 1);
                    else if (pos == Extras.DOCK_RIGHT) home.focusDock(home.focusedDockIdx);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    if (r > 0) focus(r - 1, c);
                    else if (pos == Extras.DOCK_TOP) home.focusDock(home.focusedDockIdx);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (r + 1 < rowCards.size()) focus(r + 1, c);
                    else if (pos == Extras.DOCK_BOTTOM || pos < 0) home.focusDock(home.focusedDockIdx);
                }
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ actions

    private void open(WatchCloud.Item it, boolean resume) {
        String pkg = Extras.STREMIO_PKG;
        String link = resume && (it.position > 0 || it.upNext) ? cloud.deepLink(it) : StremioCloud.titleLink(it);
        PackageManager pm = host.getPackageManager();
        Intent i = Suggestions.view(link, pkg);
        if (i.resolveActivity(pm) != null && Suggestions.start(host, i)) return;
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch != null) Suggestions.start(host, launch);
        else host.toast(en("Stremio is not installed"));
    }

    private void showMenu(View anchor, WatchCloud.Item it) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (it.position > 0 || it.upNext) {
            labels.add(it.upNext ? en("Play next episode") : en("Resume"));
            actions.add(() -> open(it, true));
        }
        labels.add(en("Open title page"));
        actions.add(() -> open(it, false));
        labels.add(en("Refresh"));
        actions.add(this::load);
        host.showMenu(anchor, it.title, labels, actions, -1);
    }

    // ------------------------------------------------------------------ data

    private void load() {
        if (loading) return;
        loading = true;
        exec.execute(() -> {
            List<WatchCloud.Item>[] rows = null;
            boolean failed = false;
            try {
                rows = cloud.centreRows(MAX_CW, MAX_LIB);
            } catch (Exception e) {
                failed = true;
            }
            final List<WatchCloud.Item>[] result = rows;
            final boolean f = failed;
            host.handler.post(() -> {
                loading = false;
                lastLoad = System.currentTimeMillis();
                loadFailed = f;
                if (result != null) {
                    cw = result[0];
                    lib = result[1];
                }
                if (active) render();
            });
        });
    }

    private void loadThumbsAround(int r, int c) {
        if (r < 0 || r >= rowItems.size()) return;
        List<WatchCloud.Item> items = rowItems.get(r);
        int from = Math.max(0, c - 2), to = Math.min(items.size() - 1, c + THUMB_WINDOW);
        for (int i = from; i <= to; i++) loadThumb(items.get(i));
    }

    private void loadThumb(WatchCloud.Item it) {
        final String id = it.contentId;
        Bitmap have = thumbs.get(id);
        if (have != null) {
            ImageView v = thumbViews.get(id);
            if (v != null && v.getDrawable() == null) v.setImageBitmap(have);
            return;
        }
        if (loadingThumbs.contains(id)) return;
        loadingThumbs.add(id);
        String base = it.baseId();
        final String metahub = base.startsWith("tt") ? "https://images.metahub.space/poster/small/" + base + "/img" : null;
        final String poster = it.poster;
        exec.execute(() -> {
            Bitmap b = null;
            if (poster != null && !poster.isEmpty()) b = Suggestions.image(host, poster, "w185", 200);
            if (b == null && metahub != null) b = Suggestions.image(host, metahub, "w185", 200);
            final Bitmap bmp = b;
            host.handler.post(() -> {
                loadingThumbs.remove(id);
                if (bmp == null) return;
                thumbs.put(id, bmp);
                ImageView v = thumbViews.get(id);
                if (v != null) v.setImageBitmap(bmp);
            });
        });
    }
}
