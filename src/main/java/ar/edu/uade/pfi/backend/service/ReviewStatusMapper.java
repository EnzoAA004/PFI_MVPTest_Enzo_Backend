package ar.edu.uade.pfi.backend.service;

public final class ReviewStatusMapper {
    private ReviewStatusMapper() {}

    public static String toApiStatus(String dbStatus) {
        if (dbStatus == null || dbStatus.isBlank()) return null;
        return switch (dbStatus) {
            case "pending" -> "pendiente";
            case "accepted" -> "aceptado";
            case "observed", "edited" -> "observado";
            case "rejected" -> "descartado";
            default -> dbStatus;
        };
    }

    public static String toDbStatus(String apiStatus) {
        if (apiStatus == null || apiStatus.isBlank()) return null;
        return switch (apiStatus) {
            case "pendiente" -> "pending";
            case "aceptado" -> "accepted";
            case "observado" -> "observed";
            case "descartado" -> "rejected";
            default -> apiStatus;
        };
    }

    public static String toApiPriority(String dbPriority) {
        if (dbPriority == null || dbPriority.isBlank()) return null;
        return switch (dbPriority) {
            case "high" -> "alta";
            case "medium" -> "media";
            case "low" -> "baja";
            default -> dbPriority;
        };
    }

    public static String toDbPriority(String apiPriority) {
        if (apiPriority == null || apiPriority.isBlank()) return "medium";
        return switch (apiPriority) {
            case "alta" -> "high";
            case "media" -> "medium";
            case "baja" -> "low";
            default -> apiPriority;
        };
    }
}
