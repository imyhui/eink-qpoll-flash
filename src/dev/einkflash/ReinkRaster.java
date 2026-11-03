package dev.einkflash;

/**
 * 墨小佳（Reink Case 官方 App）四种出图风格的移植。
 *
 * <p>算法来自它的 Flutter debug 构建：{@code assets/flutter_assets/kernel_blob.bin}
 * 里的 Dart 源码字符串池（77.7MB，业务逻辑是明文的）。原文在 {@code quick_theme_page.dart}：
 *
 * <pre>
 * RSAlgType algT = RSAlgType.atkinson;          // 默认 Atkinson，不是 Floyd
 * index 0 鎏金：c2_1 屏(mode==7) 直接用原图；其他屏走 matrixLANDIAO
 * index 1 质感：matrixLOMO
 * index 2 色阶：原图 + RSAlgType.color（纯量化，完全不抖动）
 * index 3 水墨：img.grayscale() + Atkinson
 * </pre>
 *
 * <p>实测那块屏是 c2_1，所以鎏金这一档就是「原图 + Atkinson」，不套色彩矩阵。
 *
 * <p><b>为什么它看起来更清楚</b>：关键不在翻转密度，在色调分布。质感用的
 * {@link #LOMO} 是 {@code 1.7 × 通道 - 73.1}，画一条 1.9x - 73.1 = x 的线，交点在 81：
 * 亮度低于 81 的压得更黑，高于 81 的整体往白端推。照片主体（皮肤、天空、背景）几乎全部
 * 落在 81 以上，于是被推成大片纯白或接近纯白，只有五官、发丝、衣褶留下黑点。
 * 纯白区域不抖动 = 不闪 = 看着干净锐利。<b>密度高不等于清晰，点撒在哪儿才决定清晰。</b>
 *
 * <p>Atkinson 本身也值得说：它把误差的 6/8 分给 6 个邻居，<b>故意丢掉 2/8</b>。
 * 这是它和 Floyd-Steinberg 最大的区别——网点稀疏、不粘连，出来是「铅笔素描」的质感，
 * 不像 FS 那样把中间调铺满。
 */
public final class ReinkRaster {
    /** 风格名，顺序与 {@code MODE_*} 常量一致。界面按钮直接取这里。 */
    public static final String[] MODES = {"鎏金", "质感", "色阶", "水墨"};
    /** 每个风格一句话说明，长按/选中时给用户看。 */
    public static final String[] HINTS = {
            "原图 + Atkinson 抖动 · 官方默认，细节最全",
            "LOMO 调色 + Atkinson · 大片推白只留深部，最像官方效果",
            "纯量化不抖动 · 色块最大，最省电也最糊",
            "灰阶 + Atkinson · 素描感，人脸最柔和"
    };
    public static final int MODE_GOLD = 0;
    public static final int MODE_TEXTURE = 1;
    public static final int MODE_LEVEL = 2;
    public static final int MODE_INK = 3;

    /**
     * 质感模式的色彩矩阵（墨小佳 {@code matrixLOMO} 原值，4×5，0–255 的 sRGB）。
     * 增益 1.7、偏置 -73.1，中间调整体往高光端推，是四档里最出片的一个。
     */
    static final double[] LOMO = {
            1.7, 0.1, 0.1, 0, -73.1,
            0.0, 1.7, 0.1, 0, -73.1,
            0.0, 0.1, 1.6, 0, -73.1,
            0.0, 0.0, 0.0, 1, 0.0
    };

    private static final int[] COLORS = {0xff000000, 0xffffffff, 0xfff5cd00, 0xffc80000};
    /**
     * Atkinson 误差扩散核：右 1 / 右 2 / 左下 / 下 / 右下 / 下 2，每个 1/8。
     * 合计只有 6/8，剩下 2/8 是<b>故意丢掉</b>的——这是 Atkinson 的名门特征。
     */
    private static final int[][] ATKINSON = {{1, 0}, {2, 0}, {-1, 1}, {0, 1}, {1, 1}, {0, 2}};

    private ReinkRaster() {}

    /**
     * 按风格出图。
     *
     * @param mode {@link #MODE_GOLD} / {@link #MODE_TEXTURE} / {@link #MODE_LEVEL} / {@link #MODE_INK}
     */
    public static byte[] quantize(int[] pixels, int width, int height, int mode) {
        if (pixels == null || width < 1 || height < 1 || (long) width * height != pixels.length
                || mode < 0 || mode >= MODES.length)
            throw new IllegalArgumentException("无效的出图参数");
        double[][] channels = channels(pixels);
        double[] red = channels[0];
        double[] green = channels[1];
        double[] blue = channels[2];
        switch (mode) {
            case MODE_TEXTURE:
                transform(red, green, blue, LOMO);
                return dither(red, green, blue, width, height);
            case MODE_LEVEL:
                // 色阶：墨小佳的 RSAlgType.color，纯量化、一个点都不抖。
                return nearestOnly(red, green, blue);
            case MODE_INK:
                grayscale(red, green, blue);
                return dither(red, green, blue, width, height);
            case MODE_GOLD:
            default:
                // 鎏金：官方在 c2_1 屏上直接用原图，只套 Atkinson。
                return dither(red, green, blue, width, height);
        }
    }

    /** 把 ARGB 拆成三条 0–255 的浮点通道。半透明像素先按白底合成（跟界面预览一致）。 */
    private static double[][] channels(int[] pixels) {
        int count = pixels.length;
        double[] red = new double[count];
        double[] green = new double[count];
        double[] blue = new double[count];
        for (int index = 0; index < count; index++) {
            int pixel = pixels[index];
            double alpha = (pixel >>> 24) / 255.0;
            red[index] = ((pixel >>> 16) & 255) * alpha + 255 * (1 - alpha);
            green[index] = ((pixel >>> 8) & 255) * alpha + 255 * (1 - alpha);
            blue[index] = (pixel & 255) * alpha + 255 * (1 - alpha);
        }
        return new double[][]{red, green, blue};
    }

    /** 原地套 4×5 色彩矩阵（跟 Dart {@code img.adjustColor} 的 matrix 参数同构）。 */
    private static void transform(double[] red, double[] green, double[] blue, double[] matrix) {
        for (int index = 0; index < red.length; index++) {
            double sourceRed = red[index];
            double sourceGreen = green[index];
            double sourceBlue = blue[index];
            red[index] = clamp(matrix[0] * sourceRed + matrix[1] * sourceGreen + matrix[2] * sourceBlue
                    + matrix[3] * 255 + matrix[4]);
            green[index] = clamp(matrix[5] * sourceRed + matrix[6] * sourceGreen + matrix[7] * sourceBlue
                    + matrix[8] * 255 + matrix[9]);
            blue[index] = clamp(matrix[10] * sourceRed + matrix[11] * sourceGreen + matrix[12] * sourceBlue
                    + matrix[13] * 255 + matrix[14]);
        }
    }

    /** 灰阶：Dart {@code img.grayscale()} 用的是 ITU-R BT.601 那套权重。 */
    private static void grayscale(double[] red, double[] green, double[] blue) {
        for (int index = 0; index < red.length; index++) {
            double luminance = .299 * red[index] + .587 * green[index] + .114 * blue[index];
            red[index] = luminance;
            green[index] = luminance;
            blue[index] = luminance;
        }
    }

    /** 纯量化，不抖动。 */
    private static byte[] nearestOnly(double[] red, double[] green, double[] blue) {
        byte[] codes = new byte[red.length];
        for (int index = 0; index < red.length; index++)
            codes[index] = (byte) nearest(red[index], green[index], blue[index]);
        return codes;
    }

    /** Atkinson 误差扩散。 */
    private static byte[] dither(double[] red, double[] green, double[] blue, int width, int height) {
        int count = red.length;
        float[] errorRed = new float[count];
        float[] errorGreen = new float[count];
        float[] errorBlue = new float[count];
        byte[] codes = new byte[count];
        for (int row = 0; row < height; row++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                double sampleRed = clamp(red[index] + errorRed[index]);
                double sampleGreen = clamp(green[index] + errorGreen[index]);
                double sampleBlue = clamp(blue[index] + errorBlue[index]);
                int code = nearest(sampleRed, sampleGreen, sampleBlue);
                codes[index] = (byte) code;
                int color = COLORS[code];
                float deltaRed = (float) (sampleRed - ((color >>> 16) & 255));
                float deltaGreen = (float) (sampleGreen - ((color >>> 8) & 255));
                float deltaBlue = (float) (sampleBlue - (color & 255));
                for (int[] step : ATKINSON) {
                    int targetRow = row + step[1];
                    int targetColumn = column + step[0];
                    if (targetRow >= height || targetColumn < 0 || targetColumn >= width) continue;
                    int target = targetRow * width + targetColumn;
                    errorRed[target] += deltaRed / 8;
                    errorGreen[target] += deltaGreen / 8;
                    errorBlue[target] += deltaBlue / 8;
                }
            }
        }
        return codes;
    }

    /**
     * 最近色。距离用「红均值加权」（redmean）而不是朴素欧氏距离——
     * {@code package:image} 的 {@code _colorDistance} 就是这个，四个模式全都走它。
     * 它在红色附近放大误差、蓝色附近收缩，选出的色跟人眼更接近，
     * 换成朴素欧氏距离会在浅肤色上明显偏向黄色。
     */
    static int nearest(double red, double green, double blue) {
        int sampleRed = (int) Math.round(clamp(red));
        int sampleGreen = (int) Math.round(clamp(green));
        int sampleBlue = (int) Math.round(clamp(blue));
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < 4; index++) {
            int color = COLORS[index];
            int deltaRed = sampleRed - ((color >>> 16) & 255);
            int deltaGreen = sampleGreen - ((color >>> 8) & 255);
            int deltaBlue = sampleBlue - (color & 255);
            int mean = (sampleRed + ((color >>> 16) & 255)) / 2;
            long distance = ((512 + mean) * (long) deltaRed * deltaRed) >> 8;
            distance += 4L * deltaGreen * deltaGreen;
            distance += ((767 - mean) * (long) deltaBlue * deltaBlue) >> 8;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    private static double clamp(double value) { return Math.max(0, Math.min(255, value)); }
}
