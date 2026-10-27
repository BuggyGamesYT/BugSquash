package com.buggygames.bugsquash;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Iterator;
import java.util.Random;

public class GameView extends View {

    // ---- game states ----
    private static final int MENU = 0, PLAYING = 1, PAUSED = 2, OVER = 3;
    private static final int NORMAL = 0, GOLD = 1, VIRUS = 2;

    private int state = MENU;
    private final Random rnd = new Random();
    private final float d; // density
    private final SharedPreferences prefs;

    private int score, best, lives, combo, squashed;
    private float comboTimer, spawnTimer, elapsed, overTimer, shake, globalTime, flashRed;
    private long lastNanos = 0;
    private boolean newBest;

    private final ArrayList<Bug> bugs = new ArrayList<>();
    private final ArrayList<Particle> parts = new ArrayList<>();
    private final ArrayList<FloatText> texts = new ArrayList<>();

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final RectF pauseBtn = new RectF();
    private final RectF lbBtn = new RectF();

    // ---- online leaderboard (shared with the web version) ----
    private static final String LB_DB = "https://bugsquash-leaderboard-default-rtdb.firebaseio.com/bugsquash/scores.json";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile ArrayList<String[]> board = new ArrayList<>();
    private String myName, lastMine;
    private boolean dialogOpen;
    private volatile String lastErr = "";
    private WebView web; // fallback network path (Chrome's stack), same as the web version
    private final ConcurrentHashMap<Integer, String[]> webRes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CountDownLatch> webWait = new ConcurrentHashMap<>();
    private int webSeq = 0;

    public GameView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        prefs = c.getSharedPreferences("bugsquash", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        myName = prefs.getString("name", "");
        board = parse(prefs.getString("board", ""));
        try {
            web = new WebView(c);
            web.getSettings().setJavaScriptEnabled(true);
            web.addJavascriptInterface(new Object() {
                @JavascriptInterface public void done(int id, boolean ok, String t) {
                    webRes.put(id, new String[]{ok ? "1" : "0", t});
                    CountDownLatch l = webWait.get(id); if (l != null) l.countDown();
                }
            }, "BS");
            web.loadDataWithBaseURL("https://buggygamesyt.github.io/bugsquash/", "<html></html>", "text/html", "utf-8", null);
        } catch (Throwable t) { web = null; }
        new Thread(this::lbGet).start();
        tp.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        tp.setTextAlign(Paint.Align.CENTER);
        setHapticFeedbackEnabled(true);
    }

    // ---------------- lifecycle ----------------
    public void onPauseGame() { if (state == PLAYING) state = PAUSED; lastNanos = 0; }
    public void onResumeGame() { lastNanos = 0; invalidate(); }
    public boolean onBack() {
        if (state == PLAYING) { state = PAUSED; return true; }
        if (state == PAUSED || state == OVER) { state = MENU; bugs.clear(); return true; }
        return false;
    }

    private void startGame() {
        score = 0; lives = 3; combo = 0; squashed = 0;
        comboTimer = 0; spawnTimer = 0.6f; elapsed = 0; newBest = false; flashRed = 0;
        bugs.clear(); parts.clear(); texts.clear();
        state = PLAYING;
    }

    private float difficulty() { return Math.min(1f + elapsed / 35f + score / 120f, 4.2f); }

    // ---------------- spawning ----------------
    private void spawnBug() {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        Bug b = new Bug();
        float r = rnd.nextFloat();
        float diff = difficulty();
        float virusChance = Math.min(0.08f + elapsed / 400f, 0.22f);
        b.type = r < 0.06f ? GOLD : (r < 0.06f + virusChance ? VIRUS : NORMAL);
        b.size = (b.type == GOLD ? 20 : 26 + rnd.nextFloat() * 8) * d;
        float m = b.size * 2;
        int edge = rnd.nextInt(4);
        float topPad = 70 * d;
        switch (edge) {
            case 0: b.x = rnd.nextFloat() * w; b.y = topPad - m; break;
            case 1: b.x = w + m; b.y = topPad + rnd.nextFloat() * (h - topPad); break;
            case 2: b.x = rnd.nextFloat() * w; b.y = h + m; break;
            default: b.x = -m; b.y = topPad + rnd.nextFloat() * (h - topPad); break;
        }
        float tx = w * (0.2f + rnd.nextFloat() * 0.6f);
        float ty = topPad + (h - topPad) * (0.2f + rnd.nextFloat() * 0.6f);
        float ang = (float) Math.atan2(ty - b.y, tx - b.x);
        float speed = (90 + rnd.nextFloat() * 50) * d * diff;
        if (b.type == GOLD) speed *= 1.45f;
        if (b.type == VIRUS) speed *= 0.85f;
        b.speed = speed;
        b.angle = ang;
        b.baseAngle = ang;
        b.wigglePhase = rnd.nextFloat() * 6.28f;
        b.wiggleAmp = 0.25f + rnd.nextFloat() * 0.45f;
        b.hue = rnd.nextFloat() * 360f;
        bugs.add(b);
    }

    // ---------------- update ----------------
    private void update(float dt) {
        globalTime += dt;
        if (shake > 0) shake = Math.max(0, shake - dt * 30 * d);
        if (flashRed > 0) flashRed = Math.max(0, flashRed - dt * 2.5f);

        // particles & texts always animate
        for (Iterator<Particle> it = parts.iterator(); it.hasNext(); ) {
            Particle q = it.next();
            q.life -= dt;
            if (q.life <= 0) { it.remove(); continue; }
            q.x += q.vx * dt; q.y += q.vy * dt;
            q.vx *= (1 - 3 * dt); q.vy *= (1 - 3 * dt);
        }
        for (Iterator<FloatText> it = texts.iterator(); it.hasNext(); ) {
            FloatText f = it.next();
            f.life -= dt; f.y -= 50 * d * dt;
            if (f.life <= 0) it.remove();
        }

        if (state == OVER) { overTimer += dt; }
        if (state == MENU) {
            // ambient bugs on the menu
            spawnTimer -= dt;
            if (spawnTimer <= 0 && bugs.size() < 6) { elapsed = 0; spawnBug(); spawnTimer = 1.2f; }
            moveBugs(dt, false);
            return;
        }
        if (state != PLAYING) return;

        elapsed += dt;
        if (comboTimer > 0) { comboTimer -= dt; if (comboTimer <= 0) combo = 0; }

        spawnTimer -= dt;
        if (spawnTimer <= 0) {
            spawnBug();
            if (difficulty() > 2.2f && rnd.nextFloat() < 0.3f) spawnBug();
            spawnTimer = Math.max(0.28f, 1.15f / difficulty()) * (0.7f + rnd.nextFloat() * 0.6f);
        }
        moveBugs(dt, true);
    }

    private void moveBugs(float dt, boolean penalize) {
        int w = getWidth(), h = getHeight();
        for (Iterator<Bug> it = bugs.iterator(); it.hasNext(); ) {
            Bug b = it.next();
            b.age += dt;
            b.angle = b.baseAngle + (float) Math.sin(b.age * 4 + b.wigglePhase) * b.wiggleAmp;
            b.x += Math.cos(b.angle) * b.speed * dt;
            b.y += Math.sin(b.angle) * b.speed * dt;
            float m = b.size * 2.5f;
            boolean inside = b.x > 0 && b.x < w && b.y > 0 && b.y < h;
            if (inside) b.entered = true;
            boolean gone = b.x < -m || b.x > w + m || b.y < -m || b.y > h + m;
            if (gone && (b.entered || b.age > 8)) {
                it.remove();
                if (penalize && b.type == NORMAL) loseLife(b.x, b.y, "ESCAPED!");
            }
        }
    }

    private void loseLife(float x, float y, String why) {
        lives--;
        combo = 0;
        shake = 14 * d;
        flashRed = 1f;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        int w = getWidth(), h = getHeight();
        float fx = Math.max(60 * d, Math.min(w - 60 * d, x));
        float fy = Math.max(110 * d, Math.min(h - 40 * d, y));
        texts.add(new FloatText(fx, fy, why, Color.rgb(255, 60, 80), 1.2f));
        if (lives <= 0) gameOver();
    }

    private void gameOver() {
        state = OVER;
        overTimer = 0;
        if (score > best) {
            best = score; newBest = true;
            prefs.edit().putInt("best", best).apply();
        }
        final int sc = score;
        if (sc > 0) new Thread(() -> {
            lbGet();
            if (qualifies(sc)) ui.post(() -> { if (state == OVER) askName(sc); });
        }).start();
    }

    // ---------------- leaderboard ----------------
    private String http(String url, String body) throws Exception {
        try { return httpDirect(url, body); }
        catch (Exception e) {
            String err1 = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            if (web == null) { lastErr = err1; throw e; }
            try { return httpWeb(url, body); }
            catch (Exception e2) { lastErr = err1 + " / web: " + e2.getMessage(); throw e2; }
        }
    }

    private String httpWeb(final String url, final String body) throws Exception {
        final int id;
        synchronized (this) { id = ++webSeq; }
        CountDownLatch l = new CountDownLatch(1);
        webWait.put(id, l);
        final String opts = body == null ? "{method:'GET',cache:'no-store'}"
            : "{method:'POST',cache:'no-store',headers:{'Content-Type':'application/json'},body:" + jsq(body) + "}";
        final String js = "fetch(" + jsq(url) + "," + opts + ")"
            + ".then(r=>r.ok?r.text():Promise.reject('HTTP '+r.status))"
            + ".then(t=>BS.done(" + id + ",true,t)).catch(e=>BS.done(" + id + ",false,String(e)))";
        ui.post(() -> { try { web.evaluateJavascript(js, null); } catch (Throwable t) { webRes.put(id, new String[]{"0", String.valueOf(t)}); l.countDown(); } });
        boolean got = l.await(10, TimeUnit.SECONDS);
        webWait.remove(id);
        String[] r = webRes.remove(id);
        if (!got || r == null) throw new Exception("timeout");
        if (!"1".equals(r[0])) throw new Exception(r[1]);
        return r[1];
    }

    private static String jsq(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"; }

    private static String httpDirect(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        c.setUseCaches(false);
        if (body != null) {
            byte[] bb = body.getBytes("UTF-8");
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(bb.length);
            java.io.OutputStream os = c.getOutputStream(); os.write(bb); os.close();
        }
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new Exception("HTTP " + code); }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1024]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close(); c.disconnect();
        return bo.toString("UTF-8");
    }

    private static ArrayList<String[]> parse(String t) {
        ArrayList<String[]> out = new ArrayList<>();
        if (t == null || t.trim().isEmpty() || t.trim().equals("null")) return out;
        java.util.HashMap<String, Integer> best = new java.util.HashMap<>();
        try {
            org.json.JSONObject o = new org.json.JSONObject(t);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                org.json.JSONObject e = o.optJSONObject(it.next());
                if (e == null) continue;
                String nm = e.optString("n", ""); int sc = e.optInt("s", 0);
                if (!nm.matches("[A-Z0-9]{3}") || sc <= 0 || sc > 100000) continue;
                Integer b = best.get(nm);
                if (b == null || sc > b) best.put(nm, sc);
            }
        } catch (Exception ignored) {}
        for (java.util.Map.Entry<String, Integer> e : best.entrySet()) out.add(new String[]{e.getKey(), String.valueOf(e.getValue())});
        out.sort((x, y) -> Integer.parseInt(y[1]) - Integer.parseInt(x[1]));
        while (out.size() > 10) out.remove(out.size() - 1);
        return out;
    }

    private boolean lbGet() {
        for (int i = 0; i < 3; i++) {
            try {
                String raw = http(LB_DB + "?orderBy=%22s%22&limitToLast=200", null);
                board = parse(raw); lastErr = "";
                prefs.edit().putString("board", raw).apply();
                return true;
            }
            catch (Exception ignored) {}
            try { Thread.sleep(600 + i * 500); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private boolean lbSubmit(String name, int sc) {
        String body = "{\"n\":\"" + name + "\",\"s\":" + sc + ",\"t\":" + System.currentTimeMillis() + "}";
        for (int i = 0; i < 4; i++) {
            try { http(LB_DB, body); lbGet(); return true; }
            catch (Exception ignored) {}
            try { Thread.sleep(700 + i * 600); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private boolean qualifies(int sc) {
        ArrayList<String[]> b = board;
        return sc > 0 && (b.size() < 10 || sc > Integer.parseInt(b.get(b.size() - 1)[1]));
    }

    private void askName(final int sc) {
        if (dialogOpen || !(getContext() instanceof Activity) || ((Activity) getContext()).isFinishing()) return;
        dialogOpen = true;
        final EditText in = new EditText(getContext());
        in.setText(myName);
        in.setHint("AAA");
        in.setGravity(Gravity.CENTER);
        in.setTextSize(30);
        in.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        in.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(3), new InputFilter.AllCaps(),
            (src, st, en, dst, ds, de) -> {
                StringBuilder sb = new StringBuilder();
                for (int i = st; i < en; i++) { char ch = src.charAt(i); if (Character.isLetterOrDigit(ch) && ch < 128) sb.append(ch); }
                return sb.length() == en - st ? null : sb.toString();
            }});
        LinearLayout box = new LinearLayout(getContext());
        int pad = (int) (24 * d);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final AlertDialog dlg = new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("NEW TOP SCORE!")
            .setMessage("You scored " + sc + ". Put your name on the board (3 letters or numbers):")
            .setView(box)
            .setPositiveButton("Submit", null)
            .setNegativeButton("Skip", null)
            .create();
        dlg.setOnDismissListener(x -> dialogOpen = false);
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String name = in.getText().toString().toUpperCase();
            if (!name.matches("[A-Z0-9]{3}")) { in.setError("Use exactly 3 letters or numbers"); return; }
            myName = name;
            prefs.edit().putString("name", name).apply();
            v.setEnabled(false);
            dlg.setMessage("Saving…");
            new Thread(() -> {
                final boolean ok = lbSubmit(name, sc);
                ui.post(() -> {
                    if (ok) { lastMine = name; dlg.dismiss(); showBoard(); }
                    else { dlg.setMessage("Couldn't save. Tap Submit to try again.\n(" + lastErr + ")"); v.setEnabled(true); }
                });
            }).start();
        }));
        dlg.show();
    }

    private void showBoard() {
        if (dialogOpen || !(getContext() instanceof Activity) || ((Activity) getContext()).isFinishing()) return;
        dialogOpen = true;
        final TextView tv = new TextView(getContext());
        tv.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        tv.setTextSize(18);
        int pad = (int) (24 * d);
        tv.setPadding(pad, pad / 2, pad, 0);
        tv.setText(board.isEmpty() ? "Loading…" : boardText());
        final AlertDialog dlg = new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🏆 TOP 10")
            .setView(tv)
            .setPositiveButton("Close", null)
            .create();
        final boolean[] open = {true};
        dlg.setOnDismissListener(x -> { dialogOpen = false; open[0] = false; });
        dlg.show();
        new Thread(() -> {
            while (open[0]) {
                final boolean ok = lbGet();
                ui.post(() -> tv.setText(ok ? boardText() : boardText() + "\n\nCan't reach the leaderboard right now, retrying…\n(" + lastErr + ")"));
                for (int i = 0; i < 150 && open[0]; i++) { try { Thread.sleep(100); } catch (InterruptedException ignored) {} }
            }
        }).start();
    }

    private String boardText() {
        ArrayList<String[]> b = board;
        if (b.isEmpty()) return "No scores yet. Be the first!";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.size(); i++) {
            String[] e = b.get(i);
            String rank = String.format("%2d", i + 1);
            sb.append(rank).append("  ").append(e[0]).append(e[0].equals(lastMine) ? " ◀" : "  ")
              .append(String.format("%9s", e[1])).append(i < b.size() - 1 ? "\n" : "");
        }
        return sb.toString();
    }

    // ---------------- input ----------------
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_POINTER_DOWN) return true;
        int idx = e.getActionIndex();
        float x = e.getX(idx), y = e.getY(idx);

        switch (state) {
            case MENU: if (lbBtn.contains(x, y)) showBoard(); else startGame(); break;
            case PAUSED: state = PLAYING; lastNanos = 0; break;
            case OVER: if (lbBtn.contains(x, y)) showBoard(); else if (overTimer > 0.9f) startGame(); break;
            case PLAYING:
                if (pauseBtn.contains(x, y)) { state = PAUSED; break; }
                tap(x, y);
                break;
        }
        invalidate();
        return true;
    }

    private void tap(float x, float y) {
        Bug hit = null;
        float bestDist = Float.MAX_VALUE;
        for (Bug b : bugs) {
            float dx = b.x - x, dy = b.y - y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float reach = Math.max(b.size * 1.25f, 34 * d);
            if (dist < reach && dist < bestDist) { bestDist = dist; hit = b; }
        }
        if (hit == null) {
            // whiff
            combo = 0;
            for (int i = 0; i < 5; i++) addParticle(x, y, Color.argb(160, 255, 255, 255), 60, 0.3f);
            return;
        }
        bugs.remove(hit);
        if (hit.type == VIRUS) {
            splat(hit.x, hit.y, Color.rgb(255, 40, 70), 26);
            loseLife(hit.x, hit.y, "VIRUS!");
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        combo++;
        comboTimer = 1.3f;
        squashed++;
        int mult = Math.min(1 + combo / 5, 5);
        int pts = (hit.type == GOLD ? 5 : 1) * mult;
        score += pts;
        int col = hit.type == GOLD ? Color.rgb(255, 210, 40) : Color.HSVToColor(new float[]{hit.hue, 1, 1});
        splat(hit.x, hit.y, col, hit.type == GOLD ? 30 : 18);
        String label = "+" + pts + (mult > 1 ? "  x" + mult : "");
        if (hit.type == GOLD) label = "GOLDEN! " + label;
        texts.add(new FloatText(hit.x, hit.y, label, hit.type == GOLD ? Color.rgb(255, 220, 60) : Color.WHITE, 0.9f));
        if (hit.type == GOLD && lives < 3 && rnd.nextFloat() < 0.35f) {
            lives++;
            texts.add(new FloatText(hit.x, hit.y - 30 * d, "+1 LIFE", Color.rgb(80, 255, 140), 1.1f));
        }
    }

    private void splat(float x, float y, int color, int n) {
        for (int i = 0; i < n; i++) addParticle(x, y, color, 260, 0.55f + rnd.nextFloat() * 0.4f);
        for (int i = 0; i < n / 3; i++) addParticle(x, y, Color.WHITE, 180, 0.3f);
    }

    private void addParticle(float x, float y, int color, float spd, float life) {
        Particle q = new Particle();
        float a = rnd.nextFloat() * 6.283f, s = (0.3f + rnd.nextFloat()) * spd * d;
        q.x = x; q.y = y; q.vx = (float) Math.cos(a) * s; q.vy = (float) Math.sin(a) * s;
        q.color = color; q.life = life; q.maxLife = life; q.size = (2 + rnd.nextFloat() * 4) * d;
        parts.add(q);
    }

    // ---------------- drawing ----------------
    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0 : (now - lastNanos) / 1e9f;
        lastNanos = now;
        if (dt > 0.05f) dt = 0.05f;
        update(dt);

        int w = getWidth(), h = getHeight();
        c.save();
        if (shake > 0) c.translate((rnd.nextFloat() - 0.5f) * shake, (rnd.nextFloat() - 0.5f) * shake);
        drawBackground(c, w, h);
        for (Bug b : bugs) drawBug(c, b);
        for (Particle q : parts) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(q.color);
            p.setAlpha((int) (Color.alpha(q.color) * Math.max(0, q.life / q.maxLife)));
            c.drawCircle(q.x, q.y, q.size, p);
        }
        for (FloatText f : texts) {
            tp.setTextSize(20 * d);
            tp.setColor(f.color);
            tp.setAlpha((int) (255 * Math.min(1, f.life * 2)));
            c.drawText(f.text, f.x, f.y, tp);
        }
        c.restore();

        if (flashRed > 0) {
            p.setColor(Color.argb((int) (90 * flashRed), 255, 0, 40));
            c.drawRect(0, 0, w, h, p);
        }

        if (state == PLAYING || state == PAUSED) drawHud(c, w);
        if (state == MENU) drawMenu(c, w, h);
        if (state == PAUSED) drawPaused(c, w, h);
        if (state == OVER) drawOver(c, w, h);

        postInvalidateOnAnimation();
    }

    private void drawBackground(Canvas c, int w, int h) {
        c.drawColor(Color.rgb(10, 8, 20));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1 * d);
        float g = 40 * d;
        float off = (globalTime * 12 * d) % g;
        p.setColor(Color.argb(28, 120, 90, 255));
        for (float x = -off; x < w; x += g) c.drawLine(x, 0, x, h, p);
        for (float y = -off; y < h; y += g) c.drawLine(0, y, w, y, p);
        // scanlines
        p.setColor(Color.argb(18, 0, 0, 0));
        p.setStrokeWidth(2 * d);
        for (float y = 0; y < h; y += 4 * d) c.drawLine(0, y, w, y, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawBug(Canvas c, Bug b) {
        float glitch = 2.5f * d;
        if (rnd.nextFloat() < 0.04f) glitch = 7 * d; // occasional big glitch
        int main;
        if (b.type == GOLD) main = Color.rgb(255, 205, 40);
        else if (b.type == VIRUS) main = Color.rgb(255, 30, 60);
        else main = Color.HSVToColor(new float[]{(b.hue + globalTime * 90) % 360, 0.9f, 1f});

        // RGB split ghost copies
        drawBugShape(c, b, b.x - glitch, b.y, Color.argb(120, 255, 0, 60), false);
        drawBugShape(c, b, b.x + glitch, b.y, Color.argb(120, 0, 230, 255), false);
        drawBugShape(c, b, b.x, b.y, main, true);
    }

    private void drawBugShape(Canvas c, Bug b, float x, float y, int color, boolean details) {
        float s = b.size;
        c.save();
        c.translate(x, y);
        c.rotate((float) Math.toDegrees(b.angle));
        p.setColor(color);

        if (b.type == VIRUS) {
            // spiky virus blob
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            float spin = b.age * 2;
            for (int i = 0; i < 10; i++) {
                double a = spin + i * Math.PI / 5;
                float cx = (float) Math.cos(a), sy = (float) Math.sin(a);
                c.drawLine(cx * s * 0.55f, sy * s * 0.55f, cx * s * 0.95f, sy * s * 0.95f, p);
                c.drawCircle(cx * s, sy * s, 2.5f * d, p);
            }
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(0, 0, s * 0.6f, p);
            if (details) {
                p.setColor(Color.rgb(20, 0, 10));
                p.setStrokeWidth(3 * d);
                p.setStyle(Paint.Style.STROKE);
                float e = s * 0.18f;
                c.drawLine(-e * 2, -e * 1.6f, -e * 0.4f, 0, p);
                c.drawLine(-e * 0.4f, -e * 1.6f, -e * 2, 0, p);
                c.drawLine(e * 0.4f, -e * 1.6f, e * 2, 0, p);
                c.drawLine(e * 2, -e * 1.6f, e * 0.4f, 0, p);
                p.setStyle(Paint.Style.FILL);
            }
            c.restore();
            return;
        }

        // legs
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        float swing = (float) Math.sin(b.age * 22) * s * 0.18f;
        for (int i = -1; i <= 1; i++) {
            float lx = i * s * 0.32f;
            float sw = (i == 0 ? -swing : swing);
            c.drawLine(lx, 0, lx + sw + s * 0.1f, -s * 0.75f, p);
            c.drawLine(lx, 0, lx - sw + s * 0.1f, s * 0.75f, p);
        }
        // antennae
        c.drawLine(s * 0.7f, -s * 0.1f, s * 1.05f, -s * 0.4f, p);
        c.drawLine(s * 0.7f, s * 0.1f, s * 1.05f, s * 0.4f, p);

        // body + head
        p.setStyle(Paint.Style.FILL);
        rf.set(-s * 0.6f, -s * 0.42f, s * 0.45f, s * 0.42f);
        c.drawOval(rf, p);
        c.drawCircle(s * 0.6f, 0, s * 0.26f, p);

        if (details) {
            // shell split + glitch blocks
            p.setColor(Color.argb(110, 0, 0, 0));
            p.setStrokeWidth(2 * d);
            p.setStyle(Paint.Style.STROKE);
            c.drawLine(-s * 0.55f, 0, s * 0.4f, 0, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(90, 255, 255, 255));
            float blk = ((int) (b.age * 8) % 3) * s * 0.12f;
            c.drawRect(-s * 0.35f + blk, -s * 0.3f, -s * 0.15f + blk, -s * 0.16f, p);
            // eyes
            p.setColor(Color.WHITE);
            c.drawCircle(s * 0.68f, -s * 0.12f, s * 0.08f, p);
            c.drawCircle(s * 0.68f, s * 0.12f, s * 0.08f, p);
            p.setColor(Color.BLACK);
            c.drawCircle(s * 0.71f, -s * 0.12f, s * 0.04f, p);
            c.drawCircle(s * 0.71f, s * 0.12f, s * 0.04f, p);
            if (b.type == GOLD) {
                p.setColor(Color.argb((int) (150 + 100 * Math.sin(b.age * 12)), 255, 255, 255));
                c.drawCircle(-s * 0.1f, -s * 0.55f, 2.5f * d, p);
                c.drawCircle(s * 0.2f, s * 0.6f, 2 * d, p);
            }
        }
        c.restore();
    }

    private void glitchText(Canvas c, String t, float x, float y, float size) {
        tp.setTextSize(size);
        float j = (rnd.nextFloat() < 0.08f ? 6 : 2.5f) * d;
        tp.setColor(Color.argb(200, 255, 0, 70));
        c.drawText(t, x - j, y, tp);
        tp.setColor(Color.argb(200, 0, 230, 255));
        c.drawText(t, x + j, y, tp);
        tp.setColor(Color.WHITE);
        c.drawText(t, x, y, tp);
    }

    private void drawHud(Canvas c, int w) {
        float top = 46 * d;
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTextSize(26 * d);
        tp.setColor(Color.WHITE);
        c.drawText(String.valueOf(score), 18 * d, top, tp);
        tp.setTextSize(13 * d);
        tp.setColor(Color.argb(170, 255, 255, 255));
        c.drawText("BEST " + best, 18 * d, top + 20 * d, tp);

        // lives as little hearts
        for (int i = 0; i < 3; i++) {
            float hx = w / 2f - 30 * d + i * 30 * d, hy = top - 9 * d;
            drawHeart(c, hx, hy, 10 * d, i < lives ? Color.rgb(255, 50, 90) : Color.argb(60, 255, 255, 255));
        }

        if (combo >= 5) {
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(14 * d);
            tp.setColor(Color.HSVToColor(new float[]{(globalTime * 300) % 360, 0.8f, 1}));
            c.drawText("COMBO " + combo + "  x" + Math.min(1 + combo / 5, 5), w / 2f, top + 22 * d, tp);
        }

        // pause button
        float bs = 40 * d;
        pauseBtn.set(w - bs - 14 * d, top - 30 * d, w - 14 * d, top - 30 * d + bs);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(50, 255, 255, 255));
        c.drawRoundRect(pauseBtn, 10 * d, 10 * d, p);
        p.setColor(Color.WHITE);
        float cx = pauseBtn.centerX(), cy = pauseBtn.centerY();
        c.drawRect(cx - 8 * d, cy - 9 * d, cx - 3 * d, cy + 9 * d, p);
        c.drawRect(cx + 3 * d, cy - 9 * d, cx + 8 * d, cy + 9 * d, p);
        tp.setTextAlign(Paint.Align.CENTER);
    }

    private void drawHeart(Canvas c, float x, float y, float r, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawCircle(x - r * 0.5f, y, r * 0.55f, p);
        c.drawCircle(x + r * 0.5f, y, r * 0.55f, p);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x - r * 1.02f, y + r * 0.15f);
        path.lineTo(x + r * 1.02f, y + r * 0.15f);
        path.lineTo(x, y + r * 1.15f);
        path.close();
        c.drawPath(path, p);
    }

    private void dim(Canvas c, int w, int h, int alpha) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(alpha, 5, 3, 12));
        c.drawRect(0, 0, w, h, p);
    }

    private void drawMenu(Canvas c, int w, int h) {
        dim(c, w, h, 120);
        tp.setTextAlign(Paint.Align.CENTER);
        float fit = Math.min(54 * d, w / 6.5f);
        glitchText(c, "BUG", w / 2f, h * 0.30f, fit * 1.2f);
        glitchText(c, "SQUASH", w / 2f, h * 0.30f + fit * 1.15f, fit);

        tp.setTextSize(14 * d);
        tp.setColor(Color.argb(220, 255, 255, 255));
        float y = h * 0.52f;
        c.drawText("Tap the glitchy bugs before they escape", w / 2f, y, tp);
        tp.setColor(Color.rgb(255, 210, 40));
        c.drawText("Golden bugs = 5 points", w / 2f, y + 26 * d, tp);
        tp.setColor(Color.rgb(255, 60, 90));
        c.drawText("Never tap the red viruses!", w / 2f, y + 52 * d, tp);

        if (best > 0) {
            tp.setColor(Color.argb(200, 180, 160, 255));
            tp.setTextSize(16 * d);
            c.drawText("BEST  " + best, w / 2f, y + 96 * d, tp);
        }
        float pulse = 0.6f + 0.4f * (float) Math.sin(globalTime * 4);
        tp.setTextSize(22 * d);
        tp.setColor(Color.argb((int) (255 * pulse), 255, 255, 255));
        c.drawText("TAP TO START", w / 2f, h * 0.80f, tp);
        drawLbButton(c, w, h * 0.80f + 52 * d);
        tp.setTextSize(12 * d);
        tp.setColor(Color.argb(140, 255, 255, 255));
        c.drawText("by Buggy Games YT", w / 2f, h - 28 * d, tp);
    }

    private void drawLbButton(Canvas c, int w, float cy) {
        float bw = 150 * d, bh = 40 * d;
        lbBtn.set(w / 2f - bw / 2, cy - bh / 2, w / 2f + bw / 2, cy + bh / 2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(60, 255, 255, 255));
        c.drawRoundRect(lbBtn, 12 * d, 12 * d, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * d);
        p.setColor(Color.HSVToColor(new float[]{(globalTime * 120) % 360, 0.8f, 1}));
        c.drawRoundRect(lbBtn, 12 * d, 12 * d, p);
        p.setStyle(Paint.Style.FILL);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(16 * d);
        tp.setColor(Color.WHITE);
        c.drawText("🏆 TOP 10", w / 2f, cy + 6 * d, tp);
    }

    private void drawPaused(Canvas c, int w, int h) {
        dim(c, w, h, 170);
        tp.setTextAlign(Paint.Align.CENTER);
        glitchText(c, "PAUSED", w / 2f, h * 0.45f, Math.min(46 * d, w / 6f));
        tp.setTextSize(16 * d);
        tp.setColor(Color.argb(210, 255, 255, 255));
        c.drawText("Tap to resume", w / 2f, h * 0.45f + 44 * d, tp);
        c.drawText("Back for menu", w / 2f, h * 0.45f + 70 * d, tp);
    }

    private void drawOver(Canvas c, int w, int h) {
        dim(c, w, h, 175);
        tp.setTextAlign(Paint.Align.CENTER);
        glitchText(c, "GAME OVER", w / 2f, h * 0.32f, Math.min(44 * d, w / 7f));
        tp.setTextSize(60 * d);
        tp.setColor(Color.WHITE);
        c.drawText(String.valueOf(score), w / 2f, h * 0.47f, tp);
        tp.setTextSize(16 * d);
        tp.setColor(Color.argb(200, 255, 255, 255));
        c.drawText(squashed + " bugs squashed", w / 2f, h * 0.47f + 32 * d, tp);
        if (newBest) {
            tp.setTextSize(20 * d);
            tp.setColor(Color.HSVToColor(new float[]{(globalTime * 300) % 360, 0.8f, 1}));
            c.drawText("NEW HIGH SCORE!", w / 2f, h * 0.47f + 66 * d, tp);
        } else {
            tp.setColor(Color.argb(200, 180, 160, 255));
            c.drawText("BEST  " + best, w / 2f, h * 0.47f + 62 * d, tp);
        }
        if (overTimer > 0.9f) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(globalTime * 4);
            tp.setTextSize(20 * d);
            tp.setColor(Color.argb((int) (255 * pulse), 255, 255, 255));
            c.drawText("TAP TO PLAY AGAIN", w / 2f, h * 0.75f, tp);
        }
        drawLbButton(c, w, h * 0.75f + 56 * d);
    }

    // ---------------- data classes ----------------
    private static class Bug {
        float x, y, speed, angle, baseAngle, wigglePhase, wiggleAmp, size, hue, age;
        int type;
        boolean entered;
    }

    private static class Particle {
        float x, y, vx, vy, life, maxLife, size;
        int color;
    }

    private static class FloatText {
        float x, y, life;
        String text;
        int color;
        FloatText(float x, float y, String t, int color, float life) {
            this.x = x; this.y = y; this.text = t; this.color = color; this.life = life;
        }
    }
}
