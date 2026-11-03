package dev.einkflash;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.InputStream;

/**
 * 图像预处理与高清四色出图。
 *
 * 核心优化：
 * 1. 面积平均降采样：照片多为高分辨率（如 1200万~5000万像素），直接双线性缩放会导致隔点抽样噪声进入误差扩散。
 *    面积平均将源像素在网格内按面积加权平均，滤除高频噪点，使五官边缘与平滑过渡极为干净。
 * 2. 墨小佳四档高清算法（ReinkRaster）：鎏金 / 质感 / 色阶 / 水墨。
 *    采用 Atkinson 误差扩散（故意丢弃 2/8 误差）与 Redmean 人眼色彩感知距离，避免 Floyd-Steinberg 中间调满脸黄点的缺陷。
 */
public final class PanelImage {
    public static final int[] PALETTE = {ImageRaster.color(0), ImageRaster.color(1), ImageRaster.color(2), ImageRaster.color(3)};
    public static final String[] STYLES = ReinkRaster.MODES;

    private static final int THUMB_WIDTH = PanelProtocol.WIDTH / 2;
    private static final int THUMB_HEIGHT = PanelProtocol.HEIGHT / 2;

    public static final class Prepared {
        public final Bitmap preview;
        public final byte[] packed;
        public final String settings;
        public final String panelType;
        public final int density;

        public Prepared(Bitmap preview, byte[] packed, String settings, String panelType, int density) {
            this.preview = preview;
            this.packed = packed;
            this.settings = settings;
            this.panelType = panelType;
            this.density = density;
        }

        public Prepared(Bitmap preview, byte[] packed, String settings, String panelType) {
            this(preview, packed, settings, panelType, 0);
        }
    }

    public static Bitmap load(ContentResolver resolver, Uri uri) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, options); }
        if (options.outWidth < 1 || options.outHeight < 1) throw new IllegalArgumentException("无法识别图片");
        int sample = 1;
        while (Math.max(options.outWidth, options.outHeight) / sample > 2048) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap decoded;
        try (InputStream in = resolver.openInputStream(uri)) { decoded = BitmapFactory.decodeStream(in, null, options); }
        if (decoded == null) throw new IllegalArgumentException("图片解码失败");
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = resolver.openInputStream(uri)) {
            orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        } catch (Exception ignored) { }
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(270); m.postScale(-1, 1); break;
            case 8: m.setRotate(270); break;
            default: break;
        }
        return m.isIdentity() ? decoded : Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
    }

    public static Bitmap testPattern() {
        Bitmap b = Bitmap.createBitmap(552, 768, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(44);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.drawText("NFC 刷新测试", 44, 85, p);
        p.setTextSize(27);
        c.drawText("SN220U / Q-Poll", 44, 132, p);
        for (int i = 0; i < 4; i++) {
            p.setColor(PALETTE[i]);
            c.drawRect(i * 138, 180, (i + 1) * 138, 570, p);
        }
        p.setColor(Color.BLACK);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawRect(3, 3, 549, 765, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(28);
        c.drawText("552 × 768", 44, 640, p);
        c.drawText("顶部 ↑    黑 白 黄 红", 44, 690, p);
        return b;
    }

    public static Bitmap rotated(Bitmap source, int rotation) {
        if (rotation == 0) return source;
        Matrix rotate = new Matrix();
        rotate.setRotate(rotation);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), rotate, true);
    }

    /**
     * 面积平均降采样：针对手机超大分辨率照片转 552×768。
     * 当 scale < 1 时按覆盖面积加权平均，平滑传感器噪点；scale >= 1 时使用双线性插值。
     */
    public static int[] fit(Bitmap input, int width, int height, boolean cover) {
        int sourceWidth = input.getWidth(), sourceHeight = input.getHeight();
        float scale = cover ? Math.max(width / (float) sourceWidth, height / (float) sourceHeight)
                            : Math.min(width / (float) sourceWidth, height / (float) sourceHeight);
        if (scale >= 1) {
            Bitmap fitted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(fitted);
            canvas.drawColor(Color.WHITE);
            float w = sourceWidth * scale, h = sourceHeight * scale;
            canvas.drawBitmap(input, null, new RectF((width - w) / 2, (height - h) / 2,
                    (width + w) / 2, (height + h) / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] pixels = new int[width * height];
            fitted.getPixels(pixels, 0, width, 0, 0, width, height);
            return pixels;
        }
        int[] source = new int[sourceWidth * sourceHeight];
        input.getPixels(source, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight);
        int[] out = new int[width * height];
        java.util.Arrays.fill(out, 0xffffffff);
        float drawnWidth = sourceWidth * scale, drawnHeight = sourceHeight * scale;
        float originX = (width - drawnWidth) / 2f, originY = (height - drawnHeight) / 2f;
        for (int y = 0; y < height; y++) {
            double top = (y - originY) / scale, bottom = (y + 1 - originY) / scale;
            if (bottom <= 0 || top >= sourceHeight) continue;
            top = Math.max(0, top); bottom = Math.min(sourceHeight, bottom);
            int firstRow = (int) Math.floor(top), lastRow = (int) Math.ceil(bottom);
            for (int x = 0; x < width; x++) {
                double left = (x - originX) / scale, right = (x + 1 - originX) / scale;
                if (right <= 0 || left >= sourceWidth) continue;
                left = Math.max(0, left); right = Math.min(sourceWidth, right);
                int firstColumn = (int) Math.floor(left), lastColumn = (int) Math.ceil(right);
                double sumRed = 0, sumGreen = 0, sumBlue = 0, weight = 0;
                for (int sy = firstRow; sy < lastRow; sy++) {
                    double overlapY = Math.min(bottom, sy + 1) - Math.max(top, sy);
                    if (overlapY <= 0) continue;
                    for (int sx = firstColumn; sx < lastColumn; sx++) {
                        double overlapX = Math.min(right, sx + 1) - Math.max(left, sx);
                        if (overlapX <= 0) continue;
                        double area = overlapX * overlapY;
                        int pixel = source[sy * sourceWidth + sx];
                        double alpha = ((pixel >>> 24) / 255.0) * area, blank = area - alpha;
                        sumRed += (((pixel >>> 16) & 255) * alpha + 255 * blank);
                        sumGreen += (((pixel >>> 8) & 255) * alpha + 255 * blank);
                        sumBlue += ((pixel & 255) * alpha + 255 * blank);
                        weight += area;
                    }
                }
                if (weight <= 0) continue;
                out[y * width + x] = 0xff000000 | ((int) Math.round(sumRed / weight) << 16)
                        | ((int) Math.round(sumGreen / weight) << 8) | (int) Math.round(sumBlue / weight);
            }
        }
        return out;
    }

    public static int[] fittedPixels(Bitmap source, int rotation, boolean cover) {
        return fit(rotated(source, rotation), PanelProtocol.WIDTH, PanelProtocol.HEIGHT, cover);
    }

    /**
     * 使用已经完成缩放的 552×768 像素数组出图。
     */
    public static Prepared prepare(int[] pixels, String panelType, int mode) {
        if (pixels == null || pixels.length != PanelProtocol.WIDTH * PanelProtocol.HEIGHT)
            throw new IllegalArgumentException("像素尺寸错误");
        byte[] codes = ReinkRaster.quantize(pixels, PanelProtocol.WIDTH, PanelProtocol.HEIGHT, mode);
        PanelType type = panelType.isEmpty() ? PanelType.A1 : PanelType.parse(panelType);
        byte[] packed = type.pack(codes);
        Bitmap image = Bitmap.createBitmap(type.preview(packed), PanelProtocol.WIDTH, PanelProtocol.HEIGHT, Bitmap.Config.ARGB_8888);
        int dens = density(codes, PanelProtocol.WIDTH, PanelProtocol.HEIGHT);
        String settings = "风格：" + ReinkRaster.MODES[mode] + " · 非白占比 " + dens + "% · 屏幕：" + (panelType.isEmpty() ? "A1(默认)" : panelType);
        return new Prepared(image, packed, settings, panelType, dens);
    }

    public static Prepared prepare(Bitmap source, int rotation, boolean cover, String panelType, int mode) {
        int[] pixels = fittedPixels(source, rotation, cover);
        return prepare(pixels, panelType, mode);
    }

    /** 兼容旧接口重载，默认使用质感模式 (mode=1) */
    public static Prepared prepare(Bitmap source, int rotation, boolean cover, boolean dither, int block, boolean binary, int threshold, boolean cropped, String panelType) {
        int mode = binary ? ReinkRaster.MODE_INK : ReinkRaster.MODE_TEXTURE;
        return prepare(source, rotation, cover, panelType, mode);
    }

    /** 为四档风格快速生成 276×384 缩略图 */
    public static Bitmap[] thumbnails(int[] fullPixels) {
        if (fullPixels == null || fullPixels.length != PanelProtocol.WIDTH * PanelProtocol.HEIGHT)
            throw new IllegalArgumentException("像素尺寸错误");
        int[] pixels = new int[THUMB_WIDTH * THUMB_HEIGHT];
        for (int row = 0; row < THUMB_HEIGHT; row++) {
            for (int column = 0; column < THUMB_WIDTH; column++) {
                pixels[row * THUMB_WIDTH + column] = fullPixels[(row * 2) * PanelProtocol.WIDTH + column * 2];
            }
        }
        Bitmap[] result = new Bitmap[ReinkRaster.MODES.length];
        for (int m = 0; m < result.length; m++) {
            byte[] codes = ReinkRaster.quantize(pixels, THUMB_WIDTH, THUMB_HEIGHT, m);
            byte[] packed = PanelProtocol.packCodes(codes, THUMB_WIDTH, THUMB_HEIGHT);
            int[] argb = ImageRaster.preview(packed, THUMB_WIDTH, THUMB_HEIGHT);
            result[m] = Bitmap.createBitmap(argb, THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888);
        }
        return result;
    }

    public static int density(byte[] codes, int width, int height) {
        int lit = 0;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                if ((codes[row * width + column] & 3) != 1) lit++;
            }
        }
        return Math.round(100f * lit / (width * (float) height));
    }
}
