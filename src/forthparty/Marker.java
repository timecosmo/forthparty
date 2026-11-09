package forthparty;

/**
 * 맵 이미지 위의 지점. x, y는 이미지 크기 기준 0~1 비율이라 이미지 해상도를 바꿔도 위치가 유지된다.
 * typeId는 게임의 마커 종류(MarkerType) id를 가리킨다.
 */
public record Marker(String typeId, double x, double y, String label, String memo) {

    Marker moveTo(double nx, double ny) { return new Marker(typeId, nx, ny, label, memo); }
    Marker withContent(String type, String l, String m) { return new Marker(type, x, y, l, m); }

    /** 저장 형식: 종류|x|y|이름|메모. 메모는 마지막 칸이라 '|'나 줄바꿈이 들어가도 된다 (줄바꿈은 properties가 처리) */
    public String serialize() {
        return typeId + "|" + x + "|" + y + "|" + label.replace('|', '/') + "|" + memo;
    }

    /** 예전 형식(종류|x|y|이름)도 그대로 읽힌다 */
    public static Marker parse(String s) {
        String[] p = s.split("\\|", 5);
        return new Marker(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                p.length > 3 ? p[3] : "", p.length > 4 ? p[4] : "");
    }
}
