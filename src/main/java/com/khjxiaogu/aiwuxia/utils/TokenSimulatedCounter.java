package com.khjxiaogu.aiwuxia.utils;

public class TokenSimulatedCounter {
    public static long fastCountLength(CharSequence text) {
        if (text == null || text.length()==0) {
            return 0L;
        }

        double total = 0.0;
        final int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            // 英文字母
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                total += 0.6;
            }
            // 中文汉字（基本区）
            else if (c >= 0x4E00 && c <= 0x9FFF) {
                total += 0.8;
            }
            // 其余符号
            else {
                total += 1.0;
            }
        }
        return (long) Math.ceil(total);
    }
}
