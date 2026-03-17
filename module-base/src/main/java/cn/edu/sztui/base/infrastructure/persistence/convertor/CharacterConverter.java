package cn.edu.sztui.base.infrastructure.persistence.convertor;

public class CharacterConverter {

    /**
     * 全角转半角字符
     * @param str 输入字符串
     * @return 转换后的字符串
     */
    public static String toSBC(String str) {
        if (str == null || str.isEmpty())  return str;
        StringBuilder result = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            int cCode = (int) c;
            // 全角与半角相差（除空格外）：65248（十进制）
            if (cCode >= 0xFF01 && cCode <= 0xFF5E)cCode -= 65248;
            // 处理全角空格（0x3000）转半角空格（0x0020）
            else if (cCode == 0x3000)cCode = 0x0020;
            result.append((char) cCode);
        }
        return result.toString();
    }

    /**
     * 更优雅的版本，使用数组预定义映射关系
     */
    public static String toSBC2(String str) {
        if (str == null || str.isEmpty()) return str;
        char[] chars = str.toCharArray();
        for (int i = 0; i < chars.length; i++)
            chars[i] = toHalfWidthChar(chars[i]);
        return new String(chars);
    }

    /**
     * 单个字符全角转半角
     */
    private static char toHalfWidthChar(char c) {
        // 全角空格
        if (c == '　') return ' ';// 全角空格
        // 全角ASCII字符（！到～）
        if (c >= '！' && c <= '～') return (char) (c - '！' + '!');
        // 其他字符保持不变
        return c;
    }

    /**
     * 半角转全角（反向转换）
     */
    public static String toDBC(String str) {
        if (str == null || str.isEmpty())return str;
        StringBuilder result = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            int cCode = (int) c;
            // 半角转全角：加65248
            if (cCode >= 0x21 && cCode <= 0x7E) cCode += 65248;
            // 处理半角空格（0x0020）转全角空格（0x3000）
            else if (cCode == 0x0020) cCode = 0x3000;
            result.append((char) cCode);
        }

        return result.toString();
    }

    /**
     * 更完整的版本，包含更多字符类型的转换
     */
    public static String toSBCComplete(String str) {
        if (str == null || str.isEmpty()) return str;
        char[] chars = str.toCharArray();
        for (int i = 0; i < chars.length; i++)
            chars[i] = toHalfWidthCharComplete(chars[i]);
        return new String(chars);
    }

    /**
     * 更完整的单个字符转换
     */
    private static char toHalfWidthCharComplete(char c) {
        int code = (int) c;
        // 处理空格
        // 全角空格->半角空格
        if (code == 0x3000) return 0x0020;
        // 处理数字
        if (code >= 0xFF10 && code <= 0xFF19)return (char) (code - 0xFF10 + '0');
        // 处理大写字母// 全角A-Z
        if (code >= 0xFF21 && code <= 0xFF3A) return (char) (code - 0xFF21 + 'A');
        // 处理小写字母// 全角a-z
        if (code >= 0xFF41 && code <= 0xFF5A) return (char) (code - 0xFF41 + 'a');
        // 处理标点符号
        int[][] mapping = {
                {0xFF01, '!'}, {0xFF02, '"'}, {0xFF03, '#'}, {0xFF04, '$'},
                {0xFF05, '%'}, {0xFF06, '&'}, {0xFF07, '\''}, {0xFF08, '('},
                {0xFF09, ')'}, {0xFF0A, '*'}, {0xFF0B, '+'}, {0xFF0C, ','},
                {0xFF0D, '-'}, {0xFF0E, '.'}, {0xFF0F, '/'}, {0xFF1A, ':'},
                {0xFF1B, ';'}, {0xFF1C, '<'}, {0xFF1D, '='}, {0xFF1E, '>'},
                {0xFF1F, '?'}, {0xFF20, '@'}, {0xFF3B, '['}, {0xFF3C, '\\'},
                {0xFF3D, ']'}, {0xFF3E, '^'}, {0xFF3F, '_'}, {0xFF40, '`'},
                {0xFF5B, '{'}, {0xFF5C, '|'}, {0xFF5D, '}'}, {0xFF5E, '~'}
        };

        for (int[] map : mapping) {
            if (code == map[0]) {
                return (char) map[1];
            }
        }

        // 其他字符保持不变
        return c;
    }

    /**
     * 测试方法
     */
    public static void main(String[] args) {
        // 测试示例
        String fullWidth = "Ｈｅｌｌｏ　Ｗｏｒｌｄ！　１２３ＡＢＣ";
        String halfWidth = toSBC(fullWidth);

        System.out.println("全角字符串: " + fullWidth);
        System.out.println("转半角后: " + halfWidth);

        // 测试反向转换
        String backToFull = toDBC(halfWidth);
        System.out.println("转回全角: " + backToFull);

        // 测试边界情况
        System.out.println("测试空字符串: " + toSBC(""));
        System.out.println("测试null: " + toSBC(null));
    }
}
