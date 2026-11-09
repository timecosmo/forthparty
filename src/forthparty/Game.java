package forthparty;

import java.util.List;

/** 게임 하나 + 그 게임에서 쓰는 마커 종류 목록 */
record Game(String id, String name, List<MarkerType> types) {
    Game withName(String n) { return new Game(id, n, types); }
    Game withTypes(List<MarkerType> t) { return new Game(id, name, List.copyOf(t)); }

    /** id로 종류 찾기. 목록에서 지워진 종류면 회색 '알 수 없는 종류' */
    MarkerType type(String typeId) {
        for (MarkerType t : types) if (t.id().equals(typeId)) return t;
        return MarkerType.unknown(typeId);
    }

    @Override public String toString() { return name; }
}
