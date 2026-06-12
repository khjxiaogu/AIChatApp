package com.khjxiaogu.aiwuxia.utils;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * 图像噪声工具类
 */
public class ImageNoise {

    /**
     * 向图片添加拉普拉斯分布（双指数分布）噪声。
     *
     * @param original 原始图片，不能为 null
     * @param scale    拉普拉斯分布的尺度参数 b（标准偏差的缩放因子），值越大噪声越强，建议范围 1~50
     * @param mean     拉普拉斯分布的均值，通常设为 0
     * @return 添加噪声后的新图片
     */
    public static void addLaplaceNoise(BufferedImage original, double scale, double mean) {
        if (original == null) {
            throw new IllegalArgumentException("原始图像不能为 null");
        }
        if (scale <= 0) {
            throw new IllegalArgumentException("尺度参数 b 必须大于 0");
        }

        int width = original.getWidth();
        int height = original.getHeight();

        // 确定图像类型（保留透明度）
        int type = original.getType();
        if (type == BufferedImage.TYPE_CUSTOM) {
            // 若类型未知，根据是否有 alpha 选择标准类型
            type = original.getColorModel().hasAlpha()
                    ? BufferedImage.TYPE_INT_ARGB
                    : BufferedImage.TYPE_INT_RGB;
        }

        Random rand = new Random();

        // 遍历每个像素
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = original.getRGB(x, y);
                int a = (rgb >> 24) & 0xFF; // Alpha 通道
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                // 对每个颜色通道添加拉普拉斯噪声
                r = clamp(r + generateLaplace(rand, mean, scale));
                g = clamp(g + generateLaplace(rand, mean, scale));
                b = clamp(b + generateLaplace(rand, mean, scale));

                // 组装新像素（Alpha 保持不变）
                int newRgb = (a << 24) | (r << 16) | (g << 8) | b;
                original.setRGB(x, y, newRgb);
            }
        }
    }

    /**
     * 生成拉普拉斯分布的随机数。
     * 使用公式：mean + scale * ln(U1/U2)，其中 U1,U2 为 (0,1) 均匀分布。
     *
     * @param rand  随机数生成器
     * @param mean  均值 μ
     * @param scale 尺度参数 b (>0)
     * @return 拉普拉斯分布随机数
     */
    private static int generateLaplace(Random rand, double mean, double scale) {
        double u1 = rand.nextDouble(); // (0,1)
        double u2 = rand.nextDouble(); // (0,1)
        // 避免除以 0 或取对数时出现 0
        while (u1 == 0) u1 = rand.nextDouble();
        while (u2 == 0) u2 = rand.nextDouble();
        double laplace = mean + scale * Math.log(u1 / u2);
        return (int) Math.round(laplace);
    }

    /**
     * 将像素值限制在 [0, 255] 范围内
     */
    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    // ----------------- 便捷方法 -----------------
    /**
     * 添加零均值拉普拉斯噪声（均值默认为 0）
     * @param image 原始图片
     * @param scale 尺度参数 b
     * @return 带噪图片
     */
    public static void addLaplaceNoise(BufferedImage image, double scale) {
        addLaplaceNoise(image, scale, 0);
    }
}