package ar.edu.uade.pfi.backend.util;

import java.util.List;
import java.util.Map;

public final class AiReportEvidence {
    private AiReportEvidence() {
    }

    public static String latestRunId(Map<String, Object> reports) {
        Object items = reports.get("items");
        if (!(items instanceof List<?>)) return "";
        List<?> list = (List<?>) items;
        if (list.isEmpty()) return "";
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?>)) return "";
        Object runId = ((Map<?, ?>) first).get("runId");
        return runId == null ? "" : String.valueOf(runId);
    }

    public static boolean hasReports(Map<String, Object> reports) {
        Object count = reports.get("count");
        try {
            return Integer.parseInt(String.valueOf(count)) > 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
