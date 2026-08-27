package com.picoxr.vsleep;

/** Pure Java v2 request parsing and state decisions. */
final class CoordinationProtocol {
    static final String VERSION = "2";
    static final String OWNER_VSLEEP = "vsleep";
    static final String OWNER_POWER = "power";
    static final String PHASE_ACTIVE = "active";
    static final String PHASE_RESTORING = "restoring";
    static final String PHASE_ERROR = "error";

    private CoordinationProtocol() {}

    static String request(String token, String owner, String payload) {
        if (token == null || token.length() == 0 || owner == null || owner.length() == 0 || payload == null) return null;
        return VERSION + "|" + token + "|" + owner + "|" + payload;
    }

    static Request parse(String value) {
        if (value == null) return null;
        String[] p = value.split("\\|", 4);
        if (p.length != 4 || !VERSION.equals(p[0]) || empty(p[1]) || empty(p[2]) || empty(p[3])) return null;
        if (!OWNER_VSLEEP.equals(p[2]) && !OWNER_POWER.equals(p[2])) return null;
        return new Request(value, p[1], p[2], p[3]);
    }

    static boolean tokenMatches(String request, String token) {
        Request parsed = parse(request);
        return parsed != null && token != null && token.equals(parsed.token);
    }

    static boolean laterRequestWins(String latest, String observed) {
        return latest != null && !latest.equals(observed) && parse(latest) != null;
    }

    static boolean effectiveUiEnabled(String effectiveOwner, String phase, boolean committed) {
        return committed && OWNER_VSLEEP.equals(effectiveOwner) && PHASE_ACTIVE.equals(phase);
    }

    private static boolean empty(String v) { return v == null || v.length() == 0; }

    static final class Request {
        final String raw, token, owner, payload;
        Request(String raw, String token, String owner, String payload) {
            this.raw = raw; this.token = token; this.owner = owner; this.payload = payload;
        }
    }
}
