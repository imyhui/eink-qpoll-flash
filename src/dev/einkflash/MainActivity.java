package dev.einkflash;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 墨水屏刷图：标准无 root NFC 应用（Layer 2）。依赖 HookInjector 注入的 Q-Poll 桥把壳子变成
 * 可发现的 ISO-DEP 标签；本界面用前台 dispatch 抓标签，复用 PanelProtocol 刷图。
 * 协议/图像类改编自 kur0neko/huawei-epd-pn532（PolyForm Noncommercial 1.0.0）。 */
public final class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int POLL_MS = PanelProtocol.DEFAULT_POLL_MS;

    private NfcAdapter nfc;
    private PendingIntent pending;
    private ImageView ivPreview;
    private TextView tvStatus, tvLog, tvSignal, tvPower;
    private ScrollView logScroll;
    private RadioButton rbA0;
    private Button btnFlash, btnInject;
    private ProgressBar pbFlash;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private Bitmap source;
    private volatile byte[] packed;
    private volatile Tag lastTag;
    private volatile int powerMs = 6000;
    private volatile boolean pendingFlash, probing, flashing;
    private Thread probeThread;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(getResources().getIdentifier("main", "layout", getPackageName()));
        ivPreview = find("ivPreview");
        tvStatus = find("tvStatus");
        tvLog = find("tvLog");
        tvSignal = find("tvSignal");
        tvPower = find("tvPower");
        logScroll = find("logScroll");
        rbA0 = find("rbA0");
        pbFlash = find("pbFlash");

        nfc = NfcAdapter.getDefaultAdapter(this);
        if (nfc == null) status("本机无 NFC，无法使用");
        int piFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        pending = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                piFlag);

        Button btnInject = find("btnInject"), btnPick = find("btnPick");
        this.btnInject = btnInject;
        btnFlash = find("btnFlash");
        Button btnCopyLog = find("btnCopyLog");
        btnInject.setOnClickListener(v -> {
            log("开始注入 Hook（需授权 su）…");
            exec.submit(() -> {
                boolean ok = HookInjector.inject(this, this::log);
                status(ok ? "Hook 就绪，可选图刷图" : "Hook 注入失败，看下方日志");
                refreshHookState();
            });
        });
        btnPick.setOnClickListener(v -> pickImage());
        btnPick.setOnLongClickListener(v -> {
            exec.submit(() -> { source = PanelImage.testPattern(); refreshPreview();
                log("已载入定位测试图（长按『选图』）"); });
            return true;
        });
        btnFlash.setOnClickListener(v -> onFlashClick());
        btnCopyLog.setOnClickListener(v -> copyLog());
        ((Button) find("btnPowerUp")).setOnClickListener(v -> adjustPower(1000));
        ((Button) find("btnPowerDown")).setOnClickListener(v -> adjustPower(-1000));
        updatePowerLabel();
        ((android.widget.RadioGroup) find("rgPanel"))
                .setOnCheckedChangeListener((g, id) -> refreshPreview());
        final android.view.View rowPower = find("rowPower");
        tvStatus.setOnLongClickListener(v -> {
            boolean show = rowPower.getVisibility() != android.view.View.VISIBLE;
            rowPower.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            status(show ? "已展开高级选项：上电等待（再长按状态栏可收起）" : "已收起高级选项");
            return true;
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (nfc != null) nfc.enableForegroundDispatch(this, pending,
                new IntentFilter[]{ new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED) },
                new String[][]{ { IsoDep.class.getName() } });
        refreshHookState();
    }

    /** 后台用 root 查一次是否已注入，更新①按钮：已注入显示「✓ 已Hook」，否则「注入Hook（首次）」。 */
    private void refreshHookState() {
        exec.submit(() -> {
            boolean inj = HookInjector.isInjected();
            main.post(() -> btnInject.setText(inj ? "① ✓ 已Hook" : "① 注入Hook(首次)"));
        });
    }

    @Override protected void onPause() {
        super.onPause();
        stopProbe();
        if (nfc != null) nfc.disableForegroundDispatch(this);
    }

    @Override protected void onDestroy() { super.onDestroy(); stopProbe(); exec.shutdownNow(); }

    @SuppressWarnings("unchecked")
    private <T> T find(String name) {
        return (T) findViewById(getResources().getIdentifier(name, "id", getPackageName()));
    }

    private void log(String s) {
        Log.i("einkflash", s);
        main.post(() -> {
            tvLog.append(s + "\n");
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
    private void status(String s) { main.post(() -> tvStatus.setText(s)); }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            log("加载图片…");
            exec.submit(() -> {
                try { source = PanelImage.load(getContentResolver(), uri); refreshPreview(); }
                catch (Exception e) { log("加载图片失败：" + e); }
            });
        }
    }

    private void refreshPreview() {
        if (source == null) return;
        final String pt = rbA0.isChecked() ? "A0" : "A1";
        exec.submit(() -> {
            try {
                PanelImage.Prepared prep =
                        PanelImage.prepare(source, 0, false, true, 1, false, 128, false, pt);
                packed = prep.packed;
                main.post(() -> ivPreview.setImageBitmap(prep.preview));
                log("已生成预览（" + pt + "）");
            } catch (Exception e) { log("预览失败：" + e); }
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return;
        lastTag = tag;
        if (pendingFlash) { pendingFlash = false; exec.submit(this::doFlash); return; }
        if (!flashing) startProbe();   // 连上壳子即自动开始信号检测
    }

    private void onFlashClick() {
        if (packed == null) { log("请先选择图片"); return; }
        stopProbe();
        if (lastTag != null) { exec.submit(this::doFlash); }
        else { pendingFlash = true; status("请把壳子贴到 NFC 区域开始刷图"); }
    }

    private void copyLog() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("einkflash-log", tvLog.getText()));
        status("日志已复制到剪贴板");
    }

    private void adjustPower(int delta) {
        powerMs = Math.max(2000, Math.min(12000, powerMs + delta));
        updatePowerLabel();
    }
    private void updatePowerLabel() {
        main.post(() -> tvPower.setText("上电等待 " + (powerMs / 1000.0) + "s（供电不足时调大）"));
    }

    private void startProbe() {
        if (probing || flashing) return;
        probing = true;
        probeThread = new Thread(this::probeLoop);
        probeThread.start();
    }

    private void stopProbe() {
        probing = false;
        Thread t = probeThread; probeThread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(800); } catch (InterruptedException ignored) { }
        }
    }

    private void probeLoop() {
        IsoDep iso = null; int streak = 0;
        Log.i("einkflash", "probe: loop start");
        try {
            while (probing) {
                Tag t = lastTag;
                if (t == null) { signalUi("信号 ▮▯▯▯▯  请贴上壳子", "#3A1420", "#FF6B6B"); break; }
                try {
                    if (iso == null) {
                        iso = IsoDep.get(t);
                        if (iso == null) { signalUi("无 ISO-DEP 接口", "#3A1420", "#FF6B6B"); break; }
                        iso.setTimeout(600); iso.connect();
                    }
                    long t0 = SystemClock.elapsedRealtime();
                    int sw = PanelProtocol.status(iso.transceive(PanelProtocol.D1));
                    long dt = SystemClock.elapsedRealtime() - t0;
                    Log.i("einkflash", "probe rtt=" + dt + " sw=" + String.format("%04X", sw) + " streak=" + streak);
                    if (sw == 0x9000) { streak++; showSignal(streak, dt); } else { streak = 0; showSignal(0, dt); }
                } catch (Exception e) {
                    streak = 0;
                    try { if (iso != null) iso.close(); } catch (Exception ignored) { }
                    iso = null;   // 下次用最新的 lastTag 重连，壳子重新贴上即自愈
                    signalUi("信号 ▮▯▯▯▯  弱/无响应，挪动或重贴一下", "#3A1420", "#FF6B6B");
                }
                try { Thread.sleep(150); } catch (InterruptedException e) { break; }
            }
        } finally {
            try { if (iso != null) iso.close(); } catch (Exception ignored) { }
            Log.i("einkflash", "probe: loop end");
        }
    }

    /** 以连续成功次数 + 往返延迟画 0–5 格信号（仅代表通信质量，不代表供电是否够刷）。 */
    private void showSignal(int streak, long rtt) {
        if (streak == 0) { signalUi("信号 ▮▯▯▯▯  弱/无响应，挪动壳子", "#3A1420", "#FF6B6B"); return; }
        int bars = streak >= 6 ? 5 : streak >= 4 ? 4 : streak >= 2 ? 3 : 2;
        String meter = "▮▮▮▮▮▯▯▯▯▯".substring(5 - bars, 10 - bars);
        String txt = "信号 " + meter + "  " + rtt + "ms（通信；能否刷看供电）";
        String bg, fg;
        if (bars >= 4) { bg = "#13301F"; fg = "#5BE58A"; }
        else if (bars >= 3) { bg = "#2E2A14"; fg = "#FFD166"; }
        else { bg = "#2E2A14"; fg = "#FFD166"; }
        signalUi(txt, bg, fg);
    }

    private void signalUi(String text, String bg, String fg) {
        main.post(() -> {
            tvSignal.setText(text);
            tvSignal.setBackgroundColor(Color.parseColor(bg));
            tvSignal.setTextColor(Color.parseColor(fg));
        });
    }

    private void doFlash() {
        Tag tag = lastTag;
        if (tag == null) { status("无标签，请贴壳"); return; }
        flashing = true;
        main.post(() -> {
            btnFlash.setEnabled(false);
            pbFlash.setVisibility(android.view.View.VISIBLE);
            pbFlash.setIndeterminate(true);
            tvSignal.setText("⏳ 刷图中，请保持贴合…");
            tvSignal.setBackgroundColor(Color.parseColor("#1A2630"));
            tvSignal.setTextColor(Color.parseColor("#9FB0BE"));
        });
        status("正在刷图，请保持壳子贴合不动…");
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) { log("该标签不支持 ISO-DEP"); flashing = false; status("失败：非 ISO-DEP");
            main.post(() -> { btnFlash.setEnabled(true); pbFlash.setVisibility(android.view.View.GONE); }); return; }
        final boolean[] reachedRefresh = {false};
        try {
            iso.connect();
            log("ISO-DEP 已连接，最大传输=" + iso.getMaxTransceiveLength());
            final byte[] img = packed;
            PanelProtocol.Transport tr = (cmd, timeout) -> { iso.setTimeout(timeout); return iso.transceive(cmd); };
            PanelProtocol.Control c = new PanelProtocol.Control() {
                public long now() { return SystemClock.elapsedRealtime(); }
                public void sleep(long ms) throws Exception { Thread.sleep(ms); }
                public void check() { }
                public void event(String k, String m) {
                    log(k + ": " + m);
                    if (k.equals("PROGRESS")) {
                        String[] p = m.split("\\|");
                        try {
                            int cur = Integer.parseInt(p[0]), tot = Integer.parseInt(p[1]);
                            main.post(() -> { pbFlash.setIndeterminate(false); pbFlash.setMax(tot);
                                pbFlash.setProgress(cur); tvSignal.setText("⏳ 上传 " + cur + "/" + tot); });
                        } catch (Exception ignored) { }
                    } else if (k.equals("STAGE")) {
                        String s = m.contains("|") ? m.substring(m.indexOf('|') + 1) : m;
                        if (m.startsWith("polling") || m.startsWith("refreshing")) reachedRefresh[0] = true;
                        main.post(() -> { tvSignal.setText("⏳ " + s);
                            if (m.startsWith("polling") || m.startsWith("refreshing")) pbFlash.setIndeterminate(true); });
                    }
                }
            };
            boolean ok = new PanelProtocol(tr, c).write(img, powerMs, POLL_MS);
            if (ok) {
                signalUi("✅ 刷新完成", "#13301F", "#5BE58A");
                status("刷新完成（换图后直接再点③，无需拿开）");
                main.post(() -> Toast.makeText(this, "刷新完成 ✅", Toast.LENGTH_LONG).show());
            } else {
                status("已结束，请目视确认画面");
                main.post(() -> Toast.makeText(this, "已结束，请目视确认画面", Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            String m = e.toString();
            log("刷图失败：" + e);
            boolean power = m.contains("68CA") || m.contains("6502") || m.contains("698A")
                    || m.contains("低电") || m.contains("供能");
            boolean lost = m.contains("TagLost") || m.contains("Tag was lost");
            boolean stale = m.contains("out of date");
            if (power || ((lost || stale) && reachedRefresh[0])) {
                // 刷新途中掉电/低压/标签失效 —— 本质都是供电撑不住刷新
                signalUi("⚡ 供电不足：刷新途中掉电，撑不过刷新", "#3A1420", "#FF6B6B");
                status("供电不足：调大「上电等待」、压紧、或换到能成的位置再刷");
                lastTag = null;
            } else if (lost || stale) {
                signalUi("标签已失效，请重新贴一下再刷", "#3A1420", "#FF6B6B");
                status("标签已失效：重新贴上壳子再点③");
                lastTag = null;
            } else {
                status("刷图失败：" + e);
            }
        } finally {
            try { iso.close(); } catch (Exception ignored) { }
            flashing = false;
            main.post(() -> { btnFlash.setEnabled(true); pbFlash.setVisibility(android.view.View.GONE); });
            if (lastTag != null) startProbe();  // 恢复自动信号检测
        }
    }
}
