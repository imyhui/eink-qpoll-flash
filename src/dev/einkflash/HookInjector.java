package dev.einkflash;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Layer 1 唤醒桥：用 root 把 frida-inject + hook.js 注入 com.android.nfc。
 * hook.js 来自 nfc-qpoll-ksu（MIT）；frida-inject 为官方发行（wxWindows 许可）。
 * 本层是唯一需要 root 的部分；刷图本身无需 root。 */
public final class HookInjector {
    public interface Log { void line(String s); }

    /** 把一个 asset 释放到 App 私有目录（App 自身可写），返回绝对路径。 */
    private static String extract(Context ctx, String asset, String outName) throws Exception {
        File out = new File(ctx.getFilesDir(), outName);
        try (InputStream in = ctx.getAssets().open(asset);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
        return out.getAbsolutePath();
    }

    /** 同步执行注入（必须在后台线程调用）。成功返回 true。 */
    public static boolean inject(Context ctx, Log log) {
        try {
            String injector = extract(ctx, "frida-inject", "frida-inject");
            String script = extract(ctx, "nfcq-hook.js", "nfcq-hook.js");
            // 全部交给 root shell：复制到 /data/local/tmp、按 nfc 进程 pid 去重、注入。
            String sh =
                "NFC=$(pidof com.android.nfc); " +
                "[ -z \"$NFC\" ] && echo 'ERR: com.android.nfc 未运行' && exit 1; " +
                "SENT=/data/local/tmp/eink-hook-pid; " +
                "if [ -f \"$SENT\" ] && [ \"$(cat $SENT)\" = \"$NFC\" ]; then echo \"ALREADY: 已注入 (nfc pid=$NFC)\"; exit 0; fi; " +
                "cp '" + injector + "' /data/local/tmp/eink-frida-inject || { echo 'ERR: 复制注入器失败'; exit 2; }; " +
                "cp '" + script + "' /data/local/tmp/eink-hook.js || { echo 'ERR: 复制脚本失败'; exit 3; }; " +
                "chmod 700 /data/local/tmp/eink-frida-inject; " +
                "/data/local/tmp/eink-frida-inject -n com.android.nfc -s /data/local/tmp/eink-hook.js -e; " +
                "RC=$?; if [ $RC -eq 0 ]; then echo \"$NFC\" > \"$SENT\"; " +
                "echo '重启 NFC 寻卡，使 hook 对寻卡命令生效…'; " +
                "svc nfc disable; sleep 1; svc nfc enable; echo 'NFC 已重启'; fi; echo \"RC=$RC\"";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", sh});
            boolean[] ok = {false};
            Thread err = pump(p.getErrorStream(), log, ok);
            Thread okt = pump(p.getInputStream(), log, ok);
            int code = p.waitFor();
            err.join(2000); okt.join(2000);
            log.line("su 退出码=" + code);
            return ok[0];
        } catch (Exception e) {
            log.line("注入异常：" + e);
            return false;
        }
    }

    private static Thread pump(InputStream is, Log log, boolean[] ok) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String s;
                while ((s = r.readLine()) != null) {
                    log.line(s);
                    if (s.contains("hooks installed") || s.startsWith("ALREADY")) ok[0] = true;
                }
            } catch (Exception ignored) { }
        });
        t.start();
        return t;
    }

    /** 用 root 检测当前是否已注入（哨兵文件里的 nfc pid == 当前 nfc pid）。后台线程调用。 */
    public static boolean isInjected() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                "SENT=/data/local/tmp/eink-hook-pid; [ -f \"$SENT\" ] && [ \"$(cat $SENT)\" = \"$(pidof com.android.nfc)\" ] && echo YES"});
            boolean yes = false;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String s;
                while ((s = r.readLine()) != null) if (s.contains("YES")) yes = true;
            }
            p.waitFor();
            return yes;
        } catch (Exception e) { return false; }
    }

    private HookInjector() { }
}
