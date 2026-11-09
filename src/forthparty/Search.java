package forthparty;

import java.util.Locale;

/** 마커 검색용 문자열 매칭. 공백·대소문자 무시, 한글 초성 검색 지원("ㅇㄹㅂㅇㅌ" → "엘리베이터"). */
final class Search {
    private Search() {}

    static boolean matches(String text, String query) {
        String q = norm(query);
        if (q.isEmpty()) return false;
        String t = norm(text);
        if (t.contains(q)) return true;
        return q.chars().allMatch(Search::isJamo) && chosung(t).contains(q);
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static final char[] CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ".toCharArray();

    private static String chosung(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= 0xAC00 && c <= 0xD7A3) sb.append(CHO[(c - 0xAC00) / (21 * 28)]);
            else sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isJamo(int c) { return c >= 0x3131 && c <= 0x314E; }
}
